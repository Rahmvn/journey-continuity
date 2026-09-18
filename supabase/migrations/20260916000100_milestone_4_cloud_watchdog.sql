begin;

create table public.journey_monitoring_state (
    journey_id uuid primary key references public.journeys(id) on delete restrict,
    owner_id uuid not null references auth.users(id) on delete restrict,
    phase text not null check (phase in ('EVIDENCE_FRESH', 'VERIFYING', 'CLOSED')),
    last_cloud_contact_at timestamptz,
    latest_heartbeat_sequence bigint not null default 0 check (latest_heartbeat_sequence >= 0),
    verifying_started_at timestamptz,
    closed_at timestamptz,
    updated_at timestamptz not null default now(),
    constraint journey_monitoring_phase_consistent check (
        (phase = 'EVIDENCE_FRESH' and verifying_started_at is null and closed_at is null)
        or (phase = 'VERIFYING' and verifying_started_at is not null and closed_at is null)
        or (phase = 'CLOSED' and closed_at is not null)
    )
);

create index journey_monitoring_watchdog_idx
on public.journey_monitoring_state (phase, last_cloud_contact_at);

create table public.journey_heartbeats (
    journey_id uuid not null references public.journeys(id) on delete restrict,
    sequence bigint not null check (sequence > 0),
    client_sent_at timestamptz not null,
    received_at timestamptz not null default now(),
    battery_percent integer check (battery_percent between 0 and 100),
    charging boolean,
    connectivity_state text not null check (
        connectivity_state in ('NONE', 'CELLULAR', 'WIFI', 'OTHER', 'UNKNOWN')
    ),
    latest_telemetry_sequence bigint not null check (latest_telemetry_sequence >= 0),
    primary key (journey_id, sequence)
);

create table public.journey_monitoring_events (
    id bigint generated always as identity primary key,
    journey_id uuid not null references public.journeys(id) on delete restrict,
    event_type text not null check (
        event_type in ('MONITORING_STARTED', 'VERIFYING_STARTED', 'CONTACT_RESTORED', 'MONITORING_CLOSED')
    ),
    occurred_at timestamptz not null default now(),
    previous_phase text check (previous_phase is null or previous_phase in ('EVIDENCE_FRESH', 'VERIFYING', 'CLOSED')),
    new_phase text not null check (new_phase in ('EVIDENCE_FRESH', 'VERIFYING', 'CLOSED')),
    last_cloud_contact_at timestamptz,
    reason_code text not null,
    threshold_seconds integer check (threshold_seconds is null or threshold_seconds > 0)
);

alter table public.journey_monitoring_state enable row level security;
alter table public.journey_heartbeats enable row level security;
alter table public.journey_monitoring_events enable row level security;

revoke all on table public.journey_monitoring_state from public, anon, authenticated;
revoke all on table public.journey_heartbeats from public, anon, authenticated;
revoke all on table public.journey_monitoring_events from public, anon, authenticated;
revoke all on sequence public.journey_monitoring_events_id_seq from public, anon, authenticated;

grant select on table public.journey_monitoring_state to authenticated;
grant select on table public.journey_heartbeats to authenticated;
grant select on table public.journey_monitoring_events to authenticated;

create policy monitoring_state_select_own
on public.journey_monitoring_state
for select
to authenticated
using (owner_id = (select auth.uid()));

create policy heartbeats_select_through_owned_journey
on public.journey_heartbeats
for select
to authenticated
using (
    exists (
        select 1 from public.journeys
        where journeys.id = journey_heartbeats.journey_id
          and journeys.owner_id = (select auth.uid())
    )
);

create policy monitoring_events_select_through_owned_journey
on public.journey_monitoring_events
for select
to authenticated
using (
    exists (
        select 1 from public.journeys
        where journeys.id = journey_monitoring_events.journey_id
          and journeys.owner_id = (select auth.uid())
    )
);

create or replace function public.record_journey_heartbeat(
    p_journey_id uuid,
    p_sequence bigint,
    p_client_sent_at timestamptz,
    p_battery_percent integer,
    p_charging boolean,
    p_connectivity_state text,
    p_latest_telemetry_sequence bigint
)
returns table (
    phase text,
    last_cloud_contact_at timestamptz,
    latest_heartbeat_sequence bigint,
    contact_restored boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner_id uuid;
    v_status text;
    v_received_at timestamptz;
    v_previous_phase text;
    v_inserted boolean := false;
    v_contact_restored boolean := false;
begin
    if (select auth.uid()) is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    select j.owner_id, j.status
    into v_owner_id, v_status
    from public.journeys as j
    where j.id = p_journey_id
    for update;

    if not found or v_owner_id <> (select auth.uid()) then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    if v_status <> 'ACTIVE' then
        raise exception 'Heartbeat requires an ACTIVE Journey' using errcode = '22023';
    end if;

    insert into public.journey_heartbeats (
        journey_id, sequence, client_sent_at, battery_percent, charging,
        connectivity_state, latest_telemetry_sequence
    ) values (
        p_journey_id, p_sequence, p_client_sent_at, p_battery_percent, p_charging,
        p_connectivity_state, p_latest_telemetry_sequence
    )
    on conflict (journey_id, sequence) do nothing
    returning received_at into v_received_at;

    if v_received_at is null then
        return query
        select s.phase, s.last_cloud_contact_at, s.latest_heartbeat_sequence, false
        from public.journey_monitoring_state as s
        where s.journey_id = p_journey_id;
        return;
    end if;

    insert into public.journey_monitoring_state (
        journey_id, owner_id, phase, last_cloud_contact_at,
        latest_heartbeat_sequence, updated_at
    ) values (
        p_journey_id, v_owner_id, 'EVIDENCE_FRESH', v_received_at,
        p_sequence, v_received_at
    )
    on conflict (journey_id) do nothing;
    v_inserted := found;

    if v_inserted then
        insert into public.journey_monitoring_events (
            journey_id, event_type, occurred_at, previous_phase, new_phase,
            last_cloud_contact_at, reason_code, threshold_seconds
        ) values (
            p_journey_id, 'MONITORING_STARTED', v_received_at, null, 'EVIDENCE_FRESH',
            v_received_at, 'FIRST_FRESH_HEARTBEAT', null
        );
    else
        select s.phase
        into v_previous_phase
        from public.journey_monitoring_state as s
        where s.journey_id = p_journey_id
        for update;

        if p_sequence > (
            select s.latest_heartbeat_sequence
            from public.journey_monitoring_state as s
            where s.journey_id = p_journey_id
        ) then
            v_contact_restored := v_previous_phase = 'VERIFYING';
            update public.journey_monitoring_state as s
            set phase = case when s.phase = 'VERIFYING' then 'EVIDENCE_FRESH' else s.phase end,
                last_cloud_contact_at = v_received_at,
                latest_heartbeat_sequence = p_sequence,
                verifying_started_at = case when s.phase = 'VERIFYING' then null else s.verifying_started_at end,
                updated_at = v_received_at
            where s.journey_id = p_journey_id
              and s.phase <> 'CLOSED';

            if v_contact_restored then
                insert into public.journey_monitoring_events (
                    journey_id, event_type, occurred_at, previous_phase, new_phase,
                    last_cloud_contact_at, reason_code, threshold_seconds
                ) values (
                    p_journey_id, 'CONTACT_RESTORED', v_received_at, 'VERIFYING', 'EVIDENCE_FRESH',
                    v_received_at, 'FRESH_HEARTBEAT', null
                );
            end if;
        end if;
    end if;

    return query
    select s.phase, s.last_cloud_contact_at, s.latest_heartbeat_sequence, v_contact_restored
    from public.journey_monitoring_state as s
    where s.journey_id = p_journey_id;
end;
$$;

create or replace function public.close_journey_monitoring_on_completion()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_now timestamptz := now();
    v_previous_phase text;
    v_last_contact timestamptz;
begin
    if new.status <> 'COMPLETED' then
        return new;
    end if;

    select s.phase, s.last_cloud_contact_at
    into v_previous_phase, v_last_contact
    from public.journey_monitoring_state as s
    where s.journey_id = new.id
    for update;

    if not found then
        insert into public.journey_monitoring_state (
            journey_id, owner_id, phase, latest_heartbeat_sequence, closed_at, updated_at
        ) values (new.id, new.owner_id, 'CLOSED', 0, v_now, v_now);
        v_previous_phase := null;
    elsif v_previous_phase = 'CLOSED' then
        return new;
    else
        update public.journey_monitoring_state
        set phase = 'CLOSED', verifying_started_at = null, closed_at = v_now, updated_at = v_now
        where journey_id = new.id;
    end if;

    insert into public.journey_monitoring_events (
        journey_id, event_type, occurred_at, previous_phase, new_phase,
        last_cloud_contact_at, reason_code, threshold_seconds
    ) values (
        new.id, 'MONITORING_CLOSED', v_now, v_previous_phase, 'CLOSED',
        v_last_contact, 'JOURNEY_COMPLETED', null
    );
    return new;
end;
$$;

create trigger journeys_close_monitoring_on_completion
after insert or update of status on public.journeys
for each row execute function public.close_journey_monitoring_on_completion();

create or replace function public.evaluate_due_journeys()
returns table (
    evaluated_at timestamptz,
    verifying_started integer,
    monitoring_closed integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_now timestamptz := now();
    v_threshold_seconds constant integer := 300;
    v_verifying_started integer := 0;
    v_monitoring_closed integer := 0;
begin
    with due as (
        select s.journey_id, s.phase as previous_phase, s.last_cloud_contact_at
        from public.journey_monitoring_state as s
        join public.journeys as j on j.id = s.journey_id
        where j.status = 'ACTIVE'
          and s.phase = 'EVIDENCE_FRESH'
          and s.last_cloud_contact_at <= v_now - make_interval(secs => v_threshold_seconds)
        for update of s
    ), changed as (
        update public.journey_monitoring_state as s
        set phase = 'VERIFYING', verifying_started_at = v_now, updated_at = v_now
        from due
        where s.journey_id = due.journey_id
          and s.phase = 'EVIDENCE_FRESH'
        returning s.journey_id, due.previous_phase, due.last_cloud_contact_at
    )
    insert into public.journey_monitoring_events (
        journey_id, event_type, occurred_at, previous_phase, new_phase,
        last_cloud_contact_at, reason_code, threshold_seconds
    )
    select journey_id, 'VERIFYING_STARTED', v_now, previous_phase, 'VERIFYING',
           last_cloud_contact_at, 'HEARTBEAT_TIMEOUT', v_threshold_seconds
    from changed;
    get diagnostics v_verifying_started = row_count;

    with due as (
        select s.journey_id, s.phase as previous_phase, s.last_cloud_contact_at
        from public.journey_monitoring_state as s
        join public.journeys as j on j.id = s.journey_id
        where j.status = 'COMPLETED' and s.phase <> 'CLOSED'
        for update of s
    ), changed as (
        update public.journey_monitoring_state as s
        set phase = 'CLOSED', verifying_started_at = null, closed_at = v_now, updated_at = v_now
        from due
        where s.journey_id = due.journey_id and s.phase <> 'CLOSED'
        returning s.journey_id, due.previous_phase, due.last_cloud_contact_at
    )
    insert into public.journey_monitoring_events (
        journey_id, event_type, occurred_at, previous_phase, new_phase,
        last_cloud_contact_at, reason_code, threshold_seconds
    )
    select journey_id, 'MONITORING_CLOSED', v_now, previous_phase, 'CLOSED',
           last_cloud_contact_at, 'JOURNEY_COMPLETED', null
    from changed;
    get diagnostics v_monitoring_closed = row_count;

    return query select v_now, v_verifying_started, v_monitoring_closed;
end;
$$;

revoke all on function public.record_journey_heartbeat(uuid, bigint, timestamptz, integer, boolean, text, bigint)
from public, anon, authenticated, service_role;
grant execute on function public.record_journey_heartbeat(uuid, bigint, timestamptz, integer, boolean, text, bigint)
to authenticated;

revoke all on function public.evaluate_due_journeys() from public, anon, authenticated, service_role;
grant execute on function public.evaluate_due_journeys() to service_role;

revoke all on function public.close_journey_monitoring_on_completion()
from public, anon, authenticated, service_role;

commit;

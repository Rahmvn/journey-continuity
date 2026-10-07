begin;

-- A shared timeout, preserving the watchdog's existing >=300-second stale rule.
create function private.authenticated_device_evidence_timeout_seconds()
returns integer language sql immutable set search_path = '' as $$ select 300 $$;
revoke all on function private.authenticated_device_evidence_timeout_seconds()
from public, anon, authenticated, service_role;

-- Zero is a deployment baseline, NOT a reconstructed historical episode count.
-- The transaction timestamp identifies the epoch; continuity is proved between
-- successful post-installation guard reads, never retroactively from that time.
-- Retained monitoring rows cannot be deleted/replaced by Android's role.
alter table public.journey_monitoring_state
    add column verification_guard_version bigint not null default 0
        check (verification_guard_version >= 0),
    add column verification_guard_baseline_at timestamptz not null default now();

create function private.track_verification_guard_phase()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if tg_op = 'INSERT' then
        new.verification_guard_version := case when new.phase = 'VERIFYING' then 1 else 0 end;
        new.verification_guard_baseline_at := now();
    else
        if new.verification_guard_version < old.verification_guard_version
           or new.verification_guard_baseline_at is distinct from old.verification_guard_baseline_at then
            raise exception 'Verification guard history cannot regress' using errcode = '23514';
        end if;
        if new.phase = 'VERIFYING' and old.phase is distinct from 'VERIFYING' then
            new.verification_guard_version := new.verification_guard_version + 1;
        end if;
    end if;
    return new;
end;
$$;
create trigger journey_monitoring_track_verification_guard
before insert or update on public.journey_monitoring_state
for each row execute function private.track_verification_guard_phase();
revoke all on function private.track_verification_guard_phase()
from public, anon, authenticated, service_role;

-- Covers case openings independently of phase and caller-supplied event IDs.
-- Lock BEFORE reserving the unique OPEN slot, matching the watchdog's order.
-- Increment AFTER an actual row change, so ON CONFLICT DO NOTHING is harmless.
-- A case without monitoring state cannot have had earlier CLEAR clearance;
-- the guard fails closed until monitoring exists and the case is resolved.
create function private.lock_verification_guard_case()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if new.status = 'OPEN' and (tg_op = 'INSERT' or old.status is distinct from 'OPEN') then
        perform 1 from public.journey_monitoring_state
        where journey_id = new.journey_id for update;
    end if;
    return new;
end;
$$;
create trigger verification_cases_lock_guard
before insert or update of status on public.verification_cases
for each row execute function private.lock_verification_guard_case();
revoke all on function private.lock_verification_guard_case()
from public, anon, authenticated, service_role;

create function private.track_verification_guard_case()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if new.status = 'OPEN' and (tg_op = 'INSERT' or old.status is distinct from 'OPEN') then
        update public.journey_monitoring_state
        set verification_guard_version = verification_guard_version + 1
        where journey_id = new.journey_id;
    end if;
    return new;
end;
$$;
create trigger verification_cases_track_guard
after insert or update of status on public.verification_cases
for each row execute function private.track_verification_guard_case();
revoke all on function private.track_verification_guard_case()
from public, anon, authenticated, service_role;

create index journey_monitoring_verification_history_idx
on public.journey_monitoring_events (journey_id, occurred_at)
where event_type in ('VERIFYING_STARTED', 'CONTACT_RESTORED');

create or replace function public.evaluate_due_journeys()
returns table (evaluated_at timestamptz, verifying_started integer, monitoring_closed integer)
language plpgsql security definer set search_path = '' as $$
declare
    v_now timestamptz := now(); v_threshold_seconds constant integer := private.authenticated_device_evidence_timeout_seconds();
    v_verifying_started integer := 0; v_monitoring_closed integer := 0;
    v_due record; v_event_id bigint; v_reason text; v_terminal_at timestamptz;
begin
    for v_due in
        select s.journey_id, s.owner_id, s.phase as previous_phase, s.last_cloud_contact_at
        from public.journey_monitoring_state as s
        join public.journeys as j on j.id = s.journey_id
        where j.status = 'ACTIVE' and s.phase = 'EVIDENCE_FRESH'
          and greatest(s.last_cloud_contact_at, s.last_authenticated_device_evidence_at)
              <= v_now - make_interval(secs => v_threshold_seconds)
        for update of s
    loop
        update public.journey_monitoring_state
        set phase = 'VERIFYING', verifying_started_at = v_now, updated_at = v_now
        where journey_id = v_due.journey_id and phase = 'EVIDENCE_FRESH';
        if found then
            insert into public.journey_monitoring_events (
                journey_id, event_type, occurred_at, previous_phase, new_phase,
                last_cloud_contact_at, reason_code, threshold_seconds
            ) values (v_due.journey_id, 'VERIFYING_STARTED', v_now, v_due.previous_phase, 'VERIFYING',
                      v_due.last_cloud_contact_at, 'AUTHENTICATED_DEVICE_EVIDENCE_TIMEOUT', v_threshold_seconds)
            returning id into v_event_id;
            perform public.open_verification_case(
                v_due.journey_id, v_due.owner_id, v_event_id, v_now, v_due.last_cloud_contact_at
            );
            v_verifying_started := v_verifying_started + 1;
        end if;
    end loop;

    for v_due in
        select s.journey_id, s.phase as previous_phase, s.last_cloud_contact_at,
               j.status, j.ended_at
        from public.journey_monitoring_state as s
        join public.journeys as j on j.id = s.journey_id
        where j.status in ('COMPLETED', 'CANCELLED') and s.phase <> 'CLOSED'
        for update of s
    loop
        v_reason := case v_due.status when 'CANCELLED' then 'MONITORING_STOPPED'
            else 'JOURNEY_COMPLETED' end;
        v_terminal_at := case when v_due.status = 'CANCELLED'
            then v_due.ended_at else v_now end;
        update public.journey_monitoring_state
        set phase = 'CLOSED', verifying_started_at = null,
            closed_at = v_terminal_at, updated_at = v_terminal_at
        where journey_id = v_due.journey_id and phase <> 'CLOSED';
        if found then
            insert into public.journey_monitoring_events (
                journey_id, event_type, occurred_at, previous_phase, new_phase,
                last_cloud_contact_at, reason_code, threshold_seconds
            ) values (v_due.journey_id, 'MONITORING_CLOSED', v_terminal_at, v_due.previous_phase,
                      'CLOSED', v_due.last_cloud_contact_at, v_reason, null);
            perform public.resolve_open_verification_case(v_due.journey_id, v_reason, v_terminal_at);
            if v_due.status = 'CANCELLED' then
                update public.journey_trusted_contact_access
                set sensitive_access_expires_at = v_terminal_at + public.sensitive_case_access_ttl()
                where journey_id = v_due.journey_id and revoked_at is null;
            end if;
            v_monitoring_closed := v_monitoring_closed + 1;
        end if;
    end loop;
    return query select v_now, v_verifying_started, v_monitoring_closed;
end;
$$;

-- No case/evidence details leave this owner-only projection. No mutation or lease.
create function public.get_probable_end_verification_guard_v1(p_journey_id uuid)
returns table (
    journey_id uuid, journey_active boolean, decision text, reason text,
    verification_state text, guard_version bigint, history_baseline_at timestamptz,
    evidence_not_before timestamptz, evaluated_at timestamptz, fresh_until timestamptz
)
language plpgsql security definer set search_path = '' as $$
begin
    if (select auth.uid()) is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    -- Anonymous-auth sessions also carry the authenticated SQL role. Reuse the
    -- Auth system of record (as existing Traveller identity RPCs do).
    if not exists (select 1 from auth.users u
                   where u.id = (select auth.uid()) and u.is_anonymous is false) then
        raise exception 'Non-anonymous Traveller identity required' using errcode = '42501';
    end if;
    -- Wrong-owner and absent IDs deliberately have the same error.
    if not exists (select 1 from public.journeys j
                   where j.id = p_journey_id and j.owner_id = (select auth.uid())) then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    -- All decision inputs share ONE MVCC statement snapshot. A concurrent opening
    -- commits phase/case/cursor together, visible either before or after this read.
    return query
    with evaluation as materialized (select clock_timestamp() as at),
    facts as (
        select j.id, j.status = 'ACTIVE' as active, s.journey_id as state_id,
               s.owner_id = j.owner_id as owner_matches, s.phase,
               s.verifying_started_at, s.closed_at, s.verification_guard_version as version,
               s.verification_guard_baseline_at as baseline,
               greatest(s.last_cloud_contact_at, s.last_authenticated_device_evidence_at) as evidence_at,
               s.last_cloud_contact_at as cloud_at,
               s.last_authenticated_device_evidence_at as authenticated_at,
               s.last_authenticated_device_evidence_received_at as received_at,
               s.last_authenticated_device_evidence_transport as transport,
               s.latest_authenticated_fallback_envelope_sequence as fallback_sequence,
               s.latest_authenticated_device_telemetry_sequence as device_telemetry_sequence,
               exists(select 1 from public.verification_cases c
                      where c.journey_id = j.id and c.status = 'OPEN') as open_case,
               exists(select 1 from public.verification_cases c
                      where c.journey_id = j.id and c.owner_id <> j.owner_id) as inconsistent_case,
               greatest(s.verification_guard_baseline_at,
                   (select max(greatest(c.opened_at, c.resolved_at))
                    from public.verification_cases c where c.journey_id = j.id),
                   (select max(e.occurred_at) from public.journey_monitoring_events e
                    where e.journey_id = j.id
                      and e.event_type in ('VERIFYING_STARTED', 'CONTACT_RESTORED'))) as barrier,
               evaluation.at
        from public.journeys j cross join evaluation
        join auth.users u on u.id = j.owner_id and u.is_anonymous is false
        left join public.journey_monitoring_state s on s.journey_id = j.id
        where j.id = p_journey_id and j.owner_id = (select auth.uid())
    ), assessed as (
        select f.*, case
            when not active then 'JOURNEY_NOT_ACTIVE'
            when state_id is null then 'MISSING_MONITORING_STATE'
            when not owner_matches or inconsistent_case then 'INCONSISTENT_STATE'
            when phase = 'VERIFYING' then 'VERIFYING'
            when open_case then 'OPEN_VERIFICATION_CASE'
            when phase <> 'EVIDENCE_FRESH' or verifying_started_at is not null
                 or closed_at is not null then 'INCONSISTENT_STATE'
            when authenticated_at is null or received_at is null
                 or transport not in ('CLOUD_HEARTBEAT', 'FALLBACK_SMS')
                 or authenticated_at is distinct from evidence_at
                 or (transport = 'CLOUD_HEARTBEAT' and
                     (authenticated_at is distinct from cloud_at or received_at is distinct from cloud_at))
                 or (transport = 'FALLBACK_SMS' and
                     (fallback_sequence is null or device_telemetry_sequence is null))
                 or not isfinite(authenticated_at) or not isfinite(received_at)
                 or not isfinite(evidence_at) or not isfinite(baseline) or not isfinite(barrier)
                 or evidence_at > at or received_at > at or baseline > at or barrier > at
                 then 'INCONSISTENT_STATE'
            when evidence_at <= at - make_interval(secs => private.authenticated_device_evidence_timeout_seconds())
                 then 'STALE_AUTHENTICATED_EVIDENCE'
            else 'VERIFICATION_CLEAR' end as result_reason
        from facts f
    )
    select id, active, case when result_reason = 'VERIFICATION_CLEAR' then 'CLEAR' else 'BLOCKED' end,
           result_reason,
           case when phase = 'VERIFYING' or open_case then 'UNRESOLVED'
                when result_reason = 'VERIFICATION_CLEAR' then 'CLEAR' else 'UNKNOWN' end,
           version, baseline, barrier, at,
           case when result_reason = 'VERIFICATION_CLEAR'
                then evidence_at + make_interval(secs => private.authenticated_device_evidence_timeout_seconds())
                else null end
    from assessed;
end;
$$;
revoke all on function public.get_probable_end_verification_guard_v1(uuid)
from public, anon, authenticated, service_role;
grant execute on function public.get_probable_end_verification_guard_v1(uuid) to authenticated;

commit;

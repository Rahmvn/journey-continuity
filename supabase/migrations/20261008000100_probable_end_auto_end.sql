begin;

-- This migration creates no windows for historical Journeys or presentations.
alter table public.journeys drop constraint journeys_status_check;
alter table public.journeys add constraint journeys_status_check
    check (status in ('ACTIVE', 'COMPLETED', 'CANCELLED', 'AUTO_ENDED'));
alter table public.journeys drop constraint journeys_terminal_consistent;
alter table public.journeys add constraint journeys_terminal_consistent check (
    (status = 'ACTIVE' and completed_at is null and ended_at is null)
    or (status = 'COMPLETED' and completed_at is not null and ended_at is null)
    or (status in ('CANCELLED', 'AUTO_ENDED') and completed_at is null and ended_at is not null)
);

-- Ordinary owner writes may neither manufacture AUTO_ENDED nor replace a terminal outcome.
alter policy journeys_insert_own on public.journeys
    with check (owner_id = (select auth.uid()) and status <> 'AUTO_ENDED');
alter policy journeys_update_own on public.journeys
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()) and status <> 'AUTO_ENDED');

create or replace function public.prevent_journey_terminal_rewrite()
returns trigger language plpgsql set search_path = '' as $$
begin
    if old.status in ('COMPLETED', 'CANCELLED', 'AUTO_ENDED') and
       (new.status is distinct from old.status or
        new.completed_at is distinct from old.completed_at or
        new.ended_at is distinct from old.ended_at or
        new.owner_id is distinct from old.owner_id or
        new.destination is distinct from old.destination or
        new.expected_arrival_at is distinct from old.expected_arrival_at or
        new.started_at is distinct from old.started_at) then
        raise exception 'Terminal Journey state cannot be rewritten' using errcode = 'JT001';
    end if;
    return new;
end;
$$;

create or replace function public.set_journey_updated_at()
returns trigger language plpgsql set search_path = pg_catalog as $$
begin
    new.updated_at = case when new.status in ('CANCELLED', 'AUTO_ENDED')
        then greatest(now(), new.ended_at) else now() end;
    return new;
end;
$$;

create table private.probable_end_confirmation_windows (
    presentation_episode_id uuid primary key,
    journey_id uuid not null references public.journeys(id) on delete restrict,
    owner_id uuid not null references auth.users(id) on delete restrict,
    candidate_episode_id uuid not null,
    detector_generation bigint not null check (detector_generation >= 0),
    policy_version text not null check (policy_version = 'alabarin-probable-end-v1'),
    history_baseline_at timestamptz not null,
    guard_version bigint not null check (guard_version >= 0),
    registered_at timestamptz not null,
    not_before_at timestamptz not null,
    finalized_at timestamptz,
    constraint probable_end_window_exact_duration check
        (not_before_at = registered_at + interval '30 minutes'),
    constraint probable_end_window_final_time check
        (finalized_at is null or finalized_at >= not_before_at),
    constraint probable_end_window_journey_owner unique (presentation_episode_id, journey_id, owner_id)
);
create index probable_end_window_journey_idx
    on private.probable_end_confirmation_windows (journey_id, registered_at);
revoke all on private.probable_end_confirmation_windows from public, anon, authenticated, service_role;

create function private.protect_probable_end_window()
returns trigger language plpgsql set search_path = '' as $$
begin
    if new.presentation_episode_id is distinct from old.presentation_episode_id or
       new.journey_id is distinct from old.journey_id or new.owner_id is distinct from old.owner_id or
       new.candidate_episode_id is distinct from old.candidate_episode_id or
       new.detector_generation is distinct from old.detector_generation or
       new.policy_version is distinct from old.policy_version or
       new.history_baseline_at is distinct from old.history_baseline_at or
       new.guard_version is distinct from old.guard_version or
       new.registered_at is distinct from old.registered_at or
       new.not_before_at is distinct from old.not_before_at or
       old.finalized_at is not null or new.finalized_at is null then
        raise exception 'Confirmation window is immutable' using errcode = '23514';
    end if;
    return new;
end;
$$;
create trigger probable_end_window_immutable before update
    on private.probable_end_confirmation_windows
    for each row execute function private.protect_probable_end_window();
revoke all on function private.protect_probable_end_window()
    from public, anon, authenticated, service_role;

-- Existing monitoring/case transition lock is retained. A delayed case INSERT
-- that waited behind terminalization must not open against a terminal Journey.
create or replace function private.lock_verification_guard_case()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if new.status = 'OPEN' and (tg_op = 'INSERT' or old.status is distinct from 'OPEN') then
        perform 1 from public.journey_monitoring_state
        where journey_id = new.journey_id for update;
        if not exists (select 1 from public.journeys j
                       where j.id = new.journey_id and j.status = 'ACTIVE') then
            raise exception 'Cannot open verification for a terminal Journey' using errcode = '23514';
        end if;
    end if;
    return new;
end;
$$;

-- Existing terminal trigger name is intentionally preserved. AUTO_ENDED never
-- generates a contact-restored or Traveller-completed notification.
create or replace function public.close_journey_monitoring_on_completion()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
    v_now timestamptz := now(); v_previous_phase text; v_last_contact timestamptz;
    v_reason text;
begin
    if new.status not in ('COMPLETED', 'CANCELLED', 'AUTO_ENDED') then return new; end if;
    if new.status in ('CANCELLED', 'AUTO_ENDED') then v_now := new.ended_at; end if;
    v_reason := case new.status when 'CANCELLED' then 'MONITORING_STOPPED'
        when 'AUTO_ENDED' then 'PROBABLE_END_AUTO_ENDED' else 'JOURNEY_COMPLETED' end;
    select s.phase, s.last_cloud_contact_at into v_previous_phase, v_last_contact
    from public.journey_monitoring_state s where s.journey_id = new.id for update;
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
    ) values (new.id, 'MONITORING_CLOSED', v_now, v_previous_phase, 'CLOSED',
              v_last_contact, v_reason, null);
    if new.status <> 'AUTO_ENDED' then
        perform public.resolve_open_verification_case(new.id, v_reason, v_now);
    end if;
    update public.journey_trusted_contact_access
    set sensitive_access_expires_at = v_now + public.sensitive_case_access_ttl()
    where journey_id = new.id and revoked_at is null;
    return new;
end;
$$;

create function public.register_probable_end_confirmation_window_v1(
    p_journey_id uuid, p_presentation_episode_id uuid, p_candidate_episode_id uuid,
    p_detector_generation bigint, p_policy_version text,
    p_history_baseline_at timestamptz, p_guard_version bigint
)
returns table (decision text, presentation_episode_id uuid, registered_at timestamptz,
               not_before_at timestamptz, history_baseline_at timestamptz, guard_version bigint)
language plpgsql security definer set search_path = '' as $$
declare v_owner uuid := (select auth.uid()); v_journey public.journeys%rowtype;
        v_state public.journey_monitoring_state%rowtype;
        v_window private.probable_end_confirmation_windows%rowtype;
        v_guard record; v_at timestamptz;
begin
    if v_owner is null or not exists (select 1 from auth.users u
        where u.id = v_owner and u.is_anonymous is false) then
        raise exception 'Non-anonymous Traveller identity required' using errcode = '42501';
    end if;
    -- Do not block verification's Journey FK KEY SHARE while waiting for state.
    -- NO KEY UPDATE still excludes competing status writes/finalizers, and this
    -- operation never changes the Journey's referenced key.
    select * into v_journey from public.journeys j where j.id = p_journey_id for no key update;
    if not found or v_journey.owner_id <> v_owner then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    select * into v_state from public.journey_monitoring_state s
        where s.journey_id = p_journey_id for update;
    select * into v_window from private.probable_end_confirmation_windows w
        where w.presentation_episode_id = p_presentation_episode_id for update;
    if found then
        if v_window.journey_id <> p_journey_id or v_window.owner_id <> v_owner or
           v_window.candidate_episode_id <> p_candidate_episode_id or
           v_window.detector_generation <> p_detector_generation or
           v_window.policy_version <> p_policy_version or
           v_window.history_baseline_at <> p_history_baseline_at or
           v_window.guard_version <> p_guard_version then
            raise exception 'Confirmation window identity conflict' using errcode = '23505';
        end if;
        return query select 'REGISTERED'::text, v_window.presentation_episode_id,
            v_window.registered_at, v_window.not_before_at,
            v_window.history_baseline_at, v_window.guard_version;
        return;
    end if;
    if v_journey.status <> 'ACTIVE' or p_detector_generation < 0 or
       p_policy_version <> 'alabarin-probable-end-v1' or p_guard_version < 0 or
       p_history_baseline_at is null or v_state.journey_id is null then
        return query select 'BLOCKED'::text, p_presentation_episode_id,
            null::timestamptz, null::timestamptz, null::timestamptz, null::bigint;
        return;
    end if;
    select * into v_guard from public.get_probable_end_verification_guard_v1(p_journey_id);
    if v_guard.decision <> 'CLEAR' or
       date_trunc('milliseconds', v_guard.history_baseline_at) <> p_history_baseline_at or
       v_guard.guard_version <> p_guard_version then
        return query select 'BLOCKED'::text, p_presentation_episode_id,
            null::timestamptz, null::timestamptz, null::timestamptz, null::bigint;
        return;
    end if;
    v_at := date_trunc('milliseconds', clock_timestamp());
    if clock_timestamp() >= v_guard.fresh_until then
        return query select 'BLOCKED'::text, p_presentation_episode_id,
            null::timestamptz, null::timestamptz, null::timestamptz, null::bigint;
        return;
    end if;
    insert into private.probable_end_confirmation_windows (
        presentation_episode_id, journey_id, owner_id, candidate_episode_id,
        detector_generation, policy_version, history_baseline_at, guard_version,
        registered_at, not_before_at
    ) values (p_presentation_episode_id, p_journey_id, v_owner, p_candidate_episode_id,
              p_detector_generation, p_policy_version, p_history_baseline_at, p_guard_version,
              v_at, v_at + interval '30 minutes') returning * into v_window;
    return query select 'REGISTERED'::text, v_window.presentation_episode_id,
        v_window.registered_at, v_window.not_before_at,
        v_window.history_baseline_at, v_window.guard_version;
end;
$$;
revoke all on function public.register_probable_end_confirmation_window_v1(
    uuid, uuid, uuid, bigint, text, timestamptz, bigint) from public, anon, authenticated, service_role;
grant execute on function public.register_probable_end_confirmation_window_v1(
    uuid, uuid, uuid, bigint, text, timestamptz, bigint) to authenticated;

create function public.finalize_probable_end_auto_end_v1(
    p_journey_id uuid, p_presentation_episode_id uuid
)
returns table (decision text, presentation_episode_id uuid, journey_status text,
               ended_at timestamptz, not_before_at timestamptz)
language plpgsql security definer set search_path = '' as $$
declare v_owner uuid := (select auth.uid()); v_journey public.journeys%rowtype;
        v_state public.journey_monitoring_state%rowtype;
        v_window private.probable_end_confirmation_windows%rowtype;
        v_guard record; v_at timestamptz;
begin
    if v_owner is null or not exists (select 1 from auth.users u
        where u.id = v_owner and u.is_anonymous is false) then
        raise exception 'Non-anonymous Traveller identity required' using errcode = '42501';
    end if;
    -- Compatible with a state-first verification INSERT's Journey FK KEY SHARE.
    -- Status writers and other finalizers still serialize on this Journey row.
    select * into v_journey from public.journeys j where j.id = p_journey_id for no key update;
    if not found or v_journey.owner_id <> v_owner then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    select * into v_state from public.journey_monitoring_state s
        where s.journey_id = p_journey_id for update;
    select * into v_window from private.probable_end_confirmation_windows w
        where w.presentation_episode_id = p_presentation_episode_id for update;
    if not found or v_window.journey_id <> p_journey_id or v_window.owner_id <> v_owner then
        raise exception 'Confirmation window is not available to this caller' using errcode = '42501';
    end if;
    if v_window.finalized_at is not null then
        if v_journey.status <> 'AUTO_ENDED' or v_journey.ended_at <> v_window.finalized_at then
            raise exception 'Inconsistent automatic-end provenance' using errcode = '23514';
        end if;
        return query select 'AUTO_ENDED'::text, v_window.presentation_episode_id,
            v_journey.status, v_window.finalized_at, v_window.not_before_at;
        return;
    end if;
    if v_journey.status <> 'ACTIVE' or v_state.journey_id is null then
        return query select 'BLOCKED'::text, v_window.presentation_episode_id,
            v_journey.status, v_journey.ended_at, v_window.not_before_at;
        return;
    end if;
    v_at := date_trunc('milliseconds', clock_timestamp());
    if v_at < v_window.not_before_at then
        return query select 'TOO_EARLY'::text, v_window.presentation_episode_id,
            v_journey.status, null::timestamptz, v_window.not_before_at;
        return;
    end if;
    select * into v_guard from public.get_probable_end_verification_guard_v1(p_journey_id);
    if v_guard.decision <> 'CLEAR' or
       date_trunc('milliseconds', v_guard.history_baseline_at) <> v_window.history_baseline_at or
       v_guard.guard_version <> v_window.guard_version or
       clock_timestamp() >= v_guard.fresh_until or
       v_window.policy_version <> 'alabarin-probable-end-v1' then
        return query select 'BLOCKED'::text, v_window.presentation_episode_id,
            v_journey.status, null::timestamptz, v_window.not_before_at;
        return;
    end if;
    update public.journeys j set status = 'AUTO_ENDED', ended_at = v_at
        where j.id = p_journey_id and j.status = 'ACTIVE'
        returning * into v_journey;
    if not found then raise exception 'Journey terminal race' using errcode = '40001'; end if;
    update private.probable_end_confirmation_windows w set finalized_at = v_at
        where w.presentation_episode_id = p_presentation_episode_id;
    return query select 'AUTO_ENDED'::text, v_window.presentation_episode_id,
        v_journey.status, v_at, v_window.not_before_at;
end;
$$;
revoke all on function public.finalize_probable_end_auto_end_v1(uuid, uuid)
    from public, anon, authenticated, service_role;
grant execute on function public.finalize_probable_end_auto_end_v1(uuid, uuid) to authenticated;

commit;

begin;

-- No existing Journey, case, grant, or evidence row is rewritten by this DDL.
alter table public.journeys drop constraint journeys_status_check;
alter table public.journeys add constraint journeys_status_check check
    (status in ('ACTIVE', 'COMPLETED', 'CANCELLED', 'AUTO_ENDED', 'UNRESOLVED_INCIDENT'));
alter table public.journeys drop constraint journeys_terminal_consistent;
alter table public.journeys add constraint journeys_terminal_consistent check (
    (status = 'ACTIVE' and completed_at is null and ended_at is null)
    or (status = 'COMPLETED' and completed_at is not null and ended_at is null)
    or (status in ('CANCELLED', 'AUTO_ENDED', 'UNRESOLVED_INCIDENT')
        and completed_at is null and ended_at is not null)
);
alter policy journeys_insert_own on public.journeys with check
    (owner_id = (select auth.uid()) and status not in ('AUTO_ENDED', 'UNRESOLVED_INCIDENT'));
alter policy journeys_update_own on public.journeys
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()) and status not in ('AUTO_ENDED', 'UNRESOLVED_INCIDENT'));

create or replace function public.prevent_journey_terminal_rewrite()
returns trigger language plpgsql set search_path = '' as $$
begin
    if old.status in ('COMPLETED', 'CANCELLED', 'AUTO_ENDED', 'UNRESOLVED_INCIDENT') and
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
    new.updated_at = case when new.status in ('CANCELLED', 'AUTO_ENDED', 'UNRESOLVED_INCIDENT')
        then greatest(now(), new.ended_at) else now() end;
    return new;
end;
$$;

create table private.unresolved_incident_finalizations (
    journey_id uuid primary key references public.journeys(id) on delete restrict,
    owner_id uuid not null references auth.users(id) on delete restrict,
    admission_id uuid not null references private.unresolved_boundary_admissions(admission_id) on delete restrict,
    policy_version text not null check (policy_version = 'alabarin-unresolved-boundary-v1'),
    original_eta timestamptz not null,
    boundary_at timestamptz not null,
    verification_case_id uuid not null unique references public.verification_cases(id) on delete restrict,
    opening_event_id bigint not null references public.journey_monitoring_events(id) on delete restrict,
    verifying_started_at timestamptz not null,
    guard_version bigint not null check (guard_version >= 0),
    history_baseline_at timestamptz not null,
    finalized_at timestamptz not null,
    access_expires_at timestamptz not null,
    source text not null check (source = 'AUTHORITATIVE_UNRESOLVED_FINALIZER_V1'),
    constraint unresolved_incident_exact_access_window check
        (access_expires_at = finalized_at + interval '168 hours'),
    constraint unresolved_incident_after_boundary check (finalized_at >= boundary_at)
);
revoke all on private.unresolved_incident_finalizations from public, anon, authenticated, service_role;

create function private.reject_unresolved_incident_rewrite()
returns trigger language plpgsql set search_path = '' as $$
begin
    raise exception 'Unresolved incident finalization is immutable' using errcode = '23514';
end;
$$;
create trigger unresolved_incident_finalization_immutable
before update or delete on private.unresolved_incident_finalizations
for each row execute function private.reject_unresolved_incident_rewrite();
revoke all on function private.reject_unresolved_incident_rewrite()
from public, anon, authenticated, service_role;

-- The existing terminal trigger name stays stable. The unresolved OPEN case is
-- retained, not resolved as contact restored, completed, or stopped by Traveller.
create or replace function public.close_journey_monitoring_on_completion()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
    v_now timestamptz := now(); v_previous_phase text; v_last_contact timestamptz;
    v_reason text;
begin
    if new.status not in ('COMPLETED', 'CANCELLED', 'AUTO_ENDED', 'UNRESOLVED_INCIDENT') then return new; end if;
    if new.status in ('CANCELLED', 'AUTO_ENDED', 'UNRESOLVED_INCIDENT') then v_now := new.ended_at; end if;
    v_reason := case new.status when 'CANCELLED' then 'MONITORING_STOPPED'
        when 'AUTO_ENDED' then 'PROBABLE_END_AUTO_ENDED'
        when 'UNRESOLVED_INCIDENT' then 'UNRESOLVED_BOUNDARY_MONITORING_ENDED'
        else 'JOURNEY_COMPLETED' end;
    select s.phase, s.last_cloud_contact_at into v_previous_phase, v_last_contact
    from public.journey_monitoring_state s where s.journey_id = new.id for update;
    if not found then
        insert into public.journey_monitoring_state (
            journey_id, owner_id, phase, latest_heartbeat_sequence, closed_at, updated_at
        ) values (new.id, new.owner_id, 'CLOSED', 0, v_now, v_now);
        v_previous_phase := null;
    elsif v_previous_phase = 'CLOSED' then
        if new.status <> 'UNRESOLVED_INCIDENT' then return new; end if;
    else
        update public.journey_monitoring_state
        set phase = 'CLOSED', verifying_started_at = null, closed_at = v_now, updated_at = v_now
        where journey_id = new.id;
    end if;
    if v_previous_phase is distinct from 'CLOSED' then
        insert into public.journey_monitoring_events (
            journey_id, event_type, occurred_at, previous_phase, new_phase,
            last_cloud_contact_at, reason_code, threshold_seconds
        ) values (new.id, 'MONITORING_CLOSED', v_now, v_previous_phase, 'CLOSED',
                  v_last_contact, v_reason, null);
    end if;
    if new.status not in ('AUTO_ENDED', 'UNRESOLVED_INCIDENT') then
        perform public.resolve_open_verification_case(new.id, v_reason, v_now);
    end if;
    if new.status = 'UNRESOLVED_INCIDENT' then
        update public.journey_trusted_contact_access a
        set sensitive_access_expires_at = case when a.sensitive_access_expires_at is null
            then v_now + interval '168 hours'
            else least(a.sensitive_access_expires_at, v_now + interval '168 hours') end
        where a.journey_id = new.id and a.revoked_at is null;
    else
        update public.journey_trusted_contact_access
        set sensitive_access_expires_at = v_now + public.sensitive_case_access_ttl()
        where journey_id = new.id and revoked_at is null;
    end if;
    return new;
end;
$$;

-- Exact server-atomic owner operation. Journey NO KEY UPDATE, then monitoring,
-- then case matches the accepted Section 09/state-first verification order.
create function private.unresolved_incident_boundary_reached(
    p_boundary_at timestamptz, p_at timestamptz)
returns boolean language sql immutable strict set search_path = '' as $$
    select p_at >= p_boundary_at;
$$;
revoke all on function private.unresolved_incident_boundary_reached(timestamptz,timestamptz)
from public, anon, authenticated, service_role;

create function public.finalize_unresolved_incident_v1(
    p_journey_id uuid, p_admission_id uuid, p_boundary_at timestamptz,
    p_policy_version text, p_verification_case_id uuid, p_opening_event_id bigint,
    p_verifying_started_at timestamptz, p_guard_version bigint,
    p_history_baseline_at timestamptz
)
returns table (decision text, journey_status text, ended_at timestamptz,
    boundary_at timestamptz, verification_case_id uuid, access_expires_at timestamptz)
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := (select auth.uid()); v_journey public.journeys%rowtype;
    v_state public.journey_monitoring_state%rowtype;
    v_case public.verification_cases%rowtype;
    v_admission private.unresolved_boundary_admissions%rowtype;
    v_final private.unresolved_incident_finalizations%rowtype;
    v_eligibility record; v_at timestamptz;
begin
    if v_owner is null or not exists (select 1 from auth.users u
        where u.id = v_owner and u.is_anonymous is false) then
        raise exception 'Non-anonymous Traveller identity required' using errcode = '42501';
    end if;
    select * into v_journey from public.journeys j
        where j.id = p_journey_id for no key update;
    if not found or v_journey.owner_id <> v_owner then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    select * into v_final from private.unresolved_incident_finalizations f
        where f.journey_id = p_journey_id;
    if found then
        if v_final.owner_id <> v_owner or v_final.admission_id is distinct from p_admission_id or
           v_final.boundary_at is distinct from p_boundary_at or
           v_final.policy_version is distinct from p_policy_version or
           v_final.verification_case_id is distinct from p_verification_case_id or
           v_final.opening_event_id is distinct from p_opening_event_id or
           v_final.verifying_started_at is distinct from p_verifying_started_at or
           v_final.guard_version is distinct from p_guard_version or
           v_final.history_baseline_at is distinct from p_history_baseline_at then
            raise exception 'Unresolved finalization identity conflict' using errcode = '23505';
        end if;
        if v_journey.status <> 'UNRESOLVED_INCIDENT' or v_journey.ended_at <> v_final.finalized_at then
            raise exception 'Inconsistent unresolved finalization provenance' using errcode = '23514';
        end if;
        return query select 'UNRESOLVED_INCIDENT'::text, v_journey.status,
            v_final.finalized_at, v_final.boundary_at, v_final.verification_case_id,
            v_final.access_expires_at;
        return;
    end if;
    if v_journey.status <> 'ACTIVE' then
        return query select 'BLOCKED'::text, v_journey.status, v_journey.ended_at,
            null::timestamptz, null::uuid, null::timestamptz;
        return;
    end if;
    select * into v_state from public.journey_monitoring_state s
        where s.journey_id = p_journey_id for update;
    select * into v_admission from private.unresolved_boundary_admissions a
        where a.journey_id = p_journey_id;
    if v_admission.journey_id is null or v_admission.owner_id <> v_owner or
       v_admission.admission_id is distinct from p_admission_id or
       v_admission.boundary_at is distinct from p_boundary_at or
       v_admission.policy_version is distinct from p_policy_version or
       v_admission.original_eta <> v_journey.expected_arrival_at or
       v_admission.admitted_at <> v_journey.started_at or
       v_journey.unresolved_admission_id is distinct from p_admission_id or
       v_journey.unresolved_policy_version is distinct from p_policy_version then
        return query select 'BLOCKED'::text, v_journey.status, null::timestamptz,
            null::timestamptz, null::uuid, null::timestamptz;
        return;
    end if;
    v_at := date_trunc('milliseconds', clock_timestamp());
    if private.unresolved_incident_boundary_reached(v_admission.boundary_at, v_at) is not true then
        return query select 'TOO_EARLY'::text, v_journey.status, null::timestamptz,
            v_admission.boundary_at, null::uuid, null::timestamptz;
        return;
    end if;
    select * into v_case from public.verification_cases c
        where c.id = p_verification_case_id and c.journey_id = p_journey_id
        for update;
    if v_state.journey_id is null or v_case.id is null or v_case.owner_id <> v_owner then
        return query select 'BLOCKED'::text, v_journey.status, null::timestamptz,
            v_admission.boundary_at, null::uuid, null::timestamptz;
        return;
    end if;
    -- The terminal timestamp is sampled after lock waits, at the authoritative
    -- read/mutation point rather than before a competing case writer completes.
    v_at := date_trunc('milliseconds', clock_timestamp());
    -- Read again after all locks. The 10A observation is not a future lease.
    select * into v_eligibility from private.read_unresolved_boundary(
        p_journey_id, v_owner, v_at);
    if v_eligibility.decision is distinct from 'ELIGIBLE' or
       v_eligibility.verification_case_id is distinct from p_verification_case_id or
       v_eligibility.opening_event_id is distinct from p_opening_event_id or
       v_eligibility.verifying_started_at is distinct from p_verifying_started_at or
       v_eligibility.guard_version is distinct from p_guard_version or
       v_eligibility.history_baseline_at is distinct from p_history_baseline_at or
       v_eligibility.admission_id is distinct from p_admission_id or
       v_eligibility.boundary_at is distinct from p_boundary_at or
       v_eligibility.policy_version is distinct from p_policy_version then
        return query select 'BLOCKED'::text, v_journey.status, null::timestamptz,
            v_admission.boundary_at, null::uuid, null::timestamptz;
        return;
    end if;
    update public.journeys j set status = 'UNRESOLVED_INCIDENT', ended_at = v_at
        where j.id = p_journey_id and j.status = 'ACTIVE'
        returning * into v_journey;
    if not found then raise exception 'Journey terminal race' using errcode = '40001'; end if;
    insert into private.unresolved_incident_finalizations (
        journey_id, owner_id, admission_id, policy_version, original_eta,
        boundary_at, verification_case_id, opening_event_id, verifying_started_at,
        guard_version, history_baseline_at, finalized_at, access_expires_at, source
    ) values (
        p_journey_id, v_owner, p_admission_id, p_policy_version, v_admission.original_eta,
        p_boundary_at, p_verification_case_id, p_opening_event_id, p_verifying_started_at,
        p_guard_version, p_history_baseline_at, v_at, v_at + interval '168 hours',
        'AUTHORITATIVE_UNRESOLVED_FINALIZER_V1'
    ) returning * into v_final;
    return query select 'UNRESOLVED_INCIDENT'::text, v_journey.status,
        v_final.finalized_at, v_final.boundary_at, v_final.verification_case_id,
        v_final.access_expires_at;
end;
$$;
revoke all on function public.finalize_unresolved_incident_v1(
    uuid,uuid,timestamptz,text,uuid,bigint,timestamptz,bigint,timestamptz)
from public, anon, authenticated, service_role;
grant execute on function public.finalize_unresolved_incident_v1(
    uuid,uuid,timestamptz,text,uuid,bigint,timestamptz,bigint,timestamptz) to authenticated;

-- This is separate from ordinary Journey-summary authorization and the older
-- resolved-case TTL. An OPEN case cannot bypass incident expiry or revocation.
create function private.trusted_incident_detail_allowed_at(
    p_contact_id uuid, p_journey_id uuid, p_at timestamptz)
returns boolean language sql security definer set search_path = '' as $$
    select exists (
        select 1 from private.unresolved_incident_finalizations f
        join public.journeys j on j.id = f.journey_id and j.owner_id = f.owner_id
        join public.journey_trusted_contact_access a
          on a.journey_id = j.id and a.traveller_user_id = j.owner_id
         and a.contact_user_id = p_contact_id and a.revoked_at is null
        join public.trusted_contact_relationships r
          on r.id = a.relationship_id and r.traveller_user_id = j.owner_id
         and r.contact_user_id = p_contact_id and r.status = 'ACCEPTED'
         and r.revoked_at is null
        join auth.users u on u.id = p_contact_id and u.is_anonymous is false
          and u.email_confirmed_at is not null
        where f.journey_id = p_journey_id and p_contact_id = (select auth.uid())
          and j.status = 'UNRESOLVED_INCIDENT' and j.ended_at = f.finalized_at
          and f.access_expires_at > p_at
          and (a.sensitive_access_expires_at is null
               or a.sensitive_access_expires_at > p_at)
    );
$$;
revoke all on function private.trusted_incident_detail_allowed_at(uuid,uuid,timestamptz)
from public, anon, authenticated, service_role;
create function private.trusted_incident_detail_allowed(p_contact_id uuid, p_journey_id uuid)
returns boolean language sql security definer set search_path = '' as $$
    select private.trusted_incident_detail_allowed_at(
        p_contact_id, p_journey_id, clock_timestamp());
$$;
revoke all on function private.trusted_incident_detail_allowed(uuid,uuid)
from public, anon, authenticated, service_role;

-- Factual summary remains available to an accepted, unrevoked verified contact.
-- Neither case identity nor report/location detail is present in this list.
create or replace function public.list_trusted_journeys()
returns table (journey_id uuid, destination text, started_at timestamptz,
    expected_arrival_at timestamptz, journey_status text, monitoring_phase text,
    last_cloud_contact_at timestamptz)
language plpgsql security definer set search_path = '' as $$
declare v_contact_id uuid := (select auth.uid());
begin
    if v_contact_id is null or not exists (select 1 from auth.users u
        where u.id = v_contact_id and u.is_anonymous is false
          and u.email_confirmed_at is not null) then
        raise exception 'Verified trusted identity required' using errcode = '42501';
    end if;
    return query
    select j.id, j.destination, j.started_at, j.expected_arrival_at, j.status,
           coalesce(s.phase, 'CLOSED'), s.last_cloud_contact_at
    from public.journey_trusted_contact_access a
    join public.trusted_contact_relationships r
      on r.id = a.relationship_id and r.traveller_user_id = a.traveller_user_id
     and r.contact_user_id = a.contact_user_id
    join public.journeys j on j.id = a.journey_id and j.owner_id = a.traveller_user_id
    left join public.journey_monitoring_state s on s.journey_id = j.id
    where a.contact_user_id = v_contact_id and a.revoked_at is null
      and r.status = 'ACCEPTED' and r.revoked_at is null
    order by j.started_at desc;
end;
$$;

-- Both v1/v2 status getters derive from this projection; keep its shape and
-- existing nonsensitive fields stable while closing anonymous/revoked access.
create or replace function public.list_trusted_journey_statuses_v1()
returns table (journey_id uuid, traveller_full_name text, traveller_handle text,
    destination text, started_at timestamptz, expected_arrival_at timestamptz,
    journey_status text, monitoring_phase text, completed_at timestamptz,
    last_cloud_contact_at timestamptz, last_authenticated_device_evidence_at timestamptz,
    last_authenticated_device_evidence_received_at timestamptz,
    last_authenticated_device_evidence_transport text)
language plpgsql stable security definer set search_path = '' as $$
declare v_contact_id uuid := (select auth.uid());
begin
    if v_contact_id is null or not exists (select 1 from auth.users u
        where u.id = v_contact_id and u.is_anonymous is false
          and u.email_confirmed_at is not null) then
        raise exception 'Verified trusted identity required' using errcode = '42501';
    end if;
    return query
    select j.id, p.full_name, p.handle, j.destination, j.started_at, j.expected_arrival_at,
           j.status, s.phase, j.completed_at, s.last_cloud_contact_at,
           s.last_authenticated_device_evidence_at,
           s.last_authenticated_device_evidence_received_at,
           s.last_authenticated_device_evidence_transport
    from public.journey_trusted_contact_access a
    join public.trusted_contact_relationships r
      on r.id = a.relationship_id and r.traveller_user_id = a.traveller_user_id
     and r.contact_user_id = a.contact_user_id
    join public.journeys j on j.id = a.journey_id and j.owner_id = a.traveller_user_id
    left join public.traveller_profiles p on p.owner_id = j.owner_id
    left join public.journey_monitoring_state s on s.journey_id = j.id
    where a.contact_user_id = v_contact_id and a.revoked_at is null
      and r.status = 'ACCEPTED' and r.revoked_at is null
    order by j.started_at desc, j.id;
end;
$$;

create or replace function public.get_trusted_journey_snapshot(p_journey_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_contact_id uuid := (select auth.uid()); v_result jsonb;
    v_status text; v_detail_allowed boolean := false;
begin
    if v_contact_id is null or not public.trusted_contact_has_journey_access(v_contact_id, p_journey_id)
       or not exists (select 1 from auth.users u where u.id = v_contact_id
           and u.is_anonymous is false and u.email_confirmed_at is not null) then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    select j.status into v_status from public.journeys j where j.id = p_journey_id;
    if v_status = 'UNRESOLVED_INCIDENT' then
        v_detail_allowed := private.trusted_incident_detail_allowed(v_contact_id, p_journey_id);
    end if;
    select jsonb_build_object(
        'journey_id', j.id, 'destination', j.destination, 'started_at', j.started_at,
        'expected_arrival_at', j.expected_arrival_at, 'journey_status', j.status,
        'ended_at', j.ended_at, 'monitoring_phase', coalesce(s.phase, 'CLOSED'),
        'last_cloud_contact_at', s.last_cloud_contact_at,
        'whereabouts', case when j.status = 'UNRESOLVED_INCIDENT' or s.phase = 'VERIFYING'
            then 'UNKNOWN' else 'NOT_DISCLOSED' end,
        'open_case_id', case when (j.status <> 'UNRESOLVED_INCIDENT' or v_detail_allowed)
            and s.phase = 'VERIFYING' and c.status = 'OPEN' then c.id else null end,
        'available_case_id', case when j.status <> 'UNRESOLVED_INCIDENT' or v_detail_allowed
            then c.id else null end
    ) into v_result
    from public.journeys j
    left join public.journey_monitoring_state s on s.journey_id = j.id
    left join lateral (
        select vc.id, vc.status from public.verification_cases vc
        where vc.journey_id = j.id
          and (vc.status = 'OPEN' or vc.sensitive_access_expires_at > now())
        order by vc.opened_at desc limit 1
    ) c on true
    where j.id = p_journey_id;
    insert into public.trusted_contact_access_events(event_type,contact_user_id,journey_id)
    values ('JOURNEY_VIEWED',v_contact_id,p_journey_id) on conflict do nothing;
    return v_result;
end;
$$;

create or replace function public.get_verification_case(p_case_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_contact_id uuid := (select auth.uid());
    v_case public.verification_cases%rowtype; v_journey_status text; v_result jsonb;
begin
    if v_contact_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into v_case from public.verification_cases where id = p_case_id;
    if not found or not public.trusted_contact_has_journey_access(v_contact_id,v_case.journey_id) then
        raise exception 'Verification case is not available to this caller' using errcode = '42501';
    end if;
    select j.status into v_journey_status from public.journeys j where j.id = v_case.journey_id;
    if v_journey_status = 'UNRESOLVED_INCIDENT' and
       not private.trusted_incident_detail_allowed(v_contact_id,v_case.journey_id) then
        raise exception 'Incident detail access has expired or been revoked' using errcode = '42501';
    end if;
    if v_case.status = 'RESOLVED' and v_case.sensitive_access_expires_at <= clock_timestamp() then
        raise exception 'Sensitive verification-case access has expired' using errcode = '42501';
    end if;
    select jsonb_build_object(
        'id', v_case.id, 'journey_id', v_case.journey_id, 'status', v_case.status,
        'opened_at', v_case.opened_at, 'resolution_reason', v_case.resolution_reason,
        'resolved_at', v_case.resolved_at,
        'sensitive_access_expires_at', v_case.sensitive_access_expires_at,
        'last_cloud_contact_at', v_case.last_cloud_contact_at,
        'latest_known_telemetry_sequence', v_case.latest_known_telemetry_sequence,
        'last_verified_device_location', case when v_case.latitude is null then null
            else jsonb_build_object('event_time',v_case.last_location_event_time,
                'latitude',v_case.latitude,'longitude',v_case.longitude,
                'accuracy_meters',v_case.accuracy_meters,'battery_percent',v_case.battery_percent,
                'charging',v_case.charging,'connectivity_state',v_case.connectivity_state,
                'provenance','device_verified') end,
        'journey', jsonb_build_object('destination',v_case.journey_destination,
            'started_at',v_case.journey_started_at,
            'expected_arrival_at',v_case.journey_expected_arrival_at),
        'recent_location_snapshot',coalesce((
            select jsonb_agg(jsonb_build_object('position',p.position,
                'telemetry_sequence',p.telemetry_sequence,'event_time',p.event_time,
                'latitude',p.latitude,'longitude',p.longitude,'accuracy_meters',p.accuracy_meters,
                'battery_percent',p.battery_percent,'connectivity_state',p.connectivity_state,
                'provenance','device_verified') order by p.position)
            from public.verification_case_location_points p where p.case_id=v_case.id
        ),'[]'::jsonb),
        'verification_history',coalesce((
            select jsonb_agg(jsonb_build_object('event_type',e.event_type,
                'occurred_at',e.occurred_at,'reason_code',e.reason_code,
                'provenance','system_derived') order by e.occurred_at)
            from public.journey_monitoring_events e
            where e.journey_id=v_case.journey_id and e.occurred_at>=v_case.opened_at
        ),'[]'::jsonb),
        'trusted_contact_reports',coalesce((
            select jsonb_agg(jsonb_build_object('id',r.id,'report_type',r.report_type,
                'contact_time',r.contact_time,'note',r.note,'created_at',r.created_at,
                'provenance',r.provenance) order by r.created_at)
            from public.verification_case_reports r where r.case_id=v_case.id
        ),'[]'::jsonb)
    ) into v_result;
    insert into public.trusted_contact_access_events(event_type,contact_user_id,journey_id,case_id)
    values('VERIFICATION_CASE_VIEWED',v_contact_id,v_case.journey_id,v_case.id)
    on conflict do nothing;
    if v_case.latitude is not null then
        insert into public.trusted_contact_access_events(event_type,contact_user_id,journey_id,case_id)
        values('PRECISE_LOCATION_REVEALED',v_contact_id,v_case.journey_id,v_case.id)
        on conflict do nothing;
    end if;
    return v_result;
end;
$$;

-- Lock Journey before a report is accepted. A report that wins first remains
-- preserved history; a report arriving after incident finalization is denied.
create or replace function public.submit_verification_case_report(
    p_case_id uuid,p_report_type text,p_contact_time timestamptz,p_note text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_contact_id uuid := (select auth.uid()); v_journey_id uuid; v_journey_status text;
    v_case_status text; v_sensitive_access_expires_at timestamptz;
    v_report_id uuid; v_note text := btrim(p_note);
begin
    select c.journey_id into v_journey_id from public.verification_cases c where c.id=p_case_id;
    if v_contact_id is null or v_journey_id is null or
       not public.trusted_contact_has_journey_access(v_contact_id,v_journey_id) then
        raise exception 'Verification case is not available to this caller' using errcode='42501';
    end if;
    select j.status into v_journey_status from public.journeys j
        where j.id=v_journey_id for no key update;
    if v_journey_status = 'UNRESOLVED_INCIDENT' then
        raise exception 'Reporting is closed for this incident' using errcode='42501';
    end if;
    -- Recheck after the Journey lock, including revocation and case resolution.
    select c.status,c.sensitive_access_expires_at into v_case_status,v_sensitive_access_expires_at
    from public.verification_cases c where c.id=p_case_id and c.journey_id=v_journey_id;
    if not public.trusted_contact_has_journey_access(v_contact_id,v_journey_id) then
        raise exception 'Verification case is not available to this caller' using errcode='42501';
    end if;
    if v_case_status='RESOLVED' and v_sensitive_access_expires_at <= clock_timestamp() then
        raise exception 'Sensitive verification-case access has expired' using errcode='42501';
    end if;
    if p_report_type not in ('SPOKE_WITH_TRAVELLER','RECEIVED_MESSAGE_FROM_TRAVELLER',
        'UPDATE_FROM_OTHER_PERSON','OTHER') then
        raise exception 'Unsupported report type' using errcode='22023';
    end if;
    if length(v_note) not between 1 and 1000 then
        raise exception 'Report note must be between 1 and 1000 characters' using errcode='22023';
    end if;
    insert into public.verification_case_reports(case_id,reporter_user_id,report_type,
        contact_time,note) values(p_case_id,v_contact_id,p_report_type,p_contact_time,v_note)
        returning id into v_report_id;
    insert into public.trusted_contact_access_events(event_type,contact_user_id,journey_id,case_id,report_id)
    values('REPORT_SUBMITTED',v_contact_id,v_journey_id,p_case_id,v_report_id);
    return v_report_id;
end;
$$;

commit;

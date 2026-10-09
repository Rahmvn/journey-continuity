begin;

-- Owner-only reconciliation of a previously committed incident. This is a
-- statement-snapshot read of the immutable 10B record, not a new finalizer or
-- an incident-detail grant to a trusted contact.
create function public.get_owner_unresolved_incident_finalization_v1(p_journey_id uuid)
returns table (
    decision text, journey_status text, ended_at timestamptz,
    boundary_at timestamptz, verification_case_id uuid, access_expires_at timestamptz,
    journey_id uuid, owner_id uuid, admission_id uuid, policy_version text,
    original_eta timestamptz, opening_event_id bigint,
    verifying_started_at timestamptz, guard_version bigint,
    history_baseline_at timestamptz, finalized_at timestamptz, source text
)
language plpgsql stable security definer set search_path = '' as $$
declare
    v_owner uuid := (select auth.uid());
    v_facts record;
begin
    if v_owner is null or not exists (
        select 1 from auth.users u where u.id = v_owner and u.is_anonymous is false
    ) then
        raise exception 'Non-anonymous Traveller identity required' using errcode = '42501';
    end if;

    -- The outer Journey row exists only for the exact owner. The left joins
    -- distinguish an inconsistent terminal row from an ordinary non-incident
    -- Journey without exposing either fact to another owner.
    select j.id as journey_id, j.owner_id, j.status as journey_status,
           j.ended_at as journey_ended_at, j.started_at as journey_started_at,
           j.expected_arrival_at as journey_eta,
           j.unresolved_admission_id, j.unresolved_policy_version,
           f.admission_id, f.policy_version, f.original_eta, f.boundary_at,
           f.verification_case_id, f.opening_event_id, f.verifying_started_at,
           f.guard_version, f.history_baseline_at, f.finalized_at,
           f.access_expires_at, f.source, f.owner_id as final_owner_id,
           a.owner_id as admission_owner_id, a.admission_id as accepted_admission_id,
           a.policy_version as accepted_policy_version,
           a.admitted_at as accepted_admitted_at, a.original_eta as accepted_eta,
           a.boundary_at as accepted_boundary_at
      into v_facts
      from public.journeys j
      left join private.unresolved_incident_finalizations f on f.journey_id = j.id
      left join private.unresolved_boundary_admissions a on a.journey_id = j.id
     where j.id = p_journey_id and j.owner_id = v_owner;

    if not found then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    if v_facts.journey_status <> 'UNRESOLVED_INCIDENT' then
        raise exception 'Journey has no unresolved incident finalization' using errcode = '22023';
    end if;
    if v_facts.finalized_at is null or
       v_facts.final_owner_id is distinct from v_owner or
       v_facts.journey_ended_at is distinct from v_facts.finalized_at or
       v_facts.admission_id is distinct from v_facts.unresolved_admission_id or
       v_facts.policy_version is distinct from v_facts.unresolved_policy_version or
       v_facts.original_eta is distinct from v_facts.journey_eta or
       v_facts.accepted_admission_id is distinct from v_facts.admission_id or
       v_facts.admission_owner_id is distinct from v_owner or
       v_facts.accepted_policy_version is distinct from v_facts.policy_version or
       v_facts.accepted_admitted_at is distinct from v_facts.journey_started_at or
       v_facts.accepted_eta is distinct from v_facts.original_eta or
       v_facts.accepted_boundary_at is distinct from v_facts.boundary_at or
       v_facts.access_expires_at is distinct from v_facts.finalized_at + interval '168 hours' or
       v_facts.source is distinct from 'AUTHORITATIVE_UNRESOLVED_FINALIZER_V1' then
        raise exception 'Inconsistent unresolved finalization provenance' using errcode = '23514';
    end if;

    return query select 'UNRESOLVED_INCIDENT'::text, v_facts.journey_status,
        v_facts.finalized_at, v_facts.boundary_at, v_facts.verification_case_id,
        v_facts.access_expires_at, v_facts.journey_id, v_owner,
        v_facts.admission_id, v_facts.policy_version, v_facts.original_eta,
        v_facts.opening_event_id, v_facts.verifying_started_at,
        v_facts.guard_version, v_facts.history_baseline_at,
        v_facts.finalized_at, v_facts.source;
end;
$$;

revoke all on function public.get_owner_unresolved_incident_finalization_v1(uuid)
    from public, anon, authenticated, service_role;
grant execute on function public.get_owner_unresolved_incident_finalization_v1(uuid)
    to authenticated;

commit;

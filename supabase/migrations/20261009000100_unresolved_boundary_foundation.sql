begin;

-- New admissions only. No historical ETA authority or eligibility is backfilled.
alter table public.journeys
    add column unresolved_admission_id uuid,
    add column unresolved_policy_version text;
alter table public.journeys add constraint unresolved_admission_metadata_pair check (
    (unresolved_admission_id is null and unresolved_policy_version is null) or
    (unresolved_admission_id is not null and unresolved_policy_version is not null and
        unresolved_policy_version = 'alabarin-unresolved-boundary-v1')
);

create table private.unresolved_boundary_admissions (
    journey_id uuid primary key references public.journeys(id) on delete restrict,
    owner_id uuid not null references auth.users(id) on delete restrict,
    admission_id uuid not null unique,
    admitted_at timestamptz not null,
    original_eta timestamptz not null,
    boundary_at timestamptz not null,
    policy_version text not null check (policy_version = 'alabarin-unresolved-boundary-v1'),
    source text not null check (source = 'DEVICE_ATTESTED_LOCAL_ADMISSION'),
    accepted_at timestamptz not null,
    check (original_eta > admitted_at and original_eta <= admitted_at + interval '259200 seconds'),
    check (boundary_at = original_eta + interval '86400 seconds')
);
revoke all on private.unresolved_boundary_admissions from public, anon, authenticated, service_role;

create function private.freeze_unresolved_admission()
returns trigger language plpgsql set search_path = '' as $$
begin
    if tg_op = 'INSERT' then
        -- ON CONFLICT runs INSERT triggers before its UPDATE. Preserve retries of
        -- historical rows, even if their pre-policy duration exceeded 72 hours.
        -- The UPDATE branch still rejects changed ETA or invented provenance.
        if exists (select 1 from public.journeys j where j.id = new.id and j.owner_id = new.owner_id) then
            return new;
        end if;
        -- Validate original local admission, NOT first hosted receipt. Past ETA is allowed.
        if not isfinite(new.started_at) or not isfinite(new.expected_arrival_at)
           or new.started_at < '1970-01-01 UTC'::timestamptz
           or new.expected_arrival_at > '9999-12-30 UTC'::timestamptz
           or new.expected_arrival_at <= new.started_at
           or new.expected_arrival_at > new.started_at + interval '259200 seconds' then
            raise exception 'ETA must be after admission and within 72 elapsed hours' using errcode = '23514';
        end if;
        if new.unresolved_admission_id is not null and (
            new.started_at <> date_trunc('milliseconds', new.started_at) or
            new.expected_arrival_at <> date_trunc('milliseconds', new.expected_arrival_at)) then
            raise exception 'Admission timestamps require millisecond precision' using errcode = '23514';
        end if;
    else
        -- Older clients omit additive nullable fields during otherwise legitimate upserts.
        new.unresolved_admission_id := coalesce(new.unresolved_admission_id, old.unresolved_admission_id);
        new.unresolved_policy_version := coalesce(new.unresolved_policy_version, old.unresolved_policy_version);
        if new.expected_arrival_at is distinct from old.expected_arrival_at or
           new.unresolved_admission_id is distinct from old.unresolved_admission_id or
           new.unresolved_policy_version is distinct from old.unresolved_policy_version or
           (old.unresolved_admission_id is not null and (
               new.started_at is distinct from old.started_at or new.owner_id is distinct from old.owner_id or
               new.id is distinct from old.id)) then
            raise exception 'Admitted ETA and provenance are immutable' using errcode = '23514';
        end if;
    end if;
    return new;
end;
$$;
create trigger freeze_unresolved_admission before insert or update on public.journeys
    for each row execute function private.freeze_unresolved_admission();
revoke all on function private.freeze_unresolved_admission() from public, anon, authenticated, service_role;

create function private.capture_unresolved_admission()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if new.unresolved_admission_id is not null then
        if (select auth.uid()) is distinct from new.owner_id or not exists (
            select 1 from auth.users u where u.id = new.owner_id and u.is_anonymous is false
        ) then
            raise exception 'Authenticated exact owner admission required' using errcode = '42501';
        end if;
        insert into private.unresolved_boundary_admissions values (
            new.id, new.owner_id, new.unresolved_admission_id, new.started_at, new.expected_arrival_at,
            new.expected_arrival_at + interval '86400 seconds', new.unresolved_policy_version,
            'DEVICE_ATTESTED_LOCAL_ADMISSION', date_trunc('milliseconds', clock_timestamp())
        );
    end if;
    return new;
end;
$$;
create trigger capture_unresolved_admission after insert on public.journeys
    for each row execute function private.capture_unresolved_admission();
revoke all on function private.capture_unresolved_admission() from public, anon, authenticated, service_role;

-- Private testable evaluator: a single MVCC read point, not a terminal lease.
-- Only the public wrapper chooses production server time; clients cannot supply it.
create function private.read_unresolved_boundary(p_journey_id uuid, p_owner_id uuid, p_at timestamptz)
returns table (
    journey_id uuid, owner_id uuid, admission_id uuid, policy_version text,
    admitted_at timestamptz, original_eta timestamptz, boundary_at timestamptz,
    accepted_at timestamptz, evaluated_at timestamptz, decision text, reason text,
    verification_case_id uuid, opening_event_id bigint, verifying_started_at timestamptz,
    guard_version bigint, history_baseline_at timestamptz
) language sql stable security definer set search_path = '' as $$
    with facts as (
        select j.id, j.owner_id, j.status, b.admission_id, b.policy_version,
            b.admitted_at, b.original_eta, b.boundary_at, b.accepted_at,
            b.journey_id is not null and b.owner_id = j.owner_id and
                b.admission_id = j.unresolved_admission_id and b.policy_version = j.unresolved_policy_version and
                b.original_eta = j.expected_arrival_at and b.admitted_at = j.started_at as valid_admission,
            s.journey_id as state_id, s.owner_id as state_owner, s.phase, s.closed_at,
            s.verifying_started_at, s.verification_guard_version, s.verification_guard_baseline_at,
            s.last_authenticated_device_evidence_at,
            c.id as case_id, c.owner_id as case_owner, c.opened_at, c.opened_from_monitoring_event_id,
            e.journey_id as event_journey, e.event_type, e.new_phase, e.occurred_at
        from public.journeys j
        left join private.unresolved_boundary_admissions b on b.journey_id = j.id
        left join public.journey_monitoring_state s on s.journey_id = j.id
        left join public.verification_cases c on c.journey_id = j.id and c.status = 'OPEN'
        left join public.journey_monitoring_events e on e.id = c.opened_from_monitoring_event_id
        where j.id = p_journey_id and j.owner_id = p_owner_id
    ), assessed as (
        select f.*, case
            when status <> 'ACTIVE' then 'JOURNEY_NOT_ACTIVE'
            when valid_admission is not true then 'NO_ADMISSION_PROVENANCE'
            when p_at < boundary_at then 'BOUNDARY_NOT_REACHED'
            when state_id is null then 'MISSING_MONITORING_STATE'
            when state_owner <> owner_id or closed_at is not null then 'INCONSISTENT_STATE'
            when phase = 'EVIDENCE_FRESH' and case_id is null and verifying_started_at is null then 'NOT_VERIFYING'
            when phase <> 'VERIFYING' or case_id is null or case_owner <> owner_id or
                 verifying_started_at is null or opened_at <> verifying_started_at or
                 event_journey is distinct from id or event_type <> 'VERIFYING_STARTED' or
                 new_phase <> 'VERIFYING' or occurred_at <> opened_at or
                 exists (select 1 from public.journey_monitoring_events newer
                         where newer.journey_id = f.id and newer.event_type = 'VERIFYING_STARTED'
                           and newer.id > f.opened_from_monitoring_event_id) or
                 verification_guard_baseline_at > p_at or opened_at > p_at then 'INCONSISTENT_STATE'
            when last_authenticated_device_evidence_at >= verifying_started_at then 'SUPERSEDED_EPISODE'
            else 'CURRENT_UNRESOLVED' end as assessment
        from facts f
    )
    select id, owner_id, admission_id, policy_version, admitted_at, original_eta, boundary_at,
        accepted_at, p_at,
        case when assessment = 'CURRENT_UNRESOLVED' then 'ELIGIBLE'
             when assessment in ('INCONSISTENT_STATE','MISSING_MONITORING_STATE') then 'UNKNOWN'
             else 'NOT_ELIGIBLE' end,
        assessment, case_id, opened_from_monitoring_event_id, verifying_started_at,
        verification_guard_version, verification_guard_baseline_at
    from assessed;
$$;
revoke all on function private.read_unresolved_boundary(uuid,uuid,timestamptz)
    from public, anon, authenticated, service_role;

create function public.get_unresolved_boundary_eligibility_v1(p_journey_id uuid)
returns table (
    journey_id uuid, owner_id uuid, admission_id uuid, policy_version text,
    admitted_at timestamptz, original_eta timestamptz, boundary_at timestamptz,
    accepted_at timestamptz, evaluated_at timestamptz, decision text, reason text,
    verification_case_id uuid, opening_event_id bigint, verifying_started_at timestamptz,
    guard_version bigint, history_baseline_at timestamptz
) language plpgsql security definer set search_path = '' as $$
begin
    if (select auth.uid()) is null or not exists (
        select 1 from auth.users u where u.id = (select auth.uid()) and u.is_anonymous is false
    ) then
        raise exception 'Non-anonymous Traveller identity required' using errcode = '42501';
    end if;
    if not exists (select 1 from public.journeys j where j.id = p_journey_id and j.owner_id = (select auth.uid())) then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    return query select * from private.read_unresolved_boundary(p_journey_id, (select auth.uid()),
        date_trunc('milliseconds', clock_timestamp()));
end;
$$;
revoke all on function public.get_unresolved_boundary_eligibility_v1(uuid)
    from public, anon, authenticated, service_role;
grant execute on function public.get_unresolved_boundary_eligibility_v1(uuid) to authenticated;

commit;

-- Run only against a disposable local Supabase database.
-- psql -v owner_id=<real local Auth user UUID> -f this_file
\set ON_ERROR_STOP on
begin;
set local role authenticated;
set local request.jwt.claim.sub = :'owner_id';

with t as (select date_trunc('milliseconds', clock_timestamp()) - interval '3 days' as admitted)
insert into public.journeys (id, owner_id, destination, started_at,
    expected_arrival_at, status, unresolved_admission_id, unresolved_policy_version)
select '20000000-0000-4000-8000-0000000010d1', :'owner_id', 'Local incident acceptance',
    admitted, admitted + interval '1 hour', 'ACTIVE',
    '30000000-0000-4000-8000-0000000010d1', 'alabarin-unresolved-boundary-v1' from t;

with t as (select date_trunc('milliseconds', clock_timestamp()) - interval '1 hour' as admitted)
insert into public.journeys (id, owner_id, destination, started_at,
    expected_arrival_at, status, unresolved_admission_id, unresolved_policy_version)
select '20000000-0000-4000-8000-0000000010d2', :'owner_id', 'Local early acceptance',
    admitted, admitted + interval '2 hours', 'ACTIVE',
    '30000000-0000-4000-8000-0000000010d2', 'alabarin-unresolved-boundary-v1' from t;

reset role;
insert into public.journey_monitoring_state
    (journey_id, owner_id, phase, last_cloud_contact_at)
values
    ('20000000-0000-4000-8000-0000000010d1', :'owner_id', 'EVIDENCE_FRESH', clock_timestamp() - interval '1 hour'),
    ('20000000-0000-4000-8000-0000000010d2', :'owner_id', 'EVIDENCE_FRESH', clock_timestamp());

select verifying_started from public.evaluate_due_journeys();

set local role authenticated;
with t as (select date_trunc('milliseconds', clock_timestamp()) - interval '3 days' as admitted)
insert into public.journeys (id, owner_id, destination, started_at,
    expected_arrival_at, status, unresolved_admission_id, unresolved_policy_version)
select '20000000-0000-4000-8000-0000000010d3', :'owner_id', 'Local no case acceptance',
    admitted, admitted + interval '1 hour', 'ACTIVE',
    '30000000-0000-4000-8000-0000000010d3', 'alabarin-unresolved-boundary-v1' from t;

reset role;
insert into public.journey_monitoring_state
    (journey_id, owner_id, phase, last_cloud_contact_at)
values ('20000000-0000-4000-8000-0000000010d3', :'owner_id', 'EVIDENCE_FRESH', clock_timestamp());

commit;

select j.id, j.status, s.phase, a.boundary_at < clock_timestamp() as boundary_passed,
       c.status as case_status
from public.journeys j
join private.unresolved_boundary_admissions a on a.journey_id=j.id
left join public.journey_monitoring_state s on s.journey_id=j.id
left join public.verification_cases c on c.journey_id=j.id
where j.id in ('20000000-0000-4000-8000-0000000010d1',
               '20000000-0000-4000-8000-0000000010d2',
               '20000000-0000-4000-8000-0000000010d3') order by j.id;

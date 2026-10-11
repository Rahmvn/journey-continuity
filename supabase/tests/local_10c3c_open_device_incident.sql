-- Run only after the disposable Android test logs INCIDENT_FIXTURE_READY.
-- The Journey and exact admission were created by the Android owner session.
\set ON_ERROR_STOP on
begin;
insert into public.journey_monitoring_state
    (journey_id, owner_id, phase, last_cloud_contact_at)
select id, owner_id, 'EVIDENCE_FRESH',
    date_trunc('milliseconds', clock_timestamp()) - interval '1 hour'
from public.journeys
where id = '20000000-0000-4000-8000-0000000010f1'
  and status = 'ACTIVE' and unresolved_admission_id is not null;
select verifying_started from public.evaluate_due_journeys();
commit;
select j.status, s.phase, c.status as case_status,
       a.boundary_at < clock_timestamp() as boundary_passed
from public.journeys j
join private.unresolved_boundary_admissions a on a.journey_id = j.id
join public.journey_monitoring_state s on s.journey_id = j.id
join public.verification_cases c on c.journey_id = j.id
where j.id = '20000000-0000-4000-8000-0000000010f1';

-- Fresh Section 09 window for a real TOO_EARLY Android RPC test.
-- An elapsed 30-minute window cannot be fabricated here without bypassing
-- the immutable verification guard history trigger; see acceptance report.
\set ON_ERROR_STOP on
begin;
set local role authenticated;
set local request.jwt.claim.sub = :'owner_id';
with t as (select date_trunc('milliseconds', clock_timestamp()) as at)
insert into public.journeys (id, owner_id, destination, started_at,
    expected_arrival_at, status)
select '20000000-0000-4000-8000-0000000010c4', :'owner_id',
    'Local probable end acceptance', at - interval '1 hour',
    at + interval '1 hour', 'ACTIVE' from t;
reset role;
with t as (select date_trunc('milliseconds', clock_timestamp()) as at)
insert into public.journey_monitoring_state
    (journey_id, owner_id, phase, last_cloud_contact_at,
     last_authenticated_device_evidence_at,
     last_authenticated_device_evidence_received_at,
     last_authenticated_device_evidence_transport)
select '20000000-0000-4000-8000-0000000010c4', :'owner_id',
    'EVIDENCE_FRESH', at, at, at, 'CLOUD_HEARTBEAT' from t;
commit;

begin;
select plan(39);

select has_column('public', 'journeys', 'ended_at', 'generic terminal time is present');
select ok(not has_function_privilege('anon', 'public.list_trusted_journey_statuses_v2()', 'EXECUTE'),
    'anonymous callers cannot list trusted terminal status');
select ok(not has_function_privilege('anon', 'public.stop_journey_monitoring_v1(uuid)', 'EXECUTE'),
    'anonymous callers cannot stop monitoring');

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at)
values
    ('10000000-0000-4000-8000-000000000081', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'terminal-owner@example.invalid', now(), now()),
    ('10000000-0000-4000-8000-000000000082', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'terminal-contact@example.invalid', now(), now());

insert into public.trusted_contact_relationships
    (id, traveller_user_id, contact_user_id, display_name, contact_email_normalized)
values ('30000000-0000-4000-8000-000000000081',
        '10000000-0000-4000-8000-000000000081',
        '10000000-0000-4000-8000-000000000082',
        'Trusted Contact', 'terminal-contact@example.invalid');

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values
    ('20000000-0000-4000-8000-000000000081', '10000000-0000-4000-8000-000000000081',
     'Abuja', now() + interval '1 hour', now(), 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000082', '10000000-0000-4000-8000-000000000081',
     'Lagos', now() + interval '1 hour', now(), 'ACTIVE');

insert into public.journey_monitoring_events
    (journey_id, event_type, occurred_at, new_phase, reason_code)
values ('20000000-0000-4000-8000-000000000081', 'VERIFYING_STARTED', now(),
        'VERIFYING', 'TEST_FIXTURE');
select public.open_verification_case(
    '20000000-0000-4000-8000-000000000081',
    '10000000-0000-4000-8000-000000000081', id, now(), null)
from public.journey_monitoring_events
where journey_id = '20000000-0000-4000-8000-000000000081'
  and event_type = 'VERIFYING_STARTED';

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000081';
set local request.jwt.claim.role = 'authenticated';
select lives_ok($$select * from public.stop_journey_monitoring_v1(
    '20000000-0000-4000-8000-000000000081')$$,
    'owner-authenticated stop is acknowledged');
select lives_ok($$select * from public.stop_journey_monitoring_v1(
    '20000000-0000-4000-8000-000000000081')$$,
    'retry after a lost acknowledgement is idempotent');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000082';
select throws_ok($$select * from public.stop_journey_monitoring_v1(
    '20000000-0000-4000-8000-000000000082')$$,
    '42501', 'Journey is not available to this caller',
    'a trusted contact cannot stop the Traveller Journey');
reset role;
select is((select status from public.journeys where id = '20000000-0000-4000-8000-000000000081'),
    'CANCELLED', 'stop is its own terminal status');
select ok((select completed_at is null and ended_at is not null from public.journeys
    where id = '20000000-0000-4000-8000-000000000081'),
    'stopped Journey has end time but no completion time');
select is((select phase from public.journey_monitoring_state
    where journey_id = '20000000-0000-4000-8000-000000000081'),
    'CLOSED', 'stopped Journey closes cloud monitoring');
select ok((select s.closed_at = j.ended_at and s.updated_at >= j.ended_at
    from public.journey_monitoring_state s join public.journeys j on j.id = s.journey_id
    where j.id = '20000000-0000-4000-8000-000000000081'),
    'stop closure and monitoring update use the persisted end time');
select ok((select j.updated_at >= j.ended_at from public.journeys j
    where j.id = '20000000-0000-4000-8000-000000000081'),
    'stopped Journey update cannot predate its end time');
select ok((select e.occurred_at = j.ended_at from public.journey_monitoring_events e
    join public.journeys j on j.id = e.journey_id
    where j.id = '20000000-0000-4000-8000-000000000081'
      and e.event_type = 'MONITORING_CLOSED'),
    'stop closure event uses the persisted end time');
select is((select reason_code from public.journey_monitoring_events
    where journey_id = '20000000-0000-4000-8000-000000000081'
      and event_type = 'MONITORING_CLOSED'),
    'MONITORING_STOPPED', 'closure has a stop-specific reason');
select is((select count(*) from public.journey_monitoring_events
    where journey_id = '20000000-0000-4000-8000-000000000081'
      and reason_code = 'JOURNEY_COMPLETED'),
    0::bigint, 'stop emits no completion event');
select is((select resolution_reason from public.verification_cases
    where journey_id = '20000000-0000-4000-8000-000000000081'),
    'MONITORING_STOPPED', 'open verification case resolves without claiming completion');
select ok((select c.resolved_at = j.ended_at and
           c.sensitive_access_expires_at = j.ended_at + public.sensitive_case_access_ttl()
    from public.verification_cases c join public.journeys j on j.id = c.journey_id
    where j.id = '20000000-0000-4000-8000-000000000081'),
    'verification resolution and sensitive expiry derive from persisted end time');
select ok((select bool_and(a.sensitive_access_expires_at = j.ended_at + public.sensitive_case_access_ttl())
    from public.journey_trusted_contact_access a join public.journeys j on j.id = a.journey_id
    where j.id = '20000000-0000-4000-8000-000000000081'),
    'trusted sensitive access expiry derives from persisted end time');
select is((select count(*) from public.trusted_contact_notification_outbox
    where journey_id = '20000000-0000-4000-8000-000000000081'
      and notification_kind = 'JOURNEY_COMPLETED'),
    0::bigint, 'stop does not enqueue a completion notification');
select lives_ok($$select * from public.evaluate_due_journeys()$$,
    'watchdog accepts stopped terminal state');
select is((select count(*) from public.journey_monitoring_events
    where journey_id = '20000000-0000-4000-8000-000000000081'
      and event_type = 'MONITORING_CLOSED'),
    1::bigint, 'watchdog does not duplicate closure');

-- Simulate recovery of a stale monitoring row after an already-durable stop.
-- The watchdog must reuse the original end time, rather than recovery wall time.
insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-4000-8000-000000000083',
        '10000000-0000-4000-8000-000000000081', 'Ibadan', now() + interval '1 hour', now(), 'ACTIVE');
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000081';
select lives_ok($$select * from public.stop_journey_monitoring_v1(
    '20000000-0000-4000-8000-000000000083')$$, 'watchdog fixture is cancelled');
reset role;
update public.journey_monitoring_state
set phase = 'VERIFYING', closed_at = null, verifying_started_at = now()
where journey_id = '20000000-0000-4000-8000-000000000083';
insert into public.journey_monitoring_events
    (journey_id, event_type, occurred_at, new_phase, reason_code)
values ('20000000-0000-4000-8000-000000000083', 'VERIFYING_STARTED', now(),
        'VERIFYING', 'TEST_RECOVERY_FIXTURE');
select public.open_verification_case(
    '20000000-0000-4000-8000-000000000083',
    '10000000-0000-4000-8000-000000000081', id, now(), null)
from public.journey_monitoring_events
where journey_id = '20000000-0000-4000-8000-000000000083'
  and event_type = 'VERIFYING_STARTED';
select lives_ok($$select * from public.evaluate_due_journeys()$$,
    'watchdog repairs an already-cancelled monitoring row');
select ok((select s.closed_at = j.ended_at and s.updated_at = j.ended_at
    from public.journey_monitoring_state s join public.journeys j on j.id = s.journey_id
    where j.id = '20000000-0000-4000-8000-000000000083'),
    'watchdog closure keeps the persisted cancellation time');
select ok((select e.occurred_at = j.ended_at from public.journey_monitoring_events e
    join public.journeys j on j.id = e.journey_id
    where j.id = '20000000-0000-4000-8000-000000000083'
      and e.event_type = 'MONITORING_CLOSED' order by e.id desc limit 1),
    'watchdog closure event keeps the persisted cancellation time');
select ok((select c.resolved_at = j.ended_at from public.verification_cases c
    join public.journeys j on j.id = c.journey_id
    where j.id = '20000000-0000-4000-8000-000000000083'),
    'watchdog verification resolution keeps the persisted cancellation time');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000081';
set local request.jwt.claim.role = 'authenticated';
select throws_ok(
    $$insert into public.journeys
      (id, owner_id, destination, expected_arrival_at, started_at, status)
      values ('20000000-0000-4000-8000-000000000081',
              '10000000-0000-4000-8000-000000000081', 'Abuja', now(), now(), 'ACTIVE')
      on conflict (id) do update set status = 'ACTIVE', completed_at = null, ended_at = null$$,
    'JT001', 'Terminal Journey state cannot be rewritten',
    'stale normal owner upsert cannot reopen cancellation');
select throws_ok(
    $$update public.journeys set status = 'COMPLETED', completed_at = now(), ended_at = null
      where id = '20000000-0000-4000-8000-000000000081'$$,
    'JT001', 'Terminal Journey state cannot be rewritten',
    'completion cannot overwrite cancellation');
select throws_ok(
    $$select * from public.record_journey_heartbeat(
      '20000000-0000-4000-8000-000000000081', 1, now(), null, null, 'NONE', 0)$$,
    '22023', 'Heartbeat requires an ACTIVE Journey',
    'stopped Journey rejects a new heartbeat');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000082';
select is((select count(*) from public.journeys
    where id = '20000000-0000-4000-8000-000000000081'),
    0::bigint, 'non-owner exact Journey lookup sees no terminal row');
select is((select journey_status from public.list_trusted_journey_statuses_v2()
    where journey_id = '20000000-0000-4000-8000-000000000081'),
    'CANCELLED', 'trusted projection distinguishes cancellation');
select ok((public.get_trusted_journey_status_v2('20000000-0000-4000-8000-000000000081')->>'ended_at') is not null,
    'versioned trusted status exposes the stop time');
select ok((public.get_trusted_journey_snapshot('20000000-0000-4000-8000-000000000081')->>'ended_at') is not null,
    'existing Viewer snapshot exposes stop time');
reset role;

select lives_ok($$update public.journeys
    set status = 'COMPLETED', completed_at = now()
    where id = '20000000-0000-4000-8000-000000000082'$$,
    'existing completion remains accepted');
select ok((select completed_at is not null and ended_at is null from public.journeys
    where id = '20000000-0000-4000-8000-000000000082'),
    'completion retains its historical timestamp contract');
select is((select reason_code from public.journey_monitoring_events
    where journey_id = '20000000-0000-4000-8000-000000000082'
      and event_type = 'MONITORING_CLOSED'),
    'JOURNEY_COMPLETED', 'completion closure reason remains unchanged');
select ok((select s.closed_at = now() and e.occurred_at = now()
    from public.journey_monitoring_state s
    join public.journey_monitoring_events e on e.journey_id = s.journey_id
    where s.journey_id = '20000000-0000-4000-8000-000000000082'
      and e.event_type = 'MONITORING_CLOSED'),
    'completion closure retains the existing transaction-time behavior');
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000081';
select is((select status from public.journeys
    where id = '20000000-0000-4000-8000-000000000081'),
    'CANCELLED', 'owner exact Journey lookup sees terminal truth');
select throws_ok(
    $$update public.journeys set status = 'ACTIVE', completed_at = null
      where id = '20000000-0000-4000-8000-000000000082'$$,
    'JT001', 'Terminal Journey state cannot be rewritten',
    'stale owner update cannot reopen completion');

reset role;
insert into private.fallback_installations (id, owner_id, installation_identifier)
values ('30000000-0000-4000-8000-000000000083',
        '10000000-0000-4000-8000-000000000081',
        '31000000-0000-4000-8000-000000000083');
insert into private.fallback_installation_keys
    (id, installation_id, owner_id, key_id, encrypted_master_key, encryption_iv,
     encryption_version, lifecycle_status)
values ('40000000-0000-4000-8000-000000000083',
        '30000000-0000-4000-8000-000000000083',
        '10000000-0000-4000-8000-000000000081', 1083,
        decode(repeat('11', 48), 'hex'), decode(repeat('12', 12), 'hex'), 1, 'ACTIVE');
insert into private.journey_fallback_bindings
    (id, journey_id, owner_id, installation_id, installation_key_id, key_id,
     journey_handle, binding_status)
values ('50000000-0000-4000-8000-000000000083',
        '20000000-0000-4000-8000-000000000081',
        '10000000-0000-4000-8000-000000000081',
        '30000000-0000-4000-8000-000000000083',
        '40000000-0000-4000-8000-000000000083', 1083,
        decode(repeat('a3', 12), 'hex'), 'ACTIVE');
create temporary table cancelled_before_inbound as
select * from public.journeys where id = '20000000-0000-4000-8000-000000000081';
create temporary table late_completion as
select * from public.record_fallback_inbound_result_backend(
    'verified-test-adapter', 'completion-after-monitoring-stop', clock_timestamp(),
    clock_timestamp(), 102, decode(repeat('01', 32), 'hex'), 'AUTHENTICATED',
    'JOURNEY_COMPLETED', 1083, decode(repeat('a3', 12), 'hex'), 1, 1,
    date_trunc('second', clock_timestamp()) + interval '1 second',
    65432100, 32109800, 125, 67, true, 'CELLULAR');
select ok((select reconciliation = 'COMPLETION_STALE' from late_completion)
    and (select j.status = 'CANCELLED' and j.completed_at is null and j.ended_at = c.ended_at
         from public.journeys j join cancelled_before_inbound c on c.id = j.id),
    'inbound JOURNEY_COMPLETED cannot rewrite an already-CANCELLED Journey');

select * from finish();
rollback;

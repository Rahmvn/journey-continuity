-- pgTAP acceptance; run only against a disposable, migrated local database.
begin;
select no_plan();
select has_column('public', 'journey_monitoring_state', 'verification_guard_version', 'verification guard version column exists');
select ok(not has_function_privilege('anon', 'public.get_probable_end_verification_guard_v1(uuid)', 'EXECUTE'), 'unauthenticated role cannot read guard');
select ok(has_function_privilege('authenticated', 'public.get_probable_end_verification_guard_v1(uuid)', 'EXECUTE'), 'authenticated owner RPC granted');
select ok(not has_table_privilege('authenticated', 'public.journey_monitoring_state', 'UPDATE'), 'client cannot maintain the cursor');
select is(private.authenticated_device_evidence_timeout_seconds(), 300, 'canonical timeout retained');

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at, is_anonymous) values
('10000000-0000-4000-8000-000000000091', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'guard-owner@example.invalid', now(), now(), false),
('10000000-0000-4000-8000-000000000092', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'guard-other@example.invalid', now(), now(), false);
insert into public.journeys (id, owner_id, destination, started_at, expected_arrival_at, status) values
('20000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091', 'Fixture', now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE'),
('20000000-0000-4000-8000-000000000092', '10000000-0000-4000-8000-000000000091', 'Fixture', now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE');
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
set local request.jwt.claim.role = 'authenticated';
select public.record_journey_heartbeat('20000000-0000-4000-8000-000000000091', 1, now(), null, null, 'WIFI', 0);
select is((select decision from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'CLEAR', 'fresh owner ACTIVE Journey is clear');
select is((select guard_version from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 0::bigint, 'new monitoring starts at truthful baseline zero');
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000092')), 'MISSING_MONITORING_STATE', 'missing state is blocked');
select ok((select guard_version is null and decision = 'BLOCKED' from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000092')), 'missing version cannot manufacture clearance');
select set_config('test.guard_version', (select guard_version::text from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), true);
select is((select guard_version from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), current_setting('test.guard_version')::bigint, 'reads do not advance version');
select public.record_journey_heartbeat('20000000-0000-4000-8000-000000000091', 2, now(), null, null, 'WIFI', 0);
select is((select guard_version from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), current_setting('test.guard_version')::bigint, 'ordinary fresh heartbeat does not advance version');
select public.record_journey_heartbeat('20000000-0000-4000-8000-000000000091', 2, now(), null, null, 'WIFI', 0);
select is((select guard_version from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), current_setting('test.guard_version')::bigint, 'duplicate heartbeat upsert preserves version');
select throws_ok($$update public.journey_monitoring_state set verification_guard_version = 10 where journey_id = '20000000-0000-4000-8000-000000000091'$$,
    '42501', 'permission denied for table journey_monitoring_state', 'owner cannot forge guard version');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000092';
select throws_ok($$select * from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')$$, '42501', 'Journey is not available to this caller', 'wrong owner denied');
select throws_ok($$select * from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000099')$$, '42501', 'Journey is not available to this caller', 'absent ID uses identical anti-enumeration error');
set local request.jwt.claim.sub = '';
select throws_ok($$select * from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')$$, '42501', 'Authentication required', 'missing identity denied');
reset role;

update auth.users set is_anonymous = true where id = '10000000-0000-4000-8000-000000000091';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
select throws_ok($$select * from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')$$,
    '42501', 'Non-anonymous Traveller identity required', 'anonymous-auth owner also denied');
reset role;
update auth.users set is_anonymous = false where id = '10000000-0000-4000-8000-000000000091';

-- A recent eligible fallback timestamp remains authoritative with old cloud contact.
update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '1 hour',
    last_authenticated_device_evidence_at = now() - interval '10 seconds',
    last_authenticated_device_evidence_received_at = now(),
    last_authenticated_device_evidence_transport = 'FALLBACK_SMS',
    latest_authenticated_fallback_envelope_sequence = 1,
    latest_authenticated_device_telemetry_sequence = 1
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
select is((select decision from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'CLEAR', 'fresh canonical fallback evidence is usable without fresh cloud contact');
select ok((select fresh_until = now() - interval '10 seconds' + interval '300 seconds' from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'freshness bound uses authenticated event time');
reset role;
update public.journey_monitoring_state set latest_authenticated_fallback_envelope_sequence = null
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'INCONSISTENT_STATE', 'fallback source without canonical sequence metadata is not clearance');
reset role;
update public.journey_monitoring_state set latest_authenticated_fallback_envelope_sequence = 1
where journey_id = '20000000-0000-4000-8000-000000000091';
update public.journey_monitoring_state set last_cloud_contact_at = now(),
    last_authenticated_device_evidence_at = now(), last_authenticated_device_evidence_received_at = now(),
    last_authenticated_device_evidence_transport = 'CLOUD_HEARTBEAT'
where journey_id = '20000000-0000-4000-8000-000000000091';

-- Delayed watchdog: direct freshness blocks while persisted phase is still fresh.
update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '300 seconds',
    last_authenticated_device_evidence_at = now() - interval '300 seconds',
    last_authenticated_device_evidence_received_at = now() - interval '300 seconds'
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'STALE_AUTHENTICATED_EVIDENCE', 'exact 300-second boundary fails closed without watchdog');
reset role;
select is((select phase from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), 'EVIDENCE_FRESH', 'guard did not transition phase');
select public.evaluate_due_journeys();
select ok((select verification_guard_version > current_setting('test.guard_version')::bigint from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), 'watchdog opening permanently advances cursor');
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'VERIFYING', 'verifying blocks');
select is((select verification_state from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'UNRESOLVED', 'unresolved truth is explicit');
select set_config('test.open_version', (select guard_version::text from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), true);
select public.record_journey_heartbeat('20000000-0000-4000-8000-000000000091', 3, now(), null, null, 'WIFI', 0);
select is((select decision from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'CLEAR', 'fresh heartbeat resolves verification');
select is((select guard_version from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), current_setting('test.open_version')::bigint, 'resolution preserves advanced cursor');
select ok((select guard_version > current_setting('test.guard_version')::bigint from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'old candidate detects intervening episode even after clear');
reset role;
select is((select status from public.verification_cases where journey_id = '20000000-0000-4000-8000-000000000091'), 'RESOLVED', 'case resolved through existing lifecycle');

-- An independent case opening reusing an older event still advances the guard.
select public.open_verification_case('20000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091',
    (select id from public.journey_monitoring_events where journey_id = '20000000-0000-4000-8000-000000000091' and event_type = 'VERIFYING_STARTED'), now(), now());
select ok((select verification_guard_version > current_setting('test.open_version')::bigint from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), 'case opening is covered without a new event ID');
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'OPEN_VERIFICATION_CASE', 'OPEN case blocks despite fresh phase');
reset role;
select set_config('test.direct_case_id', (select id::text from public.verification_cases where journey_id = '20000000-0000-4000-8000-000000000091' and status = 'OPEN'), true);
select set_config('test.direct_version', (select verification_guard_version::text from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), true);
update public.verification_cases set status = 'OPEN' where id = current_setting('test.direct_case_id')::uuid;
insert into public.verification_cases (journey_id, owner_id, opened_from_monitoring_event_id,
    journey_destination, journey_started_at, journey_expected_arrival_at)
select journey_id, owner_id, opened_from_monitoring_event_id,
    journey_destination, journey_started_at, journey_expected_arrival_at
from public.verification_cases where id = current_setting('test.direct_case_id')::uuid
on conflict (journey_id) where status = 'OPEN' do nothing;
select is((select verification_guard_version from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), current_setting('test.direct_version')::bigint, 'OPEN no-op update and ignored insert cannot manufacture a transition');
update public.journey_monitoring_state set phase = 'VERIFYING', verifying_started_at = now()
where journey_id = '20000000-0000-4000-8000-000000000091';
select ok((select verification_guard_version > current_setting('test.direct_version')::bigint from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), 'phase entry advances even with a case already OPEN');
select set_config('test.before_reopen', (select verification_guard_version::text from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), true);
select public.resolve_open_verification_case('20000000-0000-4000-8000-000000000091', 'DEVICE_CONTACT_RESTORED', now());
update public.verification_cases set status = 'OPEN', resolution_reason = null, resolved_at = null,
    sensitive_access_expires_at = null where id = current_setting('test.direct_case_id')::uuid;
select ok((select verification_guard_version > current_setting('test.before_reopen')::bigint from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), 'reopening advances while phase is already VERIFYING and event reference is unchanged');
select public.resolve_open_verification_case('20000000-0000-4000-8000-000000000091', 'DEVICE_CONTACT_RESTORED', now());
update public.journey_monitoring_state set phase = 'EVIDENCE_FRESH', verifying_started_at = null, updated_at = now()
where journey_id = '20000000-0000-4000-8000-000000000091';
select set_config('test.after_reopen', (select verification_guard_version::text from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), true);
update public.journey_monitoring_state set updated_at = now()
where journey_id = '20000000-0000-4000-8000-000000000091';
select is((select verification_guard_version from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), current_setting('test.after_reopen')::bigint, 'unrelated update and resolution preserve history');
select throws_ok($$update public.journey_monitoring_state set verification_guard_version = 0 where journey_id = '20000000-0000-4000-8000-000000000091'$$, '23514', 'Verification guard history cannot regress', 'cursor cannot decrease');
select throws_ok($$update public.journey_monitoring_state set verification_guard_baseline_at = now() + interval '1 second' where journey_id = '20000000-0000-4000-8000-000000000091'$$, '23514', 'Verification guard history cannot regress', 'baseline immutable');

-- Missing authenticated evidence / future evidence / mismatched owner fail closed.
update public.journey_monitoring_state set last_authenticated_device_evidence_at = now() - interval '1 second',
    last_authenticated_device_evidence_received_at = now() - interval '1 second'
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'INCONSISTENT_STATE', 'recent timestamps alone cannot bypass source inconsistency');
reset role;
update public.journey_monitoring_state set last_authenticated_device_evidence_at = 'infinity',
    last_authenticated_device_evidence_received_at = 'infinity',
    last_authenticated_device_evidence_transport = 'FALLBACK_SMS'
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'INCONSISTENT_STATE', 'non-finite authenticated timestamps cannot be fresh');
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = null,
    last_authenticated_device_evidence_at = null, last_authenticated_device_evidence_transport = null,
    last_authenticated_device_evidence_received_at = null
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'INCONSISTENT_STATE', 'missing authenticated evidence cannot be clear');
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now() + interval '1 minute'
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'INCONSISTENT_STATE', 'future server evidence fails closed');
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now(),
    last_authenticated_device_evidence_at = now(), last_authenticated_device_evidence_received_at = now(),
    owner_id = '10000000-0000-4000-8000-000000000092'
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'INCONSISTENT_STATE', 'mismatched monitoring owner blocks');
reset role;
update public.journey_monitoring_state set owner_id = '10000000-0000-4000-8000-000000000091',
    phase = 'CLOSED', closed_at = now()
where journey_id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select is((select reason from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'INCONSISTENT_STATE', 'ACTIVE Journey with CLOSED monitoring blocks');
reset role;
update public.journeys set status = 'COMPLETED', completed_at = now()
where id = '20000000-0000-4000-8000-000000000091';
set local role authenticated;
select ok((select not journey_active and decision = 'BLOCKED' and reason = 'JOURNEY_NOT_ACTIVE'
    from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000091')), 'COMPLETED never returns actionable clear');
select public.stop_journey_monitoring_v1('20000000-0000-4000-8000-000000000092');
select ok((select not journey_active and decision = 'BLOCKED'
    from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000092')), 'CANCELLED never returns actionable clear');
reset role;
select is((select count(*) from public.journey_monitoring_events where journey_id = '20000000-0000-4000-8000-000000000091' and event_type = 'VERIFYING_STARTED'), 1::bigint, 'guard reads fabricate no monitoring events');
select * from finish();
rollback;

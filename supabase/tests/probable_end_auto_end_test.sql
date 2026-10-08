-- Disposable fully migrated PostgreSQL database only. All fixture writes roll back.
begin;
select no_plan();

select has_table('private', 'probable_end_confirmation_windows', 'server window exists');
select ok(has_function_privilege('authenticated',
    'public.register_probable_end_confirmation_window_v1(uuid,uuid,uuid,bigint,text,timestamptz,bigint)',
    'EXECUTE'), 'owner registration RPC is granted');
select ok(has_function_privilege('authenticated',
    'public.finalize_probable_end_auto_end_v1(uuid,uuid)', 'EXECUTE'),
    'owner finalization RPC is granted');
select ok(not has_function_privilege('anon',
    'public.finalize_probable_end_auto_end_v1(uuid,uuid)', 'EXECUTE'), 'anon cannot finalize');
select ok(not has_function_privilege('anon',
    'public.register_probable_end_confirmation_window_v1(uuid,uuid,uuid,bigint,text,timestamptz,bigint)',
    'EXECUTE'), 'anon cannot register');
select ok(not has_table_privilege('authenticated', 'private.probable_end_confirmation_windows', 'UPDATE'),
    'client cannot rewrite server window');

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at, is_anonymous) values
('10000000-0000-4000-8000-0000000000a1', '00000000-0000-0000-0000-000000000000',
 'authenticated', 'authenticated', 'auto-owner@example.invalid', now(), now(), false),
('10000000-0000-4000-8000-0000000000a2', '00000000-0000-0000-0000-000000000000',
 'authenticated', 'authenticated', 'auto-other@example.invalid', now(), now(), false);
insert into public.journeys (id, owner_id, destination, started_at, expected_arrival_at, status) values
('20000000-0000-4000-8000-0000000000a1', '10000000-0000-4000-8000-0000000000a1',
 'Fixture', now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE'),
('20000000-0000-4000-8000-0000000000a2', '10000000-0000-4000-8000-0000000000a1',
 'Fixture', now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE'),
('20000000-0000-4000-8000-0000000000a3', '10000000-0000-4000-8000-0000000000a1',
 'Fixture', now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE');
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000a1';
select public.record_journey_heartbeat('20000000-0000-4000-8000-0000000000a1', 1, now(), null, null, 'WIFI', 0);
select public.record_journey_heartbeat('20000000-0000-4000-8000-0000000000a2', 1, now(), null, null, 'WIFI', 0);
select public.record_journey_heartbeat('20000000-0000-4000-8000-0000000000a3', 1, now(), null, null, 'WIFI', 0);
select is((select decision from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a1',
    '40000000-0000-4000-8000-0000000000a1', 1, 'alabarin-probable-end-v1',
    (select date_trunc('milliseconds', history_baseline_at) from public.get_probable_end_verification_guard_v1(
        '20000000-0000-4000-8000-0000000000a1')),
    0)), 'REGISTERED', 'fresh exact owner can register');
select ok((select not_before_at = registered_at + interval '30 minutes'
    from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a1',
    '40000000-0000-4000-8000-0000000000a1', 1, 'alabarin-probable-end-v1',
    (select date_trunc('milliseconds', history_baseline_at) from public.get_probable_end_verification_guard_v1(
        '20000000-0000-4000-8000-0000000000a1')), 0)), 'server boundary is exactly thirty minutes');
reset role;
select ok((select count(*) = 1 from private.probable_end_confirmation_windows
    where presentation_episode_id = '30000000-0000-4000-8000-0000000000a1'),
    'duplicate registration kept one window');
select ok((select registered_at >= transaction_timestamp() and registered_at <= clock_timestamp()
    from private.probable_end_confirmation_windows
    where presentation_episode_id = '30000000-0000-4000-8000-0000000000a1'),
    'registration uses current server time, never client presentation time');
select throws_ok($$update private.probable_end_confirmation_windows
    set registered_at = registered_at - interval '1 minute'
    where presentation_episode_id = '30000000-0000-4000-8000-0000000000a1'$$,
    '23514', 'Confirmation window is immutable', 'server window cannot be shortened');
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a1')),
    'TOO_EARLY', 'server refuses before authoritative deadline');
select is((select status from public.journeys where id = '20000000-0000-4000-8000-0000000000a1'),
    'ACTIVE', 'too-early call did not end Journey');
select throws_ok($$update private.probable_end_confirmation_windows set not_before_at = now()
    where presentation_episode_id = '30000000-0000-4000-8000-0000000000a1'$$,
    '42501', 'permission denied for schema private',
    'client cannot forge not-before time');
select throws_ok($$update public.journeys set status = 'AUTO_ENDED', ended_at = now()
    where id = '20000000-0000-4000-8000-0000000000a1'$$,
    '42501', null, 'ordinary owner update cannot forge AUTO_ENDED');
select is((select decision from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a9',
    '40000000-0000-4000-8000-0000000000a9', 1, 'alabarin-probable-end-v1',
    (select date_trunc('milliseconds', history_baseline_at) from public.get_probable_end_verification_guard_v1(
        '20000000-0000-4000-8000-0000000000a1')), 99)),
    'BLOCKED', 'wrong guard version cannot register');
select is((select decision from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a9',
    '40000000-0000-4000-8000-0000000000a9', 1, 'alabarin-probable-end-v1',
    transaction_timestamp() - interval '1 day', 0)),
    'BLOCKED', 'wrong history baseline cannot register');
select throws_ok($$select * from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a1',
    '40000000-0000-4000-8000-0000000000a9', 1, 'alabarin-probable-end-v1',
    (select date_trunc('milliseconds', history_baseline_at)
     from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-0000000000a1')), 0)$$,
    '23505', 'Confirmation window identity conflict', 'conflicting identity cannot reset registered window');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000a2';
select throws_ok($$select * from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a9',
    '40000000-0000-4000-8000-0000000000a9', 1, 'alabarin-probable-end-v1', now(), 0)$$,
    '42501', 'Journey is not available to this caller', 'wrong owner cannot register');
select throws_ok($$select * from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-000000000099', '30000000-0000-4000-8000-0000000000a9',
    '40000000-0000-4000-8000-0000000000a9', 1, 'alabarin-probable-end-v1', now(), 0)$$,
    '42501', 'Journey is not available to this caller', 'absent registration Journey is nondisclosing');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000a1';
reset role;

-- Older test-only window is seeded as a server fixture; production registration
-- never accepts a caller-provided registered_at or not_before_at.
insert into private.probable_end_confirmation_windows (
    presentation_episode_id, journey_id, owner_id, candidate_episode_id, detector_generation,
    policy_version, history_baseline_at, guard_version, registered_at, not_before_at)
select '30000000-0000-4000-8000-0000000000a2',
    '20000000-0000-4000-8000-0000000000a2', '10000000-0000-4000-8000-0000000000a1',
    '40000000-0000-4000-8000-0000000000a2', 2, 'alabarin-probable-end-v1',
    date_trunc('milliseconds', verification_guard_baseline_at), verification_guard_version,
    date_trunc('milliseconds', now() - interval '31 minutes'),
    date_trunc('milliseconds', now() - interval '1 minute')
from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-0000000000a2';
insert into private.probable_end_confirmation_windows (
    presentation_episode_id, journey_id, owner_id, candidate_episode_id, detector_generation,
    policy_version, history_baseline_at, guard_version, registered_at, not_before_at)
select '30000000-0000-4000-8000-0000000000a3',
    '20000000-0000-4000-8000-0000000000a3', '10000000-0000-4000-8000-0000000000a1',
    '40000000-0000-4000-8000-0000000000a3', 3, 'alabarin-probable-end-v1',
    date_trunc('milliseconds', verification_guard_baseline_at), verification_guard_version,
    date_trunc('milliseconds', now() - interval '31 minutes'),
    date_trunc('milliseconds', now() - interval '1 minute')
from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-0000000000a3';
-- Deliberately inconsistent privileged fixture: finalization must independently
-- reject a wrong epoch, even with a fresh phase and unchanged version.
insert into private.probable_end_confirmation_windows (
    presentation_episode_id, journey_id, owner_id, candidate_episode_id, detector_generation,
    policy_version, history_baseline_at, guard_version, registered_at, not_before_at)
select '30000000-0000-4000-8000-0000000000a8',
    '20000000-0000-4000-8000-0000000000a3', '10000000-0000-4000-8000-0000000000a1',
    '40000000-0000-4000-8000-0000000000a8', 3, 'alabarin-probable-end-v1',
    date_trunc('milliseconds', verification_guard_baseline_at) - interval '1 second', verification_guard_version,
    date_trunc('milliseconds', now() - interval '31 minutes'),
    date_trunc('milliseconds', now() - interval '1 minute')
from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-0000000000a3';
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a8')),
    'BLOCKED', 'final guard independently rejects baseline mismatch');
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a2', '30000000-0000-4000-8000-0000000000a2')),
    'AUTO_ENDED', 'eligible finalization commits distinct terminal state');
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a2', '30000000-0000-4000-8000-0000000000a2')),
    'AUTO_ENDED', 'lost acknowledgement retry returns same outcome');
select is((select status from public.journeys where id = '20000000-0000-4000-8000-0000000000a2'),
    'AUTO_ENDED', 'AUTO_ENDED is not COMPLETED');
select ok((select completed_at is null and ended_at is not null from public.journeys
    where id = '20000000-0000-4000-8000-0000000000a2'), 'automatic terminal has no Traveller completion timestamp');
select is((select count(*)::integer from public.journey_monitoring_events
    where journey_id = '20000000-0000-4000-8000-0000000000a2'
      and event_type = 'MONITORING_CLOSED' and reason_code = 'PROBABLE_END_AUTO_ENDED'), 1,
    'monitoring closes once with automatic reason');
select throws_ok($$update public.journeys set status = 'ACTIVE', ended_at = null
    where id = '20000000-0000-4000-8000-0000000000a2'$$,
    'JT001', 'Terminal Journey state cannot be rewritten', 'stale ACTIVE cannot reopen AUTO_ENDED');
select throws_ok($$update public.journeys set status = 'COMPLETED', ended_at = null, completed_at = now()
    where id = '20000000-0000-4000-8000-0000000000a2'$$,
    'JT001', 'Terminal Journey state cannot be rewritten', 'stale COMPLETED cannot replace AUTO_ENDED');
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '301 seconds',
    last_authenticated_device_evidence_at = now() - interval '301 seconds',
    last_authenticated_device_evidence_received_at = now() - interval '301 seconds'
where journey_id = '20000000-0000-4000-8000-0000000000a3';
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a3')),
    'BLOCKED', 'stale evidence blocks without watchdog');
select is((select decision from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a9',
    '40000000-0000-4000-8000-0000000000a9', 1, 'alabarin-probable-end-v1',
    (select date_trunc('milliseconds', history_baseline_at)
     from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-0000000000a3')), 0)),
    'BLOCKED', 'stale guard cannot register a window');
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now(),
    last_authenticated_device_evidence_at = now(),
    last_authenticated_device_evidence_received_at = now()
where journey_id = '20000000-0000-4000-8000-0000000000a3';
update public.journey_monitoring_state set phase = 'VERIFYING', verifying_started_at = now()
where journey_id = '20000000-0000-4000-8000-0000000000a3';
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a3')),
    'BLOCKED', 'VERIFYING blocks finalization');
reset role;
update public.journey_monitoring_state set phase = 'EVIDENCE_FRESH', verifying_started_at = null
where journey_id = '20000000-0000-4000-8000-0000000000a3';
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a3')),
    'BLOCKED', 'resolved phase does not erase higher version');
reset role;
insert into public.journey_monitoring_events (journey_id, event_type, new_phase, reason_code)
values ('20000000-0000-4000-8000-0000000000a3', 'VERIFYING_STARTED', 'VERIFYING', 'TEST');
select public.open_verification_case('20000000-0000-4000-8000-0000000000a3',
    '10000000-0000-4000-8000-0000000000a1',
    (select id from public.journey_monitoring_events
     where journey_id = '20000000-0000-4000-8000-0000000000a3'
       and event_type = 'VERIFYING_STARTED' order by id desc limit 1),
    now(), now());
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a3')),
    'BLOCKED', 'OPEN case blocks finalization');
reset role;
select public.resolve_open_verification_case('20000000-0000-4000-8000-0000000000a3',
    'DEVICE_CONTACT_RESTORED', now());
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a3')),
    'BLOCKED', 'resolved case leaves old window version invalid');
reset role;
update public.journeys set status = 'COMPLETED', completed_at = now()
where id = '20000000-0000-4000-8000-0000000000a1';
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a1')),
    'BLOCKED', 'COMPLETED first blocks automatic end');
reset role;
update public.journeys set status = 'CANCELLED', ended_at = now()
where id = '20000000-0000-4000-8000-0000000000a3';
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a3', '30000000-0000-4000-8000-0000000000a3')),
    'BLOCKED', 'CANCELLED first blocks automatic end');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000a2';
select throws_ok($$select * from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a1')$$,
    '42501', 'Journey is not available to this caller', 'wrong owner cannot finalize');
select throws_ok($$select * from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-000000000099', '30000000-0000-4000-8000-0000000000a1')$$,
    '42501', 'Journey is not available to this caller', 'nonexistent Journey has same error');
reset role;
update auth.users set is_anonymous = true
where id = '10000000-0000-4000-8000-0000000000a2';
set local role authenticated;
select throws_ok($$select * from public.finalize_probable_end_auto_end_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a1')$$,
    '42501', 'Non-anonymous Traveller identity required', 'anonymous authenticated account cannot finalize');
select throws_ok($$select * from public.register_probable_end_confirmation_window_v1(
    '20000000-0000-4000-8000-0000000000a1', '30000000-0000-4000-8000-0000000000a9',
    '40000000-0000-4000-8000-0000000000a9', 1, 'alabarin-probable-end-v1', now(), 0)$$,
    '42501', 'Non-anonymous Traveller identity required', 'anonymous authenticated account cannot register');
reset role;
select * from finish();
rollback;

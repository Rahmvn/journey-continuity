begin;
select plan(37);

select has_table('public', 'journey_monitoring_state', 'monitoring state table exists');
select has_table('public', 'journey_heartbeats', 'heartbeat table exists');
select has_table('public', 'journey_monitoring_events', 'monitoring event table exists');
select is((select relrowsecurity from pg_class where oid = 'public.journey_monitoring_state'::regclass), true, 'state RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.journey_heartbeats'::regclass), true, 'heartbeat RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.journey_monitoring_events'::regclass), true, 'event RLS enabled');
select ok(not has_table_privilege('authenticated', 'public.journey_monitoring_state', 'INSERT,UPDATE,DELETE'), 'client cannot mutate monitoring state');
select ok(not has_table_privilege('authenticated', 'public.journey_monitoring_events', 'INSERT,UPDATE,DELETE'), 'client cannot create transition events');
select is(
    (
        select count(*)
        from pg_proc as p
        join pg_namespace as n on n.oid = p.pronamespace
        where n.nspname = 'public' and p.proname = 'record_journey_heartbeat'
    ),
    1::bigint,
    'record_journey_heartbeat has no unexpected overload'
);

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at)
values (
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'm4-owner@example.invalid', now(), now()
);
insert into public.journeys (
    id, owner_id, destination, expected_arrival_at, started_at, status
) values (
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    'Test', now() + interval '1 hour', now(), 'ACTIVE'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
set local request.jwt.claim.role = 'authenticated';

select results_eq(
    $$select phase from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000001', 1, now(), 29, true, 'WIFI', 0
    )$$,
    array['EVIDENCE_FRESH'::text],
    'first heartbeat initializes fresh monitoring'
);
select is((select count(*) from public.journey_heartbeats), 1::bigint, 'first heartbeat is stored');
select is((select battery_percent from public.journey_heartbeats where sequence = 1), 29, 'heartbeat stores supplied battery percentage');
select is((select charging from public.journey_heartbeats where sequence = 1), true, 'heartbeat stores supplied charging value');
select is((select count(*) from public.journey_monitoring_events where event_type = 'MONITORING_STARTED'), 1::bigint, 'monitoring started once');

select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000001', 1, now(), 80, false, 'WIFI', 0
    )$$,
    'duplicate heartbeat is harmless'
);
select is((select count(*) from public.journey_heartbeats), 1::bigint, 'duplicate heartbeat creates no row');
select is((select count(*) from public.journey_monitoring_events where event_type = 'MONITORING_STARTED'), 1::bigint, 'duplicate creates no start event');

select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000001', 2, now(), 79, false, 'CELLULAR', 3
    )$$,
    'next heartbeat is accepted'
);
select is((select latest_heartbeat_sequence from public.journey_monitoring_state), 2::bigint, 'latest heartbeat sequence advances');
select ok((select last_cloud_contact_at is not null from public.journey_monitoring_state), 'heartbeat updates server contact time');

reset role;
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'watchdog evaluates before threshold');
select is((select phase from public.journey_monitoring_state), 'EVIDENCE_FRESH', 'fresh state remains before threshold');

update public.journey_monitoring_state
set last_cloud_contact_at = now() - interval '6 minutes',
    last_authenticated_device_evidence_at = now() - interval '6 minutes',
    last_authenticated_device_evidence_received_at = now() - interval '6 minutes';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'watchdog evaluates stale contact');
select is((select phase from public.journey_monitoring_state), 'VERIFYING', 'stale fresh contact enters verifying');
select is((select count(*) from public.journey_monitoring_events where event_type = 'VERIFYING_STARTED'), 1::bigint, 'verifying event is recorded once');
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'overlapping or repeated evaluation is harmless');
select is((select count(*) from public.journey_monitoring_events where event_type = 'VERIFYING_STARTED'), 1::bigint, 'repeated evaluation creates no duplicate event');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select results_eq(
    $$select phase from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000001', 3, now(), 78, false, 'WIFI', 3
    )$$,
    array['EVIDENCE_FRESH'::text],
    'fresh heartbeat restores contact'
);
select is((select count(*) from public.journey_monitoring_events where event_type = 'CONTACT_RESTORED'), 1::bigint, 'contact restored event is recorded once');

reset role;
update public.journey_monitoring_state
set last_cloud_contact_at = now() - interval '6 minutes',
    last_authenticated_device_evidence_at = now() - interval '6 minutes',
    last_authenticated_device_evidence_received_at = now() - interval '6 minutes';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'watchdog re-enters verifying after another silence');
insert into public.telemetry_observations (
    journey_id, sequence, event_time, latitude, longitude, accuracy_meters,
    battery_percent, charging, connectivity_state
) values (
    '20000000-0000-0000-0000-000000000001', 1, now() - interval '10 minutes',
    9.0, 7.0, 10.0, 77, false, 'NONE'
);
select is((select phase from public.journey_monitoring_state), 'VERIFYING', 'delayed telemetry does not restore freshness');

update public.journeys
set status = 'COMPLETED', completed_at = now()
where id = '20000000-0000-0000-0000-000000000001';
select is((select phase from public.journey_monitoring_state), 'CLOSED', 'completed Journey closes monitoring');
select is((select count(*) from public.journey_monitoring_events where event_type = 'MONITORING_CLOSED'), 1::bigint, 'monitoring closed event is recorded once');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select throws_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000001', 4, now(), 76, false, 'WIFI', 3
    )$$,
    '22023',
    'Heartbeat requires an ACTIVE Journey',
    'completed Journey rejects a delayed heartbeat'
);
reset role;
insert into public.telemetry_observations (
    journey_id, sequence, event_time, latitude, longitude, accuracy_meters,
    battery_percent, charging, connectivity_state
) values (
    '20000000-0000-0000-0000-000000000001', 2, now() - interval '9 minutes',
    9.0, 7.0, 11.0, 76, false, 'NONE'
);
select is((select phase from public.journey_monitoring_state), 'CLOSED', 'delayed telemetry cannot reopen closed monitoring');
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'closed monitoring remains idempotent');
select is((select count(*) from public.journey_monitoring_events where event_type = 'MONITORING_CLOSED'), 1::bigint, 'closed monitoring creates no duplicate event');

select * from finish();
rollback;

begin;
select plan(59);

select has_table('public', 'trusted_contact_invitations', 'invitation table exists');
select has_table('public', 'trusted_contact_relationships', 'relationship table exists');
select has_table('public', 'journey_trusted_contact_access', 'Journey access table exists');
select has_table('public', 'verification_cases', 'verification case table exists');
select has_table('public', 'verification_case_location_points', 'case location snapshot table exists');
select has_table('public', 'verification_case_reports', 'case reports table exists');
select has_table('public', 'trusted_contact_access_events', 'access audit table exists');

select is((select relrowsecurity from pg_class where oid = 'public.trusted_contact_invitations'::regclass), true, 'invitation RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.trusted_contact_relationships'::regclass), true, 'relationship RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.journey_trusted_contact_access'::regclass), true, 'Journey access RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.verification_cases'::regclass), true, 'case RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.verification_case_location_points'::regclass), true, 'case locations RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.verification_case_reports'::regclass), true, 'reports RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.trusted_contact_access_events'::regclass), true, 'audit RLS enabled');
select ok(has_table_privilege('authenticated', 'public.telemetry_observations', 'SELECT'), 'existing device sync grant remains explicitly RLS-gated');
select ok(not has_table_privilege('authenticated', 'public.verification_cases', 'SELECT,INSERT,UPDATE,DELETE'), 'case table has no browser grants');
select ok(not has_table_privilege('anon', 'public.trusted_contact_invitations', 'SELECT'), 'anonymous user cannot inspect invitations');

insert into auth.users (id, instance_id, aud, role, email, email_confirmed_at, created_at, updated_at)
values
    ('10000000-0000-0000-0000-000000000051', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'traveller-m5@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000052', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'contact-m5@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000053', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'intruder-m5@example.invalid', now(), now(), now());

create temporary table test_invitation as
select null::uuid as invitation_id, null::text as invitation_token with no data;
grant select, insert on test_invitation to authenticated;
create temporary table test_state (case_id uuid, relationship_id uuid);
insert into test_state values (null, null);
grant select on test_state to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
set local request.jwt.claim.role = 'authenticated';

insert into test_invitation
select invitation_id, invitation_token
from public.create_trusted_contact_invitation('Ada Contact', ' Contact-M5@Example.Invalid ');

reset role;
select is((select invited_email_normalized from public.trusted_contact_invitations), 'contact-m5@example.invalid', 'invited email is normalized server-side');
select is((select status from public.trusted_contact_invitations), 'PENDING', 'invitation begins pending');
select isnt((select encode(token_hash, 'hex') from public.trusted_contact_invitations), (select invitation_token from test_invitation), 'raw token is not stored');
select ok((select expires_at between now() + interval '6 days 23 hours' and now() + interval '7 days 1 hour' from public.trusted_contact_invitations), 'invitation uses centralized seven-day development expiry');

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-0000-0000-000000000051', '10000000-0000-0000-0000-000000000051', 'Abuja', now() + interval '2 hours', now(), 'ACTIVE');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000053';
select is((select count(*) from public.telemetry_observations), 0::bigint, 'unrelated authenticated identity cannot directly read raw telemetry');
select throws_ok(
    format('select * from public.accept_trusted_contact_invitation(%L)', (select invitation_token from test_invitation)),
    '42501', 'Authenticated email does not match this invitation',
    'different verified email cannot accept invitation'
);

set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
select lives_ok(
    format('select * from public.accept_trusted_contact_invitation(%L)', (select invitation_token from test_invitation)),
    'matching verified email accepts invitation'
);
reset role;
update test_state set relationship_id = (select id from public.trusted_contact_relationships);
select is((select status from public.trusted_contact_invitations), 'ACCEPTED', 'invitation is consumed');
select is((select count(*) from public.trusted_contact_relationships where status = 'ACCEPTED'), 1::bigint, 'accepted relationship persists once');
select is((select count(*) from public.journey_trusted_contact_access), 1::bigint, 'acceptance provisions current active Journey');
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
select throws_ok(
    format('select * from public.accept_trusted_contact_invitation(%L)', (select invitation_token from test_invitation)),
    '22023', 'Invitation is not pending', 'one-time token cannot be reused'
);

select is(
    (public.get_trusted_journey_snapshot('20000000-0000-0000-0000-000000000051')->>'whereabouts'),
    'NOT_DISCLOSED',
    'healthy Journey response does not disclose location'
);
select ok(
    not (public.get_trusted_journey_snapshot('20000000-0000-0000-0000-000000000051') ? 'latitude'),
    'healthy Journey response omits coordinate fields'
);
reset role;
select is((select count(*) from public.trusted_contact_access_events where event_type = 'JOURNEY_VIEWED'), 1::bigint, 'Journey view audit is deduplicated');

insert into public.telemetry_observations (
    journey_id, sequence, event_time, latitude, longitude, accuracy_meters,
    battery_percent, charging, connectivity_state
)
select '20000000-0000-0000-0000-000000000051', sequence, now() - (7 - sequence) * interval '1 minute',
       9.0 + sequence / 1000.0, 7.0 + sequence / 1000.0, 10.0, 60 + sequence, false, 'CELLULAR'
from generate_series(1, 7) as sequence;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000051', 1, now(), 67, false, 'CELLULAR', 7
    )$$,
    'traveller initializes cloud heartbeat'
);
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '6 minutes';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'watchdog opens verification case');
select is((select count(*) from public.verification_cases where status = 'OPEN'), 1::bigint, 'one open case exists');
select is((select latest_known_telemetry_sequence from public.verification_cases), 7::bigint, 'case freezes latest cloud-known telemetry sequence');
select is((select count(*) from public.verification_case_location_points), 5::bigint, 'case freezes exactly five recent locations');
select results_eq(
    $$select telemetry_sequence from public.verification_case_location_points order by position$$,
    $$values (3::bigint), (4::bigint), (5::bigint), (6::bigint), (7::bigint)$$,
    'recent location snapshot is chronological'
);
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'overlapping watchdog evaluation remains idempotent');
select is((select count(*) from public.verification_cases), 1::bigint, 'repeated watchdog creates no duplicate case');
update test_state set case_id = (select id from public.verification_cases);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000053';
select throws_ok(
    format('select public.get_verification_case(%L)', (select case_id from test_state)),
    '42501', 'Verification case is not available to this caller',
    'knowing case UUID does not authorize an unrelated user'
);

set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
select is(
    (public.get_verification_case((select case_id from test_state))->'last_verified_device_location'->>'provenance'),
    'device_verified',
    'authorized case view labels device evidence provenance'
);
reset role;
select is((select count(*) from public.trusted_contact_access_events where event_type = 'PRECISE_LOCATION_REVEALED'), 1::bigint, 'precise-location audit is deduplicated');
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
select lives_ok(
    format(
        'select public.submit_verification_case_report(%L, %L, now(), %L)',
        (select case_id from test_state), 'SPOKE_WITH_TRAVELLER', 'Vehicle has a tyre problem.'
    ),
    'authorized contact can append a report'
);
reset role;
select is((select provenance from public.verification_case_reports), 'trusted_contact_reported', 'report provenance is fixed');
select is((select count(*) from public.trusted_contact_access_events where event_type = 'REPORT_SUBMITTED'), 1::bigint, 'each report is audited');
select is((select phase from public.journey_monitoring_state), 'VERIFYING', 'report does not alter monitoring phase');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000051', 2, now(), 66, false, 'WIFI', 7
    )$$,
    'fresh heartbeat resolves open case'
);
reset role;
select is((select resolution_reason from public.verification_cases), 'DEVICE_CONTACT_RESTORED', 'case resolution records contact restoration');
select ok((select sensitive_access_expires_at > now() from public.verification_cases), 'resolved case receives temporary sensitive-access expiry');
update public.verification_cases set sensitive_access_expires_at = now() - interval '1 second';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
select throws_ok(
    $$select public.get_verification_case((select case_id from test_state))$$,
    '42501', 'Sensitive verification-case access has expired',
    'expired sensitive case details are denied'
);
select throws_ok(
    $$select public.submit_verification_case_report((select case_id from test_state), 'OTHER', null, 'Late report')$$,
    '42501', 'Sensitive verification-case access has expired',
    'expired case also rejects new trusted-contact reports'
);
reset role;
update public.verification_cases set sensitive_access_expires_at = now() + interval '23 hours';

update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '6 minutes';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'a later silence opens a distinct second case');
select is((select count(*) from public.verification_cases where status = 'OPEN'), 1::bigint, 'only the second case is open');
update public.journeys
set status = 'COMPLETED', completed_at = now()
where id = '20000000-0000-0000-0000-000000000051';
select is(
    (select resolution_reason from public.verification_cases order by opened_from_monitoring_event_id desc limit 1),
    'JOURNEY_COMPLETED',
    'Journey completion resolves the current open case'
);
select is(
    (select count(*) from public.journey_monitoring_events where event_type = 'CONTACT_RESTORED'),
    1::bigint,
    'completion does not fabricate another contact-restored event'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
select lives_ok(
    $$select public.revoke_trusted_contact((select relationship_id from test_state))$$,
    'traveller can revoke accepted relationship'
);
reset role;
select is((select status from public.trusted_contact_relationships), 'REVOKED', 'relationship is marked revoked');
select ok((select revoked_at is not null from public.journey_trusted_contact_access), 'current Journey access is promptly revoked');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
select throws_ok(
    $$select public.get_trusted_journey_snapshot('20000000-0000-0000-0000-000000000051')$$,
    '42501', 'Journey is not available to this caller',
    'revoked contact loses viewer access'
);

select * from finish();
rollback;

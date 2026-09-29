begin;
select no_plan();

select has_table('public', 'traveller_profiles', 'owner-controlled traveller profile exists');
select is((select relrowsecurity from pg_class where oid = 'public.traveller_profiles'::regclass), true,
    'traveller profile has RLS enabled');
select ok(not has_table_privilege('authenticated', 'public.traveller_profiles', 'SELECT,INSERT,UPDATE,DELETE'),
    'authenticated clients have no direct profile table access');
select ok(not has_function_privilege('anon', 'public.set_traveller_profile_identity(text,text)', 'EXECUTE'),
    'anonymous callers cannot set Traveller identity');
select ok(not has_function_privilege('anon', 'public.check_traveller_handle_availability(text)', 'EXECUTE'),
    'public anon role cannot check handle availability');
select ok(has_function_privilege('authenticated', 'public.check_traveller_handle_availability(text)', 'EXECUTE'),
    'authenticated role may check handle availability');
select is(pg_get_function_result('public.check_traveller_handle_availability(text)'::regprocedure),
    'boolean', 'availability RPC returns only a boolean, with no profile identity fields');
select ok(not has_function_privilege('anon', 'public.list_trusted_journey_statuses_v1()', 'EXECUTE'),
    'anonymous callers cannot list trusted Journeys');
select ok(not has_function_privilege('anon', 'public.get_trusted_journey_status_v1(uuid)', 'EXECUTE'),
    'anonymous callers cannot read trusted status');

insert into auth.users (id, instance_id, aud, role, email, email_confirmed_at, created_at, updated_at)
values
    ('10000000-0000-4000-8000-000000000071', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'owner-a@example.invalid', now(), now(), now()),
    ('10000000-0000-4000-8000-000000000072', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'owner-b@example.invalid', now(), now(), now()),
    ('10000000-0000-4000-8000-000000000073', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'contact@example.invalid', now(), now(), now()),
    ('10000000-0000-4000-8000-000000000074', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'stranger@example.invalid', now(), now(), now()),
    ('10000000-0000-4000-8000-000000000075', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'owner-no-profile@example.invalid', now(), now(), now());

insert into auth.users
    (id, instance_id, aud, role, email, email_confirmed_at, is_anonymous, created_at, updated_at)
values
    ('10000000-0000-4000-8000-000000000076', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', null, null, true, now(), now()),
    ('10000000-0000-4000-8000-000000000077', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'unverified@example.invalid', null, false, now(), now());

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000076';
select is(public.check_traveller_handle_availability('  @New_Handle1  '), true,
    'authenticated anonymous user can check a free normalized handle');
select throws_ok($$select public.set_traveller_profile_identity('Anonymous User', 'new_handle1')$$,
    '42501', 'A verified email identity is required',
    'authenticated anonymous user cannot claim a handle');
select throws_ok($$select public.check_traveller_handle_availability('@x')$$,
    '22023', 'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'availability check rejects too-short handle');
select throws_ok($$select public.check_traveller_handle_availability('bad_')$$,
    '22023', 'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'availability check rejects invalid trailing underscore');
select throws_ok($$select public.check_traveller_handle_availability(U&'r\00E9ne')$$,
    '22023', 'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'availability check rejects non-ASCII handle');
select throws_ok($$select public.check_traveller_handle_availability(null)$$,
    '22023', 'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'availability check rejects missing handle');
reset role;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000077';
select is(public.check_traveller_handle_availability('new_handle1'), true,
    'unverified authenticated user may check availability');
select throws_ok($$select public.set_traveller_profile_identity('Unverified User', 'new_handle1')$$,
    '42501', 'A verified email identity is required',
    'email without confirmation cannot claim a handle');
reset role;
select is((select count(*) from public.traveller_profiles), 0::bigint,
    'anonymous and unverified profile writes created no rows');

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000071';
select is(public.set_traveller_profile_identity('  Abdulrahman Saheed  ', '  @Rahmvn  '),
    '{"full_name": "Abdulrahman Saheed", "handle": "rahmvn"}'::jsonb,
    'verified-email owner creates trimmed full name and normalized handle');
select is(public.check_traveller_handle_availability('@RAHMVN'), true,
    'owner sees their own normalized handle as available for Edit identity');
select is(public.set_traveller_profile_identity('Abdulrahman Saheed Jr', 'RAHMVN'),
    '{"full_name": "Abdulrahman Saheed Jr", "handle": "rahmvn"}'::jsonb,
    'verified-email owner atomically updates name and normalizes handle');
select is(public.set_traveller_profile_identity('Abdulrahman Saheed', '@rahmvn'),
    '{"full_name": "Abdulrahman Saheed", "handle": "rahmvn"}'::jsonb,
    'owner may restore full name without changing normalized handle');
select throws_ok($$select public.set_traveller_profile_identity('   ', 'rahmvn')$$, '22023',
    'Traveller full name must be 1 to 100 visible characters without control characters',
    'blank full name is rejected');
select throws_ok($$select public.set_traveller_profile_identity(null, 'rahmvn')$$, '22023',
    'Traveller full name must be 1 to 100 visible characters without control characters',
    'full name is required');
select throws_ok($$select public.set_traveller_profile_identity(repeat('x', 101), 'rahmvn')$$, '22023',
    'Traveller full name must be 1 to 100 visible characters without control characters',
    'overlong full name is rejected');
select throws_ok($$select public.set_traveller_profile_identity(E'Bad\nName', 'rahmvn')$$, '22023',
    'Traveller full name must be 1 to 100 visible characters without control characters',
    'control characters in full name are rejected');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', '')$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'blank handle is rejected');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', null)$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'handle is required');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', '@x')$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'short handle is rejected');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', '_bad')$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'handle must start with a letter');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', 'bad_')$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'handle must end with a letter or digit');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', 'bad.name')$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'handle rejects punctuation outside the allowed syntax');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', U&'r\00E9ne')$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'handle rejects non-ASCII letters regardless of database collation');
select throws_ok($$select public.set_traveller_profile_identity('Valid Name', repeat('a', 25))$$, '22023',
    'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'overlong handle is rejected');
reset role;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000072';
select is(public.check_traveller_handle_availability('@RAHMVN'), false,
    'leading @ and uppercase normalize to an occupied handle');
select is(public.check_traveller_handle_availability('rahmvn'), false,
    'another owner sees occupied handle as unavailable');
select throws_ok($$select public.set_traveller_profile_identity('Abdulrahman Saheed', '@RAHMVN')$$,
    '23505', 'JOURNEY handle is unavailable',
    'normalized handle uniqueness rejects another owner');
select is(public.set_traveller_profile_identity('Abdulrahman Saheed', 'rahmvn2'),
    '{"full_name": "Abdulrahman Saheed", "handle": "rahmvn2"}'::jsonb,
    'two Travellers may share a full name with distinct handles');
select is(public.set_traveller_profile_identity('Abdulrahman Saheed', '@RAHMVN_2'),
    '{"full_name": "Abdulrahman Saheed", "handle": "rahmvn_2"}'::jsonb,
    'second owner can update only their own handle');
select throws_ok(
    $$update public.traveller_profiles set full_name = 'Stolen', handle = 'stolen'
      where owner_id = '10000000-0000-4000-8000-000000000071'$$,
    '42501', 'permission denied for table traveller_profiles',
    'another authenticated user cannot update the first owner profile directly');
reset role;
select is((select full_name from public.traveller_profiles
           where owner_id = '10000000-0000-4000-8000-000000000071'), 'Abdulrahman Saheed',
    'another authenticated owner did not alter first owner full name');
select is((select handle from public.traveller_profiles
           where owner_id = '10000000-0000-4000-8000-000000000071'), 'rahmvn',
    'another authenticated owner did not alter first owner handle');
select is((select count(distinct full_name) from public.traveller_profiles), 1::bigint,
    'multiple Traveller profiles may use the same full name');

-- Both callers can see a free handle. Neither check reserves it; the first
-- verified owner to save wins, and the other receives the bounded conflict.
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000072';
select is(public.check_traveller_handle_availability('@Race_Handle1'), true,
    'second owner sees a free handle before a competing save');
reset role;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000071';
select is(public.check_traveller_handle_availability('RACE_HANDLE1'), true,
    'first owner also sees the handle free before saving');
select is(public.set_traveller_profile_identity('Abdulrahman Saheed', 'race_handle1'),
    '{"full_name": "Abdulrahman Saheed", "handle": "race_handle1"}'::jsonb,
    'first owner claims the handle through the authoritative unique constraint');
reset role;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000072';
select throws_ok($$select public.set_traveller_profile_identity('Abdulrahman Saheed', '@RACE_HANDLE1')$$,
    '23505', 'JOURNEY handle is unavailable',
    'second save loses the race despite its earlier successful check');
reset role;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000071';
select is(public.set_traveller_profile_identity('Abdulrahman Saheed', 'rahmvn'),
    '{"full_name": "Abdulrahman Saheed", "handle": "rahmvn"}'::jsonb,
    'first owner restores fixture handle for trusted projections');
reset role;
select is((select handle from public.traveller_profiles
           where owner_id = '10000000-0000-4000-8000-000000000072'), 'rahmvn_2',
    'losing the race does not change the second owner profile');

insert into public.trusted_contact_relationships
    (id, traveller_user_id, contact_user_id, display_name, contact_email_normalized)
values
    ('30000000-0000-4000-8000-000000000071', '10000000-0000-4000-8000-000000000071',
     '10000000-0000-4000-8000-000000000073', 'Amina Contact', 'contact@example.invalid'),
    ('30000000-0000-4000-8000-000000000072', '10000000-0000-4000-8000-000000000072',
     '10000000-0000-4000-8000-000000000073', 'Bisi Contact', 'contact@example.invalid'),
    ('30000000-0000-4000-8000-000000000073', '10000000-0000-4000-8000-000000000075',
     '10000000-0000-4000-8000-000000000073', 'Unnamed Contact', 'contact@example.invalid');

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values
    ('20000000-0000-4000-8000-000000000071', '10000000-0000-4000-8000-000000000071',
     'Ikeja', now() + interval '2 hours', now() - interval '2 hours', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000072', '10000000-0000-4000-8000-000000000071',
     'Yaba', now() + interval '2 hours', now() - interval '1 hour', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000073', '10000000-0000-4000-8000-000000000072',
     'Abuja', now() + interval '2 hours', now() - interval '3 hours', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000074', '10000000-0000-4000-8000-000000000075',
     'Kaduna', now() + interval '2 hours', now() - interval '4 hours', 'ACTIVE');

insert into public.journey_monitoring_state
    (journey_id, owner_id, phase, last_cloud_contact_at, latest_heartbeat_sequence)
select id, owner_id, 'EVIDENCE_FRESH', now() - interval '10 minutes', 1
from public.journeys where id in (
    '20000000-0000-4000-8000-000000000071',
    '20000000-0000-4000-8000-000000000072',
    '20000000-0000-4000-8000-000000000073'
);

insert into public.telemetry_observations
    (journey_id, sequence, event_time, latitude, longitude, accuracy_meters, connectivity_state)
values ('20000000-0000-4000-8000-000000000071', 1, now() - interval '12 minutes',
        6.45, 3.39, 12, 'CELLULAR');

-- Expected values are captured by the fixture owner. The trusted caller must
-- compare against this fixture, since RLS correctly hides owner-only tables.
create temporary table projection_expected as
select s.journey_id, s.last_cloud_contact_at,
       s.last_authenticated_device_evidence_at,
       s.last_authenticated_device_evidence_received_at, j.completed_at
from public.journey_monitoring_state as s
join public.journeys as j on j.id = s.journey_id
where s.journey_id = '20000000-0000-4000-8000-000000000071';
grant select on projection_expected to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000074';
select is((select count(*) from public.list_trusted_journey_statuses_v1()), 0::bigint,
    'unrelated authenticated user receives no Journey projection');
select throws_ok(
    $$select public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')$$,
    '42501', 'Journey is not available to this caller', 'guessed Journey UUID does not authorize status');
reset role;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000073';
select is((select count(*) from public.list_trusted_journey_statuses_v1()), 4::bigint,
    'one contact can see multiple granted Journeys across Travellers');
select is((select traveller_full_name from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000071'), 'Abdulrahman Saheed',
    'authorized list returns owner-controlled Traveller full name');
select is((select traveller_handle from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000071'), 'rahmvn',
    'authorized list returns first Traveller normalized handle');
select is((select traveller_full_name from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000073'), 'Abdulrahman Saheed',
    'second authorized Traveller may share the full name');
select is((select traveller_handle from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000073'), 'rahmvn_2',
    'second Traveller is distinguishable by unique handle');
select is((select traveller_full_name from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000074'), null::text,
    'missing profile has no fabricated name or email fallback');
select is((select traveller_handle from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000074'), null::text,
    'missing profile has no fabricated handle');
select is((select monitoring_phase from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000074'), null::text,
    'missing monitoring state remains unknown rather than invented CLOSED');
select is(public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'traveller_full_name',
    'Abdulrahman Saheed', 'per-Journey status returns authorized Traveller full name');
select is(public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'traveller_handle',
    'rahmvn', 'per-Journey status returns authorized Traveller handle');
select ok(not (public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071') ? 'email'),
    'per-Journey status has no email property');
select ok(position('owner-a@example.invalid' in
    public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')::text) = 0,
    'per-Journey status does not expose Traveller email value');
select ok(not (to_jsonb((select p from public.list_trusted_journey_statuses_v1() as p
                         where journey_id = '20000000-0000-4000-8000-000000000071')) ? 'email'),
    'list projection has no email property');
select ok(not (public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071') ? 'latitude')
          and not (public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071') ? 'longitude')
          and not (public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071') ? 'accuracy_meters'),
    'healthy per-Journey status has no precise location fields');
select ok(not (to_jsonb((select p from public.list_trusted_journey_statuses_v1() as p
                         where journey_id = '20000000-0000-4000-8000-000000000071')) ? 'latitude'),
    'healthy list projection has no precise location fields');
select is(public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_authenticated_device_evidence_transport',
    'CLOUD_HEARTBEAT', 'cloud contact records authenticated internet source');
select is((public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_authenticated_device_evidence_at')::timestamptz,
    (select last_cloud_contact_at from projection_expected
     where journey_id = '20000000-0000-4000-8000-000000000071'),
    'cloud authenticated evidence timestamp is returned');
select is(public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'completed_at',
    null::text, 'active Journey has no completion timestamp');
reset role;

update public.journey_monitoring_state
set last_authenticated_device_evidence_at = now() - interval '1 minute',
    last_authenticated_device_evidence_received_at = now() - interval '30 seconds',
    last_authenticated_device_evidence_transport = 'FALLBACK_SMS'
where journey_id = '20000000-0000-4000-8000-000000000071';
update projection_expected as e
set last_authenticated_device_evidence_at = s.last_authenticated_device_evidence_at,
    last_authenticated_device_evidence_received_at = s.last_authenticated_device_evidence_received_at
from public.journey_monitoring_state as s where s.journey_id = e.journey_id;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000073';
select is(public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_authenticated_device_evidence_transport',
    'FALLBACK_SMS', 'newer authenticated fallback transport is returned');
select is((public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_authenticated_device_evidence_at')::timestamptz,
    (select last_authenticated_device_evidence_at from projection_expected
     where journey_id = '20000000-0000-4000-8000-000000000071'),
    'fallback observation time is returned exactly');
select is((public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_authenticated_device_evidence_received_at')::timestamptz,
    (select last_authenticated_device_evidence_received_at from projection_expected
     where journey_id = '20000000-0000-4000-8000-000000000071'),
    'fallback server receive time remains separate');
select ok((public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_authenticated_device_evidence_at')::timestamptz
          > (public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_cloud_contact_at')::timestamptz,
    'fallback evidence may be newer than last cloud contact');
select is((public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'last_cloud_contact_at')::timestamptz,
    (select last_cloud_contact_at from projection_expected
     where journey_id = '20000000-0000-4000-8000-000000000071'),
    'fallback does not rewrite or imply restored internet contact');
select is((select last_authenticated_device_evidence_transport from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000071'),
    'FALLBACK_SMS', 'list and status agree on bounded fallback source');
reset role;

update public.journeys set status = 'COMPLETED', completed_at = now() - interval '15 seconds'
where id = '20000000-0000-4000-8000-000000000071';
update projection_expected as e set completed_at = j.completed_at
from public.journeys as j where j.id = e.journey_id;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000073';
select is((public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000071')->>'completed_at')::timestamptz,
    (select completed_at from projection_expected where journey_id = '20000000-0000-4000-8000-000000000071'),
    'status returns authoritative completion time');
select is((select completed_at from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000071'),
    (select completed_at from projection_expected where journey_id = '20000000-0000-4000-8000-000000000071'),
    'list returns the same authoritative completion time');
reset role;

-- Keep an existing human report in the fixture so the mutation check covers
-- both accidental insert and accidental update, not only an empty table.
insert into public.journey_monitoring_events
    (journey_id, event_type, occurred_at, previous_phase, new_phase, last_cloud_contact_at, reason_code)
values ('20000000-0000-4000-8000-000000000073', 'VERIFYING_STARTED', now(),
        'EVIDENCE_FRESH', 'VERIFYING', now() - interval '10 minutes', 'TEST_FIXTURE');
insert into public.verification_cases
    (id, journey_id, owner_id, opened_from_monitoring_event_id,
     journey_destination, journey_started_at, journey_expected_arrival_at)
select '40000000-0000-4000-8000-000000000073', j.id, j.owner_id, e.id,
       j.destination, j.started_at, j.expected_arrival_at
from public.journeys as j
join public.journey_monitoring_events as e on e.journey_id = j.id and e.event_type = 'VERIFYING_STARTED'
where j.id = '20000000-0000-4000-8000-000000000073';
insert into public.verification_case_reports
    (id, case_id, reporter_user_id, report_type, note)
values ('50000000-0000-4000-8000-000000000073',
        '40000000-0000-4000-8000-000000000073',
        '10000000-0000-4000-8000-000000000073', 'OTHER', 'Existing human report');

create temporary table projection_baseline as
select
    (select md5(coalesce(string_agg(to_jsonb(p)::text, ',' order by p.owner_id::text), ''))
     from public.traveller_profiles as p) as profiles_hash,
    (select md5(coalesce(string_agg(to_jsonb(j)::text, ',' order by j.id::text), ''))
     from public.journeys as j) as journeys_hash,
    (select md5(coalesce(string_agg(to_jsonb(s)::text, ',' order by s.journey_id::text), ''))
     from public.journey_monitoring_state as s) as monitoring_hash,
    (select md5(coalesce(string_agg(to_jsonb(t)::text, ',' order by t.journey_id::text, t.sequence), ''))
     from public.telemetry_observations as t) as telemetry_hash,
    (select md5(coalesce(string_agg(to_jsonb(c)::text, ',' order by c.id::text), ''))
     from public.verification_cases as c) as cases_hash,
    (select md5(coalesce(string_agg(to_jsonb(o)::text, ',' order by o.id::text), ''))
     from public.trusted_contact_notification_outbox as o) as notifications_hash,
    (select md5(coalesce(string_agg(to_jsonb(r)::text, ',' order by r.id::text), ''))
     from public.verification_case_reports as r) as reports_hash;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000073';
select is((select count(*) from public.list_trusted_journey_statuses_v1()), 4::bigint,
    'repeated authorized list call returns granted Journeys');
select ok(public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000073') is not null,
    'repeated authorized status call returns one Journey');
select is(public.check_traveller_handle_availability('@RAHMVN'), false,
    'read-only availability call runs alongside trusted projections');
reset role;

select is((select md5(coalesce(string_agg(to_jsonb(p)::text, ',' order by p.owner_id::text), ''))
           from public.traveller_profiles as p),
          (select profiles_hash from projection_baseline), 'availability check does not mutate profiles');
select is((select md5(coalesce(string_agg(to_jsonb(j)::text, ',' order by j.id::text), ''))
           from public.journeys as j),
          (select journeys_hash from projection_baseline), 'projection calls do not mutate Journey lifecycle');
select is((select md5(coalesce(string_agg(to_jsonb(s)::text, ',' order by s.journey_id::text), ''))
           from public.journey_monitoring_state as s),
          (select monitoring_hash from projection_baseline), 'projection calls do not mutate monitoring state');
select is((select md5(coalesce(string_agg(to_jsonb(t)::text, ',' order by t.journey_id::text, t.sequence), ''))
           from public.telemetry_observations as t),
          (select telemetry_hash from projection_baseline), 'projection calls do not mutate telemetry');
select is((select md5(coalesce(string_agg(to_jsonb(c)::text, ',' order by c.id::text), ''))
           from public.verification_cases as c),
          (select cases_hash from projection_baseline), 'projection calls do not mutate verification cases');
select is((select md5(coalesce(string_agg(to_jsonb(o)::text, ',' order by o.id::text), ''))
           from public.trusted_contact_notification_outbox as o),
          (select notifications_hash from projection_baseline), 'projection calls do not mutate notifications');
select is((select md5(coalesce(string_agg(to_jsonb(r)::text, ',' order by r.id::text), ''))
           from public.verification_case_reports as r),
          (select reports_hash from projection_baseline), 'projection calls do not create or update reports');

update public.journey_trusted_contact_access set revoked_at = now()
where journey_id = '20000000-0000-4000-8000-000000000072';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000073';
select is((select count(*) from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000072'), 0::bigint,
    'revoked Journey grant disappears from list');
select throws_ok(
    $$select public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000072')$$,
    '42501', 'Journey is not available to this caller', 'revoked Journey grant denies status');
reset role;

update public.trusted_contact_relationships set status = 'REVOKED', revoked_at = now()
where id = '30000000-0000-4000-8000-000000000072';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000073';
select is((select count(*) from public.list_trusted_journey_statuses_v1()
           where journey_id = '20000000-0000-4000-8000-000000000073'), 0::bigint,
    'revoked relationship disappears from list');
select throws_ok(
    $$select public.get_trusted_journey_status_v1('20000000-0000-4000-8000-000000000073')$$,
    '42501', 'Journey is not available to this caller', 'revoked relationship denies status');
reset role;

select * from finish();
rollback;

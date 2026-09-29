begin;
select no_plan();

select is(pg_get_function_result('public.get_my_traveller_identity_v1()'::regprocedure),
    'jsonb', 'own-identity RPC has only one compact JSON result');
select ok(has_function_privilege('authenticated',
    'public.get_my_traveller_identity_v1()', 'EXECUTE'),
    'authenticated callers may request their own identity');
select ok(not has_function_privilege('anon',
    'public.get_my_traveller_identity_v1()', 'EXECUTE'),
    'public anon role cannot execute the RPC');
select ok(not has_table_privilege('authenticated', 'public.traveller_profiles',
    'SELECT,INSERT,UPDATE,DELETE'), 'RPC grants no direct profile table access');

insert into auth.users
    (id, instance_id, aud, role, email, email_confirmed_at,
     is_anonymous, created_at, updated_at)
values
    ('10000000-0000-4000-8000-000000000091', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'my-owner@example.invalid', now(), false, now(), now()),
    ('10000000-0000-4000-8000-000000000092', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'other-owner@example.invalid', now(), false, now(), now()),
    ('10000000-0000-4000-8000-000000000093', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'viewer-only@example.invalid', now(), false, now(), now()),
    ('10000000-0000-4000-8000-000000000094', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', null, null, true, now(), now()),
    ('10000000-0000-4000-8000-000000000095', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'unconfirmed@example.invalid', null, false, now(), now());

insert into public.traveller_profiles (owner_id, full_name, handle)
values
    ('10000000-0000-4000-8000-000000000091', 'Same Name', 'identity_one'),
    ('10000000-0000-4000-8000-000000000092', 'Same Name', 'identity_two'),
    -- Privileged/corrupt rows cannot turn an anonymous or unconfirmed Auth
    -- identity into a Traveller through this read contract.
    ('10000000-0000-4000-8000-000000000094', 'Invalid Anonymous', 'anon_invalid'),
    ('10000000-0000-4000-8000-000000000095', 'Invalid Unconfirmed', 'unconfirmed_invalid');

create temp table identity_before as
select (select count(*) from public.traveller_profiles) as profile_count,
       (select count(*) from public.journeys) as journey_count,
       (select count(*) from public.telemetry_observations) as telemetry_count;

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
select is(public.get_my_traveller_identity_v1(),
    '{"full_name":"Same Name","handle":"identity_one"}'::jsonb,
    'verified Traveller sees only their own name and handle');
select ok(public.get_my_traveller_identity_v1() ? 'email' = false,
    'own-identity result contains no email');
select ok(public.get_my_traveller_identity_v1() ? 'owner_id' = false,
    'own-identity result contains no user identifier');
select throws_ok($$select * from public.traveller_profiles$$,
    '42501', 'permission denied for table traveller_profiles',
    'RPC does not open direct profile reads');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000092';
select is(public.get_my_traveller_identity_v1(),
    '{"full_name":"Same Name","handle":"identity_two"}'::jsonb,
    'a second Traveller cannot see the first Traveller handle');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000093';
select is(public.get_my_traveller_identity_v1(), null::jsonb,
    'verified Viewer-only account without a Traveller profile has no Traveller identity');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000094';
select is(public.get_my_traveller_identity_v1(), null::jsonb,
    'authenticated anonymous account cannot manufacture Traveller status even with a corrupt profile');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000095';
select is(public.get_my_traveller_identity_v1(), null::jsonb,
    'unconfirmed email cannot obtain Traveller status even with a corrupt profile');
reset role;

select results_eq(
    $$select count(*) from public.traveller_profiles$$,
    $$select profile_count from identity_before$$,
    'reads leave profile rows unchanged');
select results_eq(
    $$select count(*) from public.journeys$$,
    $$select journey_count from identity_before$$,
    'reads leave Journey rows unchanged');
select results_eq(
    $$select count(*) from public.telemetry_observations$$,
    $$select telemetry_count from identity_before$$,
    'reads leave device evidence unchanged');

select * from finish();
rollback;

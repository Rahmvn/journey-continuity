begin;
select no_plan();

select is(pg_get_function_result(
    'public.complete_traveller_profile_identity_v1(text,text)'::regprocedure),
    'jsonb', 'create-only finalizer returns one bounded JSON result');
select ok(has_function_privilege('authenticated',
    'public.complete_traveller_profile_identity_v1(text,text)', 'EXECUTE'),
    'authenticated role may complete its own profile');
select ok(not has_function_privilege('anon',
    'public.complete_traveller_profile_identity_v1(text,text)', 'EXECUTE'),
    'public anon role cannot execute the finalizer');
select ok(not has_table_privilege('authenticated', 'public.traveller_profiles',
    'SELECT,INSERT,UPDATE,DELETE'), 'finalizer grants no direct table access');

insert into auth.users
    (id, instance_id, aud, role, email, email_confirmed_at,
     is_anonymous, created_at, updated_at)
values
    ('10000000-0000-4000-8000-000000000081', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'create-a@example.invalid', now(), false, now(), now()),
    ('10000000-0000-4000-8000-000000000082', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'create-b@example.invalid', now(), false, now(), now()),
    ('10000000-0000-4000-8000-000000000083', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', null, null, true, now(), now()),
    ('10000000-0000-4000-8000-000000000084', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'unconfirmed-create@example.invalid', null, false, now(), now()),
    ('10000000-0000-4000-8000-000000000085', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'create-c@example.invalid', now(), false, now(), now());

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000083';
select throws_ok(
    $$select public.complete_traveller_profile_identity_v1('Anonymous', 'anon_handle')$$,
    '42501', 'A verified email identity is required',
    'anonymous authenticated owner cannot finalize a profile');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000084';
select throws_ok(
    $$select public.complete_traveller_profile_identity_v1('Unconfirmed', 'unconfirmed_handle')$$,
    '42501', 'A verified email identity is required',
    'unconfirmed email cannot finalize a profile');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000081';
select throws_ok(
    $$select public.complete_traveller_profile_identity_v1(' ', 'valid_handle')$$,
    '22023', 'Traveller full name must be 1 to 100 visible characters without control characters',
    'finalizer preserves full-name validation');
select throws_ok(
    $$select public.complete_traveller_profile_identity_v1('Valid Name', 'invalid_')$$,
    '22023', 'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit',
    'finalizer preserves handle validation');
select is(public.complete_traveller_profile_identity_v1('  First Name  ', '  @First_Handle  '),
    '{"status":"CREATED"}'::jsonb,
    'first completion creates normalized profile');
select is(public.get_my_traveller_identity_v1(),
    '{"full_name":"First Name","handle":"first_handle"}'::jsonb,
    'first profile is stored under caller A');
reset role;
create temp table first_profile_row_version as
select ctid::text as row_version from public.traveller_profiles
where owner_id = '10000000-0000-4000-8000-000000000081';
set local role authenticated;
select is(public.complete_traveller_profile_identity_v1('First Name', 'FIRST_HANDLE'),
    '{"status":"ALREADY_COMPLETED","matches_request":true}'::jsonb,
    'identical second completion is idempotent');
select is(public.complete_traveller_profile_identity_v1('Different Name', 'first_handle'),
    '{"status":"ALREADY_COMPLETED","matches_request":false}'::jsonb,
    'different full name returns already completed');
select is(public.complete_traveller_profile_identity_v1('First Name', 'different_handle'),
    '{"status":"ALREADY_COMPLETED","matches_request":false}'::jsonb,
    'different handle returns already completed');
select is(public.get_my_traveller_identity_v1(),
    '{"full_name":"First Name","handle":"first_handle"}'::jsonb,
    'repeat completions never update the first profile');
reset role;
select is((select ctid::text from public.traveller_profiles
    where owner_id = '10000000-0000-4000-8000-000000000081'),
    (select row_version from first_profile_row_version),
    'identical and differing repeat calls do not create a new row version');
set local role authenticated;
select ok(public.complete_traveller_profile_identity_v1('First Name', 'first_handle') ? 'owner_id' = false,
    'finalizer exposes no Auth owner ID');
select ok(public.complete_traveller_profile_identity_v1('First Name', 'first_handle') ? 'email' = false,
    'finalizer exposes no email');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000082';
select is(public.complete_traveller_profile_identity_v1('Second Owner', 'second_handle'),
    '{"status":"CREATED"}'::jsonb,
    'second Traveller creates a separate profile');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000085';
select is(public.complete_traveller_profile_identity_v1('Third Owner', '@SECOND_HANDLE'),
    '{"status":"HANDLE_UNAVAILABLE"}'::jsonb,
    'another Traveller handle conflict returns no owner details');
select is(public.get_my_traveller_identity_v1(), null::jsonb,
    'handle conflict creates no profile');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000082';
select is(public.get_my_traveller_identity_v1(),
    '{"full_name":"Second Owner","handle":"second_handle"}'::jsonb,
    'handle conflict cannot affect another Traveller');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000085';

-- Simulated competing sessions for the same owner: both initially intend a
-- first completion, but the second call must observe the winner as immutable.
select is(public.complete_traveller_profile_identity_v1('Winning Name', 'winner_handle'),
    '{"status":"CREATED"}'::jsonb,
    'first simulated same-owner completion wins');
select is(public.complete_traveller_profile_identity_v1('Losing Name', 'loser_handle'),
    '{"status":"ALREADY_COMPLETED","matches_request":false}'::jsonb,
    'second simulated same-owner completion cannot update winner');
select is(public.get_my_traveller_identity_v1(),
    '{"full_name":"Winning Name","handle":"winner_handle"}'::jsonb,
    'same-owner competing completion is not last-write-wins');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000081';
select is(public.set_traveller_profile_identity('Intentional Edit', 'edited_handle'),
    '{"full_name":"Intentional Edit","handle":"edited_handle"}'::jsonb,
    'existing setter still intentionally edits the same owner profile');
select is(public.complete_traveller_profile_identity_v1('Stale Create', 'stale_handle'),
    '{"status":"ALREADY_COMPLETED","matches_request":false}'::jsonb,
    'create-only finalizer cannot overwrite an intentional edit');
select is(public.get_my_traveller_identity_v1(),
    '{"full_name":"Intentional Edit","handle":"edited_handle"}'::jsonb,
    'edited profile remains unchanged by stale completion');
reset role;

select is((select count(*) from public.traveller_profiles), 3::bigint,
    'only the three verified Travellers have profile rows');
select * from finish();
rollback;

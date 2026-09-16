begin;
select plan(8);

select has_table('public', 'journeys', 'journeys table exists');
select has_table('public', 'telemetry_observations', 'telemetry table exists');
select is(
    (select relrowsecurity from pg_class where oid = 'public.journeys'::regclass),
    true,
    'journeys has RLS enabled'
);
select is(
    (select relrowsecurity from pg_class where oid = 'public.telemetry_observations'::regclass),
    true,
    'telemetry has RLS enabled'
);
select policies_are(
    'public',
    'journeys',
    array['journeys_insert_own', 'journeys_select_own', 'journeys_update_own'],
    'journeys exposes only explicit owner policies'
);
select policies_are(
    'public',
    'telemetry_observations',
    array[
        'telemetry_insert_through_owned_journey',
        'telemetry_select_through_owned_journey',
        'telemetry_update_through_owned_journey'
    ],
    'telemetry policies require Journey ownership'
);
select ok(
    not has_table_privilege('anon', 'public.journeys', 'SELECT,INSERT,UPDATE,DELETE'),
    'anon role has no Journey table privileges'
);
select ok(
    not has_table_privilege('anon', 'public.telemetry_observations', 'SELECT,INSERT,UPDATE,DELETE'),
    'anon role has no telemetry table privileges'
);

select * from finish();
rollback;

begin;

create extension if not exists pgtap with schema extensions;
select plan(36);

select has_schema('private', 'private server schema exists');
select has_table('private', 'fallback_installations', 'installation table exists');
select has_table('private', 'fallback_installation_keys', 'key history table exists');
select has_table('private', 'journey_fallback_bindings', 'binding history table exists');
select hasnt_column('private', 'fallback_installation_keys', 'master_key', 'plaintext key column is absent');
select hasnt_column('private', 'fallback_installation_keys', 'plaintext_master_key', 'plaintext-labelled key column is absent');
select ok(not has_schema_privilege('anon', 'private', 'USAGE'), 'anonymous cannot use private schema');
select ok(not has_schema_privilege('authenticated', 'private', 'USAGE'), 'authenticated cannot use private schema');
select ok(not has_table_privilege('authenticated', 'private.fallback_installation_keys', 'SELECT'), 'ordinary clients cannot read encrypted keys');
select ok(not has_function_privilege(
    'authenticated',
    'public.provision_fallback_material_backend(uuid,uuid,uuid,bigint,bytea,bytea,integer,bytea)',
    'EXECUTE'
), 'ordinary authenticated clients cannot call backend provisioning RPC');
select has_index(
    'private', 'fallback_installation_keys', 'fallback_installation_keys_one_active',
    'database enforces one ACTIVE key per installation under concurrency'
);
select has_index(
    'private', 'journey_fallback_bindings', 'journey_fallback_bindings_one_active_journey',
    'database enforces one ACTIVE binding per Journey under concurrency'
);

insert into auth.users (id, aud, role, email, created_at, updated_at)
values
    ('10000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'fallback-a@example.test', now(), now()),
    ('20000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated', 'fallback-b@example.test', now(), now());

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status, completed_at)
values
    ('a0000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'A active', now() + interval '1 hour', now(), 'ACTIVE', null),
    ('a0000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'A future', now() + interval '1 hour', now(), 'ACTIVE', null),
    ('b0000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002', 'B active', now() + interval '1 hour', now(), 'ACTIVE', null),
    ('a0000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', 'A complete', now(), now() - interval '1 hour', 'COMPLETED', now());

select throws_ok(
    $$select * from public.provision_fallback_material_backend(
        '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
        'b0000000-0000-4000-8000-000000000001', 1, decode(repeat('11',48),'hex'),
        decode(repeat('12',12),'hex'), 1, decode(repeat('13',12),'hex'))$$,
    '42501', 'Journey is not available to this caller', 'cross-owner Journey provisioning is rejected'
);
select throws_ok(
    $$select * from public.provision_fallback_material_backend(
        '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
        'a0000000-0000-4000-8000-000000000003', 1, decode(repeat('11',48),'hex'),
        decode(repeat('12',12),'hex'), 1, decode(repeat('13',12),'hex'))$$,
    '22023', 'Fallback provisioning requires an ACTIVE Journey', 'completed Journey is ineligible'
);
select throws_ok(
    $$select * from public.provision_fallback_material_backend(
        '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
        'a0000000-0000-4000-8000-000000000001', 4294967296, decode(repeat('11',48),'hex'),
        decode(repeat('12',12),'hex'), 1, decode(repeat('13',12),'hex'))$$,
    '22023', 'Invalid fallback provisioning material', 'out-of-range key id is rejected'
);
select throws_ok(
    $$select * from public.provision_fallback_material_backend(
        '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
        'a0000000-0000-4000-8000-000000000001', 1, decode(repeat('11',48),'hex'),
        decode(repeat('12',12),'hex'), 1, decode(repeat('13',11),'hex'))$$,
    '22023', 'Invalid fallback provisioning material', 'wrong handle length is rejected'
);

create temporary table first_provision as
select * from public.provision_fallback_material_backend(
    '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
    'a0000000-0000-4000-8000-000000000001', 4000000001, decode(repeat('21',48),'hex'),
    decode(repeat('22',12),'hex'), 1, decode(repeat('23',12),'hex'));

select is((select key_id from first_provision), 4000000001::bigint, 'opaque uint32 key id is retained');
select is(octet_length((select journey_handle from first_provision)), 12, 'Journey handle is exactly 12 bytes');
select is((select binding_status from first_provision), 'ACTIVE', 'new binding is active');
select is((select key_lifecycle_status from first_provision), 'ACTIVE', 'new installation key is active');
select is((
    select count(*)
    from private.fallback_installation_keys as k
    join private.fallback_installations as i on i.id = k.installation_id
    where i.installation_identifier = '11111111-1111-4111-8111-111111111111'
), 1::bigint, 'one fixture installation key was created');
select is((
    select count(*)
    from private.journey_fallback_bindings
    where journey_id = 'a0000000-0000-4000-8000-000000000001'
), 1::bigint, 'one fixture Journey binding was created');

create temporary table retry_provision as
select * from public.provision_fallback_material_backend(
    '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
    'a0000000-0000-4000-8000-000000000001', 4000000002, decode(repeat('31',48),'hex'),
    decode(repeat('32',12),'hex'), 2, decode(repeat('33',12),'hex'));

select is((select key_id from retry_provision), (select key_id from first_provision), 'installation retry returns same key id');
select is((select encrypted_master_key from retry_provision), (select encrypted_master_key from first_provision), 'installation retry returns same encrypted key');
select is((select journey_handle from retry_provision), (select journey_handle from first_provision), 'Journey retry returns same handle');
select is((
    select count(*)
    from private.fallback_installation_keys as k
    join private.fallback_installations as i on i.id = k.installation_id
    where i.installation_identifier = '11111111-1111-4111-8111-111111111111'
), 1::bigint, 'retry did not duplicate the fixture installation key');
select is((
    select count(*)
    from private.journey_fallback_bindings
    where journey_id = 'a0000000-0000-4000-8000-000000000001'
), 1::bigint, 'retry did not duplicate the fixture binding');

select throws_ok(
    $$select * from public.provision_fallback_material_backend(
        '10000000-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222',
        'a0000000-0000-4000-8000-000000000001', 3000000001, decode(repeat('41',48),'hex'),
        decode(repeat('42',12),'hex'), 1, decode(repeat('43',12),'hex'))$$,
    '42501', 'Journey is already bound to another installation', 'second installation cannot take over Journey'
);

select lives_ok(
    $$select * from public.rotate_fallback_installation_key_backend(
        '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
        4000000003, decode(repeat('51',48),'hex'), decode(repeat('52',12),'hex'), 2)$$,
    'rotation operation succeeds'
);
select is((select lifecycle_status from private.fallback_installation_keys where key_id = 4000000001), 'RETIRED', 'old key is retired');
select is((select lifecycle_status from private.fallback_installation_keys where key_id = 4000000003), 'ACTIVE', 'rotated key is active');
select is((select key_id from private.journey_fallback_bindings where journey_id = 'a0000000-0000-4000-8000-000000000001'), 4000000001::bigint, 'rotation does not rewrite old Journey binding');

create temporary table future_provision as
select * from public.provision_fallback_material_backend(
    '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
    'a0000000-0000-4000-8000-000000000002', 4000000004, decode(repeat('61',48),'hex'),
    decode(repeat('62',12),'hex'), 3, decode(repeat('63',12),'hex'));
select is((select key_id from future_provision), 4000000003::bigint, 'new Journey uses current ACTIVE key');
select isnt((select journey_handle from future_provision), (select journey_handle from first_provision), 'handles are unique across Journeys');
select ok((select public.revoke_fallback_installation_key_backend(
    '10000000-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 4000000003
)), 'key can be revoked without deleting history');
select is((select lifecycle_status from private.fallback_installation_keys where key_id = 4000000003), 'REVOKED', 'revoked key remains distinguishable');

select * from finish();
rollback;

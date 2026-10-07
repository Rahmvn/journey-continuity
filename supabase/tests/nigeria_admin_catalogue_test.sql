begin;
select no_plan();

select is((select sha256 from public.admin_catalogue_versions
 where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1'),
 'e5a75f4d7f906ab3bd2d4db806bbbfa1b4c7ac2ff04f6cca3e52dd39e6b24aa4',
 'exact canonical asset hash');
select is((select snapshot_date::text from public.admin_catalogue_versions
 where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1'), '2026-10-03', 'Alabarin snapshot date');
select is((select count(*) from public.admin_regions where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1'), 37::bigint, '37 regions');
select is((select count(*) from public.admin_regions where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1' and region_type = 'STATE'), 36::bigint, '36 states');
select is((select count(*) from public.admin_regions where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1' and region_type = 'FCT'), 1::bigint, 'one FCT');
select is((select count(*) from public.admin_local_units where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1'), 774::bigint, '774 local units');
select is((select count(*) from public.admin_local_units where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1' and local_unit_type = 'LGA'), 768::bigint, '768 LGAs');
select is((select count(*) from public.admin_local_units where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1' and local_unit_type = 'AREA_COUNCIL'), 6::bigint, 'six Area Councils');
select is((select count(*) from public.admin_local_units u left join public.admin_regions r
 on r.dataset_id = u.dataset_id and r.version = u.version and r.id = u.parent_region_id
 where r.id is null), 0::bigint, 'all local units have same-version parents');
select is((select count(*) from public.admin_local_units u join public.admin_regions r
 on r.dataset_id = u.dataset_id and r.version = u.version and r.id = u.parent_region_id
 where (r.region_type = 'STATE' and u.local_unit_type <> 'LGA') or
       (r.region_type = 'FCT' and u.local_unit_type <> 'AREA_COUNCIL')), 0::bigint,
 'all local unit types match parent types');
select is((select count(*) from public.admin_local_units where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1' and parent_region_id = 'ng-fct'), 6::bigint,
 'FCT has six Area Councils');
select is((select count(*) from public.admin_local_units where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1' and parent_region_id = 'ng-kwara'), 16::bigint,
 'Kwara has 16 LGAs');
select is((select canonical_name from public.admin_local_units where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-03-v1' and id = 'ng-fct-abuja-municipal'),
 'Abuja Municipal Area Council', 'AMAC canonical name');
select throws_ok($$insert into public.admin_local_units
 (dataset_id, version, id, parent_region_id, parent_region_type, canonical_name, local_unit_type, display_order)
 values ('NG_ADMIN', 'alabarin-2026-10-03-v1', 'ng-kwara-invalid', 'ng-fct', 'STATE', 'Invalid', 'LGA', 99)$$,
 '23503', null, 'foreign key prevents false parent type');
select throws_ok($$insert into public.admin_local_units
 (dataset_id, version, id, parent_region_id, parent_region_type, canonical_name, local_unit_type, display_order)
 values ('NG_ADMIN', 'alabarin-2026-10-03-v1', 'ng-kwara-invalid', 'ng-kwara', 'STATE', 'Invalid', 'AREA_COUNCIL', 99)$$,
 '23514', null, 'State cannot have Area Council');
select throws_ok($$insert into public.admin_local_units
 (dataset_id, version, id, parent_region_id, parent_region_type, canonical_name, local_unit_type, display_order)
 values ('NG_ADMIN', 'alabarin-2026-10-03-v1', 'ng-fct-invalid', 'ng-fct', 'FCT', 'Invalid', 'LGA', 99)$$,
 '23514', null, 'FCT cannot have LGA');

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at)
values ('10000000-0000-4000-8000-0000000000b1', '00000000-0000-0000-0000-000000000000',
 'authenticated', 'authenticated', 'catalogue-owner@example.invalid', now(), now()),
 ('10000000-0000-4000-8000-0000000000b2', '00000000-0000-0000-0000-000000000000',
 'authenticated', 'authenticated', 'catalogue-contact@example.invalid', now(), now());

create function pg_temp.insert_catalogue_journey(
 p_number integer, p_version text, p_region_id text, p_region_name text,
 p_unit_id text, p_unit_name text)
returns void language plpgsql as $$
begin
 insert into public.journeys
 (id, owner_id, destination, expected_arrival_at, started_at, status,
  destination_country_code, destination_state_id, destination_state_name,
  destination_lga_id, destination_lga_name, destination_kind,
  destination_selection_source,
  destination_admin_dataset_version, destination_local_unit_type)
 values (('40000000-0000-4000-8000-' || lpad(p_number::text, 12, '0'))::uuid,
  '10000000-0000-4000-8000-0000000000b1', 'Selected destination',
  now() + interval '1 hour', now(), 'ACTIVE', 'NG',
  p_region_id, p_region_name, p_unit_id, p_unit_name,
  'LOCALITY', 'LOCALITY_SELECTION', p_version, case when p_region_id = 'ng-fct' then 'AREA_COUNCIL' else 'LGA' end);
end;
$$;

-- A retained older version remains valid even after another version is installed.
insert into public.admin_catalogue_versions
 (dataset_id, version, snapshot_date, sha256, region_count, local_unit_count)
values ('NG_ADMIN', 'alabarin-future-fixture', '2026-10-04', repeat('0', 64), 1, 1);
insert into public.admin_regions
 (dataset_id, version, id, canonical_name, region_type, display_order)
values ('NG_ADMIN', 'alabarin-future-fixture', 'ng-kwara', 'Kwara Renamed Fixture', 'STATE', 0);
insert into public.admin_local_units
 (dataset_id, version, id, parent_region_id, parent_region_type, canonical_name, local_unit_type, display_order)
values ('NG_ADMIN', 'alabarin-future-fixture', 'ng-kwara-offa', 'ng-kwara', 'STATE',
 'Offa Renamed Fixture', 'LGA', 0);

select lives_ok($$select pg_temp.insert_catalogue_journey(1, 'alabarin-2026-10-03-v1',
 'ng-kwara', 'Kwara', 'ng-kwara-offa', 'Offa')$$, 'valid State/LGA Journey');
select lives_ok($$select pg_temp.insert_catalogue_journey(13, 'alabarin-future-fixture',
 'ng-kwara', 'Kwara Renamed Fixture', 'ng-kwara-offa', 'Offa Renamed Fixture')$$,
 'later retained version can use corrected snapshot names');
select lives_ok($$select pg_temp.insert_catalogue_journey(2, 'alabarin-2026-10-03-v1',
 'ng-fct', 'Federal Capital Territory', 'ng-fct-abuja-municipal', 'Abuja Municipal Area Council')$$,
 'valid FCT/Area Council Journey');
select throws_ok($$select pg_temp.insert_catalogue_journey(3, 'unknown',
 'ng-kwara', 'Kwara', 'ng-kwara-offa', 'Offa')$$,
 '22023', 'Unknown or missing destination catalogue version', 'unknown version rejected');
select throws_ok($$select pg_temp.insert_catalogue_journey(4, null,
 'ng-kwara', 'Kwara', 'ng-kwara-offa', 'Offa')$$,
 '22023', 'Unknown or missing destination catalogue version', 'new versionless structured row rejected');
select throws_ok($$select pg_temp.insert_catalogue_journey(5, 'alabarin-2026-10-03-v1',
 'ng-unknown', 'Kwara', 'ng-kwara-offa', 'Offa')$$,
 '22023', 'Journey destination does not match catalogue snapshot', 'unknown region ID rejected');
select throws_ok($$select pg_temp.insert_catalogue_journey(6, 'alabarin-2026-10-03-v1',
 'ng-kwara', 'Lagos', 'ng-kwara-offa', 'Offa')$$,
 '22023', 'Journey destination does not match catalogue snapshot', 'wrong region name rejected');
select throws_ok($$select pg_temp.insert_catalogue_journey(7, 'alabarin-2026-10-03-v1',
 'ng-kwara', 'Kwara', 'ng-missing', 'Offa')$$,
 '22023', 'Journey destination does not match catalogue snapshot', 'unknown local-unit ID rejected');
select throws_ok($$select pg_temp.insert_catalogue_journey(8, 'alabarin-2026-10-03-v1',
 'ng-kwara', 'Kwara', 'ng-kwara-offa', 'Ikeja')$$,
 '22023', 'Journey destination does not match catalogue snapshot', 'wrong local-unit name rejected');
select throws_ok($$select pg_temp.insert_catalogue_journey(9, 'alabarin-2026-10-03-v1',
 'ng-kwara', 'Kwara', 'ng-lagos-ikeja', 'Ikeja')$$,
 '22023', 'Journey destination does not match catalogue snapshot', 'wrong parent rejected');

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('40000000-0000-4000-8000-000000000010', '10000000-0000-4000-8000-0000000000b1',
 'Text only', now() + interval '1 hour', now(), 'ACTIVE');
select ok((select destination_kind is null from public.journeys where id =
 '40000000-0000-4000-8000-000000000010'), 'old-client text-only insert accepted');
update public.journeys set status = 'COMPLETED', completed_at = now()
where id = '40000000-0000-4000-8000-000000000010';
select ok((select status = 'COMPLETED' from public.journeys where id =
 '40000000-0000-4000-8000-000000000010'), 'historical text-only lifecycle update');

-- Legacy production history has all structured facts NULL, never invented versionless structure.
insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('40000000-0000-4000-8000-000000000011', '10000000-0000-4000-8000-0000000000b1',
 'Legacy Unit', now() + interval '1 hour', now(), 'ACTIVE');
update public.journeys set status = 'COMPLETED', completed_at = now()
where id = '40000000-0000-4000-8000-000000000011';
select ok((select status = 'COMPLETED' and destination_admin_dataset_version is null
 and destination_local_unit_type is null from public.journeys where id = '40000000-0000-4000-8000-000000000011'),
 'previously stored legacy text remains lifecycle-updateable without invented evidence');

-- Same-value current upsert, then old-client omitted-field and explicit-null conflict shapes.
insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind, destination_selection_source, destination_admin_dataset_version, destination_local_unit_type)
select id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind, destination_selection_source, destination_admin_dataset_version, destination_local_unit_type
from public.journeys where id = '40000000-0000-4000-8000-000000000001'
on conflict (id) do update set destination = excluded.destination,
 destination_kind = excluded.destination_kind,
 destination_admin_dataset_version = excluded.destination_admin_dataset_version;
select ok((select destination_admin_dataset_version = 'alabarin-2026-10-03-v1'
 from public.journeys where id = '40000000-0000-4000-8000-000000000001'),
 'same-value current upsert accepted');
insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
select id, owner_id, destination, expected_arrival_at, started_at, status
from public.journeys where id = '40000000-0000-4000-8000-000000000001'
on conflict (id) do update set destination_kind = excluded.destination_kind,
 destination_state_id = excluded.destination_state_id,
 destination_lga_id = excluded.destination_lga_id,
 destination_admin_dataset_version = excluded.destination_admin_dataset_version;
select ok((select destination_kind = 'LOCALITY' and destination_state_id = 'ng-kwara' and
 destination_lga_id = 'ng-kwara-offa' and
 destination_admin_dataset_version = 'alabarin-2026-10-03-v1'
 from public.journeys where id = '40000000-0000-4000-8000-000000000001'),
 'old-client omitted fields cannot erase catalogue facts');
insert into public.journeys
 (id, owner_id, destination, expected_arrival_at, started_at, status,
  destination_kind, destination_state_id, destination_admin_dataset_version, destination_local_unit_type)
select id, owner_id, destination, expected_arrival_at, started_at, status,
 null, null, null, null from public.journeys
where id = '40000000-0000-4000-8000-000000000001'
on conflict (id) do update set destination_kind = excluded.destination_kind,
 destination_state_id = excluded.destination_state_id,
 destination_admin_dataset_version = excluded.destination_admin_dataset_version;
select ok((select destination_kind = 'LOCALITY' and destination_state_id = 'ng-kwara' and
 destination_admin_dataset_version = 'alabarin-2026-10-03-v1'
 from public.journeys where id = '40000000-0000-4000-8000-000000000001'),
 'explicit null compatibility cannot erase catalogue facts');
update public.journeys set status = 'CANCELLED', ended_at = now()
where id = '40000000-0000-4000-8000-000000000001';
select ok((select status = 'CANCELLED' and ended_at is not null and completed_at is null
 from public.journeys where id = '40000000-0000-4000-8000-000000000001'),
 'terminal lifecycle update still works');
select throws_ok($$update public.journeys set destination_lga_name = 'Wrong'
 where id = '40000000-0000-4000-8000-000000000001'$$,
 'JD001', 'Journey destination is immutable', 'catalogue-backed snapshot is immutable');

select ok(not has_table_privilege('anon', 'public.admin_regions', 'INSERT') and
 not has_table_privilege('authenticated', 'public.admin_regions', 'UPDATE') and
 not has_table_privilege('service_role', 'public.admin_regions', 'DELETE') and
 not has_table_privilege('authenticated', 'public.admin_local_units', 'SELECT') and
 not has_table_privilege('anon', 'public.admin_catalogue_versions', 'SELECT'),
 'catalogue tables have no client read/write grants');
select ok((select relrowsecurity from pg_class where oid = 'public.admin_regions'::regclass),
 'catalogue RLS enabled');
select is((select count(*) from pg_policies where tablename in
 ('admin_regions', 'admin_local_units', 'admin_catalogue_versions')), 0::bigint,
 'catalogue has no public client policies');
select ok((select prosecdef from pg_proc where oid =
 'public.validate_journey_catalogue_destination_insert()'::regprocedure),
 'catalogue validator uses SECURITY DEFINER');
select ok((select proconfig @> array['search_path=""'] from pg_proc where oid =
 'public.validate_journey_catalogue_destination_insert()'::regprocedure),
 'catalogue validator pins an empty search_path');
select ok(not has_function_privilege('anon',
 'public.validate_journey_catalogue_destination_insert()', 'EXECUTE') and
 not has_function_privilege('authenticated',
 'public.validate_journey_catalogue_destination_insert()', 'EXECUTE'),
 'client roles cannot directly execute catalogue validator');
select ok((select count(*) = 3 from pg_policies where tablename = 'journeys'),
 'Journey owner RLS policy set unchanged');
insert into public.trusted_contact_relationships
 (id, traveller_user_id, contact_user_id, display_name, contact_email_normalized)
values ('30000000-0000-4000-8000-0000000000b1',
 '10000000-0000-4000-8000-0000000000b1', '10000000-0000-4000-8000-0000000000b2',
 'Contact', 'catalogue-contact@example.invalid');
insert into public.journey_trusted_contact_access
 (journey_id, relationship_id, traveller_user_id, contact_user_id)
values ('40000000-0000-4000-8000-000000000001',
 '30000000-0000-4000-8000-0000000000b1',
 '10000000-0000-4000-8000-0000000000b1', '10000000-0000-4000-8000-0000000000b2')
on conflict do nothing;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000b2';
select ok(not (public.get_trusted_journey_status_v2('40000000-0000-4000-8000-000000000001')
 ?| array['destination_state_id', 'destination_lga_id', 'destination_admin_dataset_version']),
 'trusted status does not expose catalogue internals');

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000b1';
select is(current_user, 'authenticated', 'owner insert uses authenticated database role');
select is(auth.uid()::text, '10000000-0000-4000-8000-0000000000b1',
 'owner insert uses user A JWT subject');
insert into public.journeys
 (id, owner_id, destination, expected_arrival_at, started_at, status,
  destination_country_code, destination_state_id, destination_state_name,
  destination_lga_id, destination_lga_name, destination_kind,
  destination_selection_source,
  destination_admin_dataset_version, destination_local_unit_type)
values ('40000000-0000-4000-8000-000000000012', '10000000-0000-4000-8000-0000000000b1',
 'Authenticated selection', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'ng-kwara', 'Kwara', 'ng-kwara-offa', 'Offa', 'LOCALITY', 'LOCALITY_SELECTION',
 'alabarin-2026-10-03-v1', 'LGA');
select ok((select count(*) = 1 from public.journeys where id =
 '40000000-0000-4000-8000-000000000012'),
 'authenticated owner can insert through catalogue validator without catalogue SELECT');
select throws_ok($$insert into public.journeys
 (id, owner_id, destination, expected_arrival_at, started_at, status,
  destination_country_code, destination_state_id, destination_state_name,
  destination_lga_id, destination_lga_name, destination_kind,
  destination_selection_source,
  destination_admin_dataset_version, destination_local_unit_type)
 values ('40000000-0000-4000-8000-000000000014', '10000000-0000-4000-8000-0000000000b1',
 'Wrong canonical name', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'ng-kwara', 'Lagos', 'ng-kwara-offa', 'Offa', 'LOCALITY', 'LOCALITY_SELECTION',
 'alabarin-2026-10-03-v1', 'LGA')$$,
 '22023', 'Journey destination does not match catalogue snapshot',
 'authenticated owner gets catalogue error for wrong canonical name');

set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000b2';
select is(auth.uid()::text, '10000000-0000-4000-8000-0000000000b2',
 'cross-owner checks use user B JWT subject');
select throws_ok($$insert into public.journeys
 (id, owner_id, destination, expected_arrival_at, started_at, status,
  destination_country_code, destination_state_id, destination_state_name,
  destination_lga_id, destination_lga_name, destination_kind,
  destination_selection_source,
  destination_admin_dataset_version, destination_local_unit_type)
 values ('40000000-0000-4000-8000-000000000015', '10000000-0000-4000-8000-0000000000b1',
 'Wrong owner', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'ng-kwara', 'Kwara', 'ng-kwara-offa', 'Offa', 'LOCALITY', 'LOCALITY_SELECTION',
 'alabarin-2026-10-03-v1', 'LGA')$$,
 '42501', null, 'authenticated user B cannot insert a Journey owned by A');
with changed as (
 update public.journeys set status = 'COMPLETED', completed_at = now()
 where id = '40000000-0000-4000-8000-000000000012' returning id
)
select is((select count(*) from changed), 0::bigint,
 'authenticated user B lifecycle update affects no row owned by A');
select throws_ok($$select count(*) from public.admin_regions$$,
 '42501', null, 'authenticated client cannot read catalogue table directly');
select throws_ok($$insert into public.admin_regions
 (dataset_id, version, id, canonical_name, region_type, display_order)
 values ('NG_ADMIN', 'alabarin-2026-10-03-v1', 'ng-client-write', 'Client write', 'STATE', 99)$$,
 '42501', null, 'authenticated client cannot mutate catalogue table directly');
reset role;
select ok((select status = 'ACTIVE' and completed_at is null from public.journeys where id =
 '40000000-0000-4000-8000-000000000012'),
 'cross-owner update left A Journey unchanged');

select * from finish();
rollback;

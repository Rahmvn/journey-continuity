begin;
select no_plan();

select is((select count(*) from public.admin_catalogue_versions where dataset_id = 'NG_ADMIN'),
 2::bigint, 'both immutable catalogue versions retained');
select is((select sha256 from public.admin_catalogue_versions where dataset_id = 'NG_ADMIN'
 and version = 'alabarin-2026-10-05-v2'),
 '694a02cb25ad723429477da364f8c3bb482f3b5cb6595e1c0571d8c7e27ee911', 'v2 exact asset hash');
select is((select count(*) from public.admin_regions where dataset_id = 'NG_ADMIN'
 and version = 'alabarin-2026-10-05-v2'), 37::bigint, 'v2 has 37 regions');
select is((select count(*) from public.admin_local_units where dataset_id = 'NG_ADMIN'
 and version = 'alabarin-2026-10-05-v2'), 774::bigint, 'v2 has 774 local units');
select is((select count(*) from public.admin_local_units a full join public.admin_local_units b
 on a.id = b.id and b.dataset_id = 'NG_ADMIN' and b.version = 'alabarin-2026-10-05-v2'
 where a.dataset_id = 'NG_ADMIN' and a.version = 'alabarin-2026-10-03-v1' and b.id is null),
 0::bigint, 'every original stable local-unit ID retained');
select is((select count(*) from public.admin_local_units a join public.admin_local_units b
 on a.dataset_id = b.dataset_id and a.id = b.id
 where a.dataset_id = 'NG_ADMIN' and a.version = 'alabarin-2026-10-03-v1'
 and b.version = 'alabarin-2026-10-05-v2' and
 (a.parent_region_id, a.parent_region_type, a.local_unit_type, a.display_order)
 is distinct from (b.parent_region_id, b.parent_region_type, b.local_unit_type, b.display_order)),
 0::bigint, 'parents, types and ordering unchanged');
select is((select count(*) from public.admin_local_units a join public.admin_local_units b
 on a.dataset_id = b.dataset_id and a.id = b.id
 where a.dataset_id = 'NG_ADMIN' and a.version = 'alabarin-2026-10-03-v1'
 and b.version = 'alabarin-2026-10-05-v2' and a.canonical_name <> b.canonical_name),
 1::bigint, 'exactly one display-name correction');
select is((select canonical_name from public.admin_local_units where dataset_id = 'NG_ADMIN'
 and version = 'alabarin-2026-10-03-v1' and id = 'ng-plateau-quaan-pan'),
 U&'Qua\2019an Pan', 'v1 curly apostrophe preserved; it was not U+FFFD');
select is((select canonical_name from public.admin_local_units where dataset_id = 'NG_ADMIN'
 and version = 'alabarin-2026-10-05-v2' and id = 'ng-plateau-quaan-pan'),
 'Qua''an Pan', 'v2 uses current INEC canonical spelling');

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at)
values ('10000000-0000-4000-8000-0000000000c1', '00000000-0000-0000-0000-000000000000',
 'authenticated', 'authenticated', 'catalogue-v2-owner@example.invalid', now(), now());
create function pg_temp.insert_versioned_journey(p_number integer, p_version text, p_name text)
returns void language plpgsql as $$
begin
 insert into public.journeys
 (id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind, destination_selection_source,
 destination_admin_dataset_version, destination_local_unit_type)
 values (('41000000-0000-4000-8000-' || lpad(p_number::text, 12, '0'))::uuid,
 '10000000-0000-4000-8000-0000000000c1', p_name, now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'ng-plateau', 'Plateau', 'ng-plateau-quaan-pan', p_name,
 'LOCALITY', 'LOCALITY_SELECTION', p_version, 'LGA');
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-0000000000c1', true);
select set_config('request.jwt.claims',
 '{"sub":"10000000-0000-4000-8000-0000000000c1","role":"authenticated"}', true);
select lives_ok($$select pg_temp.insert_versioned_journey(1, 'alabarin-2026-10-03-v1',
 U&'Qua\2019an Pan')$$, 'authenticated old offline client can still create a v1 Journey');
select lives_ok($$select pg_temp.insert_versioned_journey(2, 'alabarin-2026-10-05-v2',
 'Qua''an Pan')$$, 'authenticated current client creates corrected v2 Journey');
select throws_ok($$select pg_temp.insert_versioned_journey(3, 'alabarin-2026-10-05-v2',
 U&'Qua\2019an Pan')$$, '22023', 'Journey destination does not match catalogue snapshot',
 'v2 rejects v1 spelling; no silent normalization');
select lives_ok($$update public.journeys set status = 'COMPLETED', completed_at = now()
 where id = '41000000-0000-4000-8000-000000000001'$$, 'old-version Journey lifecycle still works');
select is((select destination_lga_name from public.journeys
 where id = '41000000-0000-4000-8000-000000000001'), U&'Qua\2019an Pan',
 'old Journey immutable name not rewritten');
reset role;
set local role service_role;
select is((select version from public.admin_catalogue_versions where dataset_id = 'NG_ADMIN'
 order by snapshot_date desc, version desc limit 1), 'alabarin-2026-10-05-v2',
 'existing search catalogue lookup chooses v2');
reset role;
select * from finish();
rollback;

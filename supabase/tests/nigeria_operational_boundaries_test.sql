begin;
select no_plan();

select is((select n.nspname from pg_extension e join pg_namespace n on n.oid=e.extnamespace
 where e.extname='postgis'), 'gis', 'PostGIS lives outside exposed public schema');
select is((select count(*) from private.boundary_dataset_versions where dataset_id='NG_OPERATIONAL_BOUNDARIES'),
 1::bigint, 'one reviewed boundary version');
select is((select version from private.boundary_dataset_versions), 'alabarin-2026-10-03-v1',
 'boundary version is independent of admin catalogue version');
select is((select source_lga_sha256 from private.boundary_dataset_versions),
 '784cc510bbbdbca2848bb02fb1a4a1a681885d464b9a09f29529ddb3a10f10a3', 'exact LGA source hash');
select is((select source_state_sha256 from private.boundary_dataset_versions),
 '97cece7f6304c0962f3c66d3d2f43cd8cbe8c0804d980c6e5bef6d0f5c8025ca', 'exact State source hash');
select is((select mapping_sha256 from private.boundary_dataset_versions),
 '92700100062315eefd485629567ee17aa5c77b5fc42a41520b21fb21a85f329b', 'exact mapping hash');
select is((select count(*) from private.boundary_regions), 37::bigint, '37 region geometries');
select is((select count(*) from private.boundary_local_units), 774::bigint, '774 local-unit geometries');
select is((select count(distinct region_id) from private.boundary_regions), 37::bigint, 'region IDs unique');
select is((select count(distinct local_unit_id) from private.boundary_local_units), 774::bigint, 'local-unit IDs unique');
select is((select count(*) from private.boundary_local_units where region_id='ng-fct'), 6::bigint, 'FCT has six mapped Area Councils');
select is((select count(*) from private.boundary_local_units where region_id='ng-kwara'), 16::bigint, 'Kwara has sixteen mapped LGAs');
select is((select count(*) from private.boundary_regions where geom is null or gis.ST_IsEmpty(geom)
 or not gis.ST_IsValid(geom) or gis.ST_SRID(geom)<>4326 or gis.GeometryType(geom)<>'MULTIPOLYGON'),
 0::bigint, 'all region geometries are valid nonempty SRID 4326 MultiPolygons');
select is((select count(*) from private.boundary_local_units where geom is null or gis.ST_IsEmpty(geom)
 or not gis.ST_IsValid(geom) or gis.ST_SRID(geom)<>4326 or gis.GeometryType(geom)<>'MULTIPOLYGON'),
 0::bigint, 'all local-unit geometries are valid nonempty SRID 4326 MultiPolygons');
select is((select count(*) from private.boundary_local_units u left join private.boundary_regions r
 on r.dataset_id=u.dataset_id and r.version=u.version and r.region_id=u.region_id
 where r.region_id is null or not gis.ST_CoveredBy(u.geom,r.geom)), 0::bigint,
 'every local unit is within its same-version mapped region');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)->>'outcome'),
 'INSIDE', 'Offa interior is inside');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.14435408227513,4.65057447773)->>'outcome'),
 'BOUNDARY_AMBIGUOUS', 'exact Offa/Oyun shared vertex is ambiguous');
select ok((select not gis.ST_Contains(geom,gis.ST_SetSRID(gis.ST_MakePoint(4.65057447773,8.14435408227513),4326))
 and gis.ST_Covers(geom,gis.ST_SetSRID(gis.ST_MakePoint(4.65057447773,8.14435408227513),4326))
 from private.boundary_local_units where local_unit_id='ng-kwara-offa'), 'shared fixture is truly on Offa boundary');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1998492,4.6316354)->>'outcome'),
 'OUTSIDE_SELECTED_UNIT', 'Oyun interior is outside selected Offa');
select is((select private.classify_destination_coordinate_v1('ng-lagos','ng-lagos-eti-osa',6.463625,3.396625)->>'outcome'),
 'COVERAGE_UNRESOLVED', 'audited Lagos land gap remains unresolved');
select is((select private.classify_destination_coordinate_v1('ng-fct','ng-fct-abuja-municipal',9.039929,7.4619731)->>'outcome'),
 'INSIDE', 'National Hospital Abuja inside mapped Area Council');
select is((select private.classify_destination_coordinate_v1('ng-gombe','ng-gombe-gombe',10.2990598,11.1363065)->>'outcome'),
 'INSIDE', 'Federal Teaching Hospital Gombe inside mapped local unit');
select is((select private.classify_destination_coordinate_v1('ng-delta','ng-delta-ethiope-east',5.7927494,6.1216518)->>'outcome'),
 'INSIDE', 'DELSU Abraka inside mapped local unit');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)->>'boundary_dataset_version'),
 'alabarin-2026-10-03-v1', 'classifier returns actual geometry version');
select is((select private.classify_destination_coordinate_v1('ng-unknown','ng-kwara-offa',8.1560008,4.7227435)->>'outcome'),
 'INVALID_CONTEXT', 'unknown region rejected');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-unknown',8.1560008,4.7227435)->>'outcome'),
 'INVALID_CONTEXT', 'unknown local unit rejected');
select is((select private.classify_destination_coordinate_v1('ng-lagos','ng-kwara-offa',8.1560008,4.7227435)->>'outcome'),
 'INVALID_CONTEXT', 'wrong parent relationship rejected');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',91,4.7)->>'outcome'),
 'INVALID_CONTEXT', 'invalid latitude rejected');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',0,0)->>'outcome'),
 'OUTSIDE_SELECTED_UNIT', 'point outside selected State and local unit is outside');
select is((select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435,'missing-version')->>'outcome'),
 'UNAVAILABLE', 'missing boundary version is unavailable');
select ok((select gis.ST_Contains(geom,gis.ST_SetSRID(gis.ST_MakePoint(3.396625,6.463625),4326))
 from private.boundary_regions where region_id='ng-lagos'), 'Lagos gap fixture is strictly inside State geometry');
select is((select count(*) from private.boundary_local_units where region_id='ng-lagos'
 and gis.ST_Covers(geom,gis.ST_SetSRID(gis.ST_MakePoint(3.396625,6.463625),4326))),
 0::bigint, 'Lagos gap point belongs to no mapped local unit');

select ok(not has_schema_privilege('anon','gis','USAGE') and not has_schema_privilege('authenticated','gis','USAGE'),
 'clients cannot use PostGIS schema directly');
select ok(not has_table_privilege('anon','private.boundary_local_units','SELECT') and
 not has_table_privilege('authenticated','private.boundary_local_units','SELECT') and
 not has_table_privilege('authenticated','private.boundary_regions','INSERT'),
 'client roles lack boundary table read and write grants');
select ok(not has_function_privilege('anon','public.verify_destination_coordinate_v1(text,text,double precision,double precision)','EXECUTE')
 and has_function_privilege('authenticated','public.verify_destination_coordinate_v1(text,text,double precision,double precision)','EXECUTE'),
 'only authenticated role can call public verifier');
select ok(not has_function_privilege('authenticated','private.classify_destination_coordinate_v1(text,text,double precision,double precision,text)','EXECUTE'),
 'authenticated role cannot call internal classifier');
select ok((select prosecdef from pg_proc where oid='private.classify_destination_coordinate_v1(text,text,double precision,double precision,text)'::regprocedure)
 and (select prosecdef from pg_proc where oid='public.verify_destination_coordinate_v1(text,text,double precision,double precision)'::regprocedure),
 'internal and public functions use trusted definer rights');
select ok((select 'search_path=""'=any(proconfig) from pg_proc
 where oid='private.classify_destination_coordinate_v1(text,text,double precision,double precision,text)'::regprocedure),
 'internal classifier pins empty search_path');

insert into auth.users(id,instance_id,aud,role,email,email_confirmed_at,created_at,updated_at)
values ('10000000-0000-4000-8000-0000000000f1','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','boundary-owner@example.invalid',now(),now(),now()),
 ('10000000-0000-4000-8000-0000000000f2','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','boundary-no-profile@example.invalid',now(),now(),now());
insert into public.traveller_profiles(owner_id,full_name,handle)
values ('10000000-0000-4000-8000-0000000000f1','Boundary Owner','boundary_owner');

set local role anon;
select throws_ok($$select public.verify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)$$,
 '42501', null, 'anonymous caller cannot execute verifier');
select throws_ok($$select count(*) from private.boundary_local_units$$,
 '42501', null, 'anonymous caller cannot read raw boundary polygons');
reset role;
set local role authenticated;
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000f2';
select throws_ok($$select public.verify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)$$,
 '42501', 'Authenticated Traveller profile required', 'confirmed user without Traveller profile is denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000f1';
select is((select public.verify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)->>'outcome'),
 'INSIDE', 'authenticated profiled Traveller can verify coordinate');
select throws_ok($$select count(*) from private.boundary_local_units$$,
 '42501', null, 'authenticated caller cannot read raw boundary polygons');
select throws_ok($$update private.boundary_regions set grid3_name='changed' where region_id='ng-kwara'$$,
 '42501', null, 'authenticated caller cannot mutate boundary rows');
select throws_ok($$select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)$$,
 '42501', null, 'authenticated caller cannot directly invoke internal classifier');
reset role;

select * from finish();
rollback;

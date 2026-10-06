begin;
select no_plan();

select ok((select prosecdef from pg_proc where oid=
 'public.get_destination_local_unit_viewport_v1(text,text)'::regprocedure), 'viewport uses trusted definer rights');
select ok((select 'search_path=""'=any(proconfig) from pg_proc where oid=
 'public.get_destination_local_unit_viewport_v1(text,text)'::regprocedure), 'viewport pins empty search_path');
select ok(has_function_privilege('authenticated','public.get_destination_local_unit_viewport_v1(text,text)','EXECUTE')
 and not has_function_privilege('anon','public.get_destination_local_unit_viewport_v1(text,text)','EXECUTE')
 and not has_function_privilege('service_role','public.get_destination_local_unit_viewport_v1(text,text)','EXECUTE'),
 'only authenticated clients have viewport EXECUTE');

insert into auth.users(id,instance_id,aud,role,email,email_confirmed_at,is_anonymous,created_at,updated_at)
values ('10000000-0000-4000-8000-0000000000d1','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','viewport-owner@example.invalid',now(),false,now(),now()),
 ('10000000-0000-4000-8000-0000000000d2','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','viewport-no-profile@example.invalid',now(),false,now(),now()),
 ('10000000-0000-4000-8000-0000000000d3','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','viewport-unconfirmed@example.invalid',null,false,now(),now()),
 ('10000000-0000-4000-8000-0000000000d4','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','viewport-anonymous@example.invalid',now(),true,now(),now());
insert into public.traveller_profiles(owner_id,full_name,handle)
values ('10000000-0000-4000-8000-0000000000d1','Viewport Owner','viewport_owner'),
 ('10000000-0000-4000-8000-0000000000d3','Viewport Unconfirmed','viewport_unconfirmed'),
 ('10000000-0000-4000-8000-0000000000d4','Viewport Anonymous','viewport_anonymous');

set local role anon;
select throws_ok($$select public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')$$,
 '42501',null,'anon denied');
reset role;
set local role authenticated;
set local request.jwt.claim.sub='';
select throws_ok($$select public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')$$,
 '42501','Authenticated Traveller profile required','missing identity denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d2';
select throws_ok($$select public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')$$,
 '42501','Authenticated Traveller profile required','missing Traveller profile denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d3';
select throws_ok($$select public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')$$,
 '42501','Authenticated Traveller profile required','unconfirmed account denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d4';
select throws_ok($$select public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')$$,
 '42501','Authenticated Traveller profile required','anonymous Auth account denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d1';
select is(public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')->>'status',
 'AVAILABLE','verified Traveller can get Offa viewport');
select is(public.get_destination_local_unit_viewport_v1('ng-fct','ng-fct-abuja-municipal')->>'status',
 'AVAILABLE','verified Traveller can get AMAC viewport');
select is(public.get_destination_local_unit_viewport_v1('ng-lagos','ng-kwara-offa')->>'status',
 'INVALID_CONTEXT','wrong parent rejected');
select is(public.get_destination_local_unit_viewport_v1('ng-unknown','ng-kwara-offa')->>'status',
 'INVALID_CONTEXT','unknown region rejected');
select is(public.get_destination_local_unit_viewport_v1('ng-kwara','ng-unknown')->>'status',
 'INVALID_CONTEXT','unknown unit rejected');
select is(public.get_destination_local_unit_viewport_v1(null,'ng-kwara-offa')->>'status',
 'INVALID_CONTEXT','null ID rejected');
select is(public.get_destination_local_unit_viewport_v1('NG-Kwara','ng-kwara-offa')->>'status',
 'INVALID_CONTEXT','noncanonical ID syntax rejected');
select throws_ok($$select count(*) from private.boundary_local_units$$,'42501',null,'raw polygons remain inaccessible');
select throws_ok($$select count(*) from private.boundary_dataset_versions$$,'42501',null,'raw dataset metadata remains inaccessible');
reset role;

create temp table viewport_results as select u.local_unit_id,u.region_id,u.geom,
 public.get_destination_local_unit_viewport_v1(u.region_id,u.local_unit_id) result
 from private.boundary_local_units u where u.local_unit_id in ('ng-kwara-offa','ng-fct-abuja-municipal');
select is((select count(*) from viewport_results where
 (result->>'west')::double precision = gis.ST_XMin(gis.Box3D(geom)) and
 (result->>'south')::double precision = gis.ST_YMin(gis.Box3D(geom)) and
 (result->>'east')::double precision = gis.ST_XMax(gis.Box3D(geom)) and
 (result->>'north')::double precision = gis.ST_YMax(gis.Box3D(geom))),2::bigint,'bounds enclose actual operational geometry');
select is((select count(*) from viewport_results where
 (result->>'west')::double precision between -180 and 180 and
 (result->>'east')::double precision between -180 and 180 and
 (result->>'south')::double precision between -90 and 90 and
 (result->>'north')::double precision between -90 and 90 and
 (result->>'west')::double precision < (result->>'east')::double precision and
 (result->>'south')::double precision < (result->>'north')::double precision),2::bigint,'bounds are finite, geographic and ordered');
select is((select count(*) from viewport_results where result->>'boundary_dataset_id'='NG_OPERATIONAL_BOUNDARIES'
 and result->>'boundary_dataset_version'=(select version from private.boundary_dataset_versions
 where dataset_id='NG_OPERATIONAL_BOUNDARIES' order by snapshot_date desc,version desc limit 1)),
 2::bigint,'actual latest boundary version returned');
select is((select array_agg(key order by key)::text from jsonb_object_keys(
 public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')) key),
 '{boundary_dataset_id,boundary_dataset_version,east,north,south,status,west}',
 'response exposes only camera bounds and version, no geometry or destination');

update private.boundary_dataset_versions set expected_local_unit_count=expected_local_unit_count+1
 where dataset_id='NG_OPERATIONAL_BOUNDARIES';
select is(public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa'),
 '{"status":"UNAVAILABLE"}'::jsonb,'incomplete snapshot fails closed without partial bounds');
update private.boundary_dataset_versions set expected_local_unit_count=expected_local_unit_count-1
 where dataset_id='NG_OPERATIONAL_BOUNDARIES';

update private.boundary_local_units set local_unit_id='ng-kwara-unmapped' where local_unit_id='ng-kwara-offa';
select is(public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa')->>'status',
 'UNAVAILABLE','missing selected mapping fails closed even with complete counts');
update private.boundary_local_units set local_unit_id='ng-kwara-offa' where local_unit_id='ng-kwara-unmapped';

update private.boundary_local_units set geom=gis.ST_GeomFromText(
 'MULTIPOLYGON(((4 8,4.1 8,4.1 8.1,4 8.1,4 8)),((4.3 8.2,4.4 8.2,4.4 8.3,4.3 8.3,4.3 8.2)))',4326)
 where local_unit_id='ng-kwara-offa';
select is(public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa'),
 '{"status":"AVAILABLE","west":4,"south":8,"east":4.4,"north":8.3,"boundary_dataset_id":"NG_OPERATIONAL_BOUNDARIES","boundary_dataset_version":"alabarin-2026-10-03-v1"}'::jsonb,
 'bounds include every multipart component');
update private.boundary_local_units u set geom=v.geom from viewport_results v
 where u.local_unit_id=v.local_unit_id and u.local_unit_id='ng-kwara-offa';

update private.boundary_local_units set geom=gis.ST_GeomFromText(
 'MULTIPOLYGON(((179 8,179.5 8,179.5 8.1,179 8.1,179 8)),((-179.5 8,-179 8,-179 8.1,-179.5 8.1,-179.5 8)))',4326)
 where local_unit_id='ng-kwara-offa';
select is(public.get_destination_local_unit_viewport_v1('ng-kwara','ng-kwara-offa'),
 '{"status":"UNAVAILABLE"}'::jsonb,'antimeridian-spanning bounds fail closed');
update private.boundary_local_units u set geom=v.geom from viewport_results v
 where u.local_unit_id=v.local_unit_id and u.local_unit_id='ng-kwara-offa';

select * from finish();
rollback;

begin;
select no_plan();

select ok((select prosecdef from pg_proc where oid=
 'public.classify_destination_coordinate_service_v1(text,text,double precision,double precision)'::regprocedure),
 'service wrapper runs with trusted definer rights');
select ok((select 'search_path=""'=any(proconfig) from pg_proc where oid=
 'public.classify_destination_coordinate_service_v1(text,text,double precision,double precision)'::regprocedure),
 'service wrapper pins empty search_path');
select ok(not has_function_privilege('anon',
 'public.classify_destination_coordinate_service_v1(text,text,double precision,double precision)','EXECUTE')
 and not has_function_privilege('authenticated',
 'public.classify_destination_coordinate_service_v1(text,text,double precision,double precision)','EXECUTE')
 and has_function_privilege('service_role',
 'public.classify_destination_coordinate_service_v1(text,text,double precision,double precision)','EXECUTE'),
 'only service_role has wrapper EXECUTE');
select ok(not has_function_privilege('service_role',
 'private.classify_destination_coordinate_v1(text,text,double precision,double precision,text)','EXECUTE'),
 'service_role cannot directly execute internal classifier');
select ok(not has_table_privilege('service_role','private.boundary_local_units','SELECT'),
 'service_role has no direct raw geometry read grant');

set local role anon;
select throws_ok($$select public.classify_destination_coordinate_service_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)$$,
 '42501', null, 'anon cannot execute service wrapper');
reset role;
set local role authenticated;
select throws_ok($$select public.classify_destination_coordinate_service_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435)$$,
 '42501', null, 'authenticated client cannot execute service wrapper');
reset role;
set local role service_role;
select is((select public.classify_destination_coordinate_service_v1(
 'ng-fct','ng-fct-abuja-municipal',9.039929,7.4619731)->>'outcome'),
 'INSIDE', 'service role classifies National Hospital coordinate inside FCT Area Council');
select is((select public.classify_destination_coordinate_service_v1(
 'ng-kwara','ng-kwara-offa',8.1998492,4.6316354)->>'outcome'),
 'OUTSIDE_SELECTED_UNIT', 'service role classifies Oyun point outside Offa');
select is((select public.classify_destination_coordinate_service_v1(
 'ng-kwara','ng-kwara-offa',8.14435408227513,4.65057447773)->>'outcome'),
 'BOUNDARY_AMBIGUOUS', 'service role preserves Offa shared border ambiguity');
select is((select public.classify_destination_coordinate_service_v1(
 'ng-lagos','ng-lagos-eti-osa',6.463625,3.396625)->>'outcome'),
 'COVERAGE_UNRESOLVED', 'service role preserves audited Lagos gap uncertainty');
select is((select public.classify_destination_coordinate_service_v1(
 'ng-fct','ng-fct-abuja-municipal',9.039929,7.4619731)->>'boundary_dataset_version'),
 'alabarin-2026-10-03-v1', 'service result returns geometry version rather than admin version');
select is((select array_agg(key order by key)::text from jsonb_object_keys(
 public.classify_destination_coordinate_service_v1(
 'ng-fct','ng-fct-abuja-municipal',9.039929,7.4619731)) as key),
 '{boundary_dataset_id,boundary_dataset_version,outcome}',
 'service result contains exactly the minimal classifier fields');
reset role;

select * from finish();
rollback;

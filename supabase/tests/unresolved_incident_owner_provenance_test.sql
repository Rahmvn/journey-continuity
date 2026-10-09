-- Rollback-only pgTAP fixture on a fully migrated disposable local database.
begin;
select no_plan();

select ok(has_function_privilege('authenticated',
  'public.get_owner_unresolved_incident_finalization_v1(uuid)','EXECUTE'),
  'authenticated owner can invoke the read');
select ok(not has_function_privilege('anon',
  'public.get_owner_unresolved_incident_finalization_v1(uuid)','EXECUTE'),
  'anonymous role cannot invoke the read');
select ok(not has_table_privilege('authenticated',
  'private.unresolved_incident_finalizations','SELECT,INSERT,UPDATE,DELETE'),
  'private finalization table remains inaccessible');
select ok(not has_table_privilege('authenticated',
  'private.unresolved_boundary_admissions','SELECT'),
  'private admission provenance remains inaccessible');
select is((select count(*) from private.unresolved_incident_finalizations),0::bigint,
  'migration did not backfill finalizations');
select ok((select p.provolatile='s' and p.prosecdef and
  p.proconfig @> array['search_path=""']::text[] from pg_proc p
  where p.oid='public.get_owner_unresolved_incident_finalization_v1(uuid)'::regprocedure),
  'read is STABLE SECURITY DEFINER with empty search_path');

insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
values
 ('10000000-0000-4000-8000-0000000000d1','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','read-owner@example.invalid',now(),now(),false),
 ('10000000-0000-4000-8000-0000000000d2','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','read-other@example.invalid',now(),now(),false),
 ('10000000-0000-4000-8000-0000000000d3','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','read-anon@example.invalid',now(),now(),true);

set local role authenticated;
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d1';
with t as (select date_trunc('milliseconds',clock_timestamp())-interval '3 days' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-0000000000d1',
 '10000000-0000-4000-8000-0000000000d1','Fixture',admitted,
 admitted+interval '1 hour','ACTIVE','30000000-0000-4000-8000-0000000000d1',
 'alabarin-unresolved-boundary-v1' from t;
with t as (select date_trunc('milliseconds',clock_timestamp())-interval '3 days' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-0000000000d2',
 '10000000-0000-4000-8000-0000000000d1','Fixture',admitted,
 admitted+interval '1 hour','ACTIVE','30000000-0000-4000-8000-0000000000d2',
 'alabarin-unresolved-boundary-v1' from t;
with t as (select date_trunc('milliseconds',clock_timestamp())-interval '3 days' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select ('20000000-0000-4000-8000-0000000000d'||n)::uuid,
 '10000000-0000-4000-8000-0000000000d1','Fixture',admitted,
 admitted+interval '1 hour','ACTIVE',('30000000-0000-4000-8000-0000000000d'||n)::uuid,
 'alabarin-unresolved-boundary-v1' from t cross join generate_series(3,5) n;
reset role;
insert into public.journey_monitoring_state(journey_id,owner_id,phase,last_cloud_contact_at)
values ('20000000-0000-4000-8000-0000000000d1',
 '10000000-0000-4000-8000-0000000000d1','EVIDENCE_FRESH',clock_timestamp()-interval '1 hour');
select public.evaluate_due_journeys();

set local role authenticated;
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d1')$$,'22023',
 'Journey has no unresolved incident finalization',
 'ACTIVE Journey cannot fabricate an incident');
create temporary table read_identity as select * from public.get_unresolved_boundary_eligibility_v1(
 '20000000-0000-4000-8000-0000000000d1');
select is((select f.decision from read_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'UNRESOLVED_INCIDENT',
 '10B finalizer commits an eligible incident');
reset role;

-- A new authenticated session with no local finalizer claim reads the exact
-- immutable record. Session identity changes; owner identity remains exact.
set local request.jwt.claim.session_id='50000000-0000-4000-8000-0000000000d2';
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d1';
set local role authenticated;
create temporary table owner_read as select * from
 public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d1');
select is((select decision from owner_read),'UNRESOLVED_INCIDENT',
 'second same-owner session reads committed outcome');
select ok((select r.journey_status='UNRESOLVED_INCIDENT' and
 r.ended_at=r.finalized_at and r.journey_id=i.journey_id and
 r.owner_id='10000000-0000-4000-8000-0000000000d1'::uuid and
 r.admission_id=i.admission_id and r.policy_version=i.policy_version and
 r.boundary_at=i.boundary_at and r.verification_case_id=i.verification_case_id and
 r.opening_event_id=i.opening_event_id and
 r.verifying_started_at=i.verifying_started_at and
 r.guard_version=i.guard_version and
 r.history_baseline_at=i.history_baseline_at
 from owner_read r cross join read_identity i),
 'cross-device read has exact 10B request and terminal identity');
reset role;
select ok((select r.original_eta=f.original_eta and r.finalized_at=f.finalized_at and
 r.access_expires_at=f.access_expires_at and r.source=f.source
 from owner_read r join private.unresolved_incident_finalizations f using(journey_id)),
 'timestamps and source exactly match the immutable 10B row');
select is((select access_expires_at-finalized_at from owner_read),interval '168 hours',
 'returned expiry is exactly seven elapsed days');
select ok((select to_jsonb(r) ?& array['decision','journey_status','ended_at',
 'boundary_at','verification_case_id','access_expires_at','journey_id','owner_id',
 'admission_id','policy_version','original_eta','opening_event_id',
 'verifying_started_at','guard_version','history_baseline_at','finalized_at','source']
 and not (to_jsonb(r) ?| array['latitude','longitude','destination','reports',
 'case_content','location_points','trusted_contact_reports','token'])
 from owner_read r),'response has bounded provenance and no sensitive case payload');
select is((select count(*) from private.unresolved_incident_finalizations),1::bigint,
 'read creates no duplicate finalization');
select is((select to_jsonb(r) from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d1') r),
 (select to_jsonb(r) from owner_read r),'duplicate read is stable');
update read_identity set verification_case_id='50000000-0000-4000-8000-0000000000d1';
select is((select to_jsonb(r) from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d1') r),
 (select to_jsonb(r) from owner_read r),
 'stale device case identity cannot alter authoritative read');

set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d2';
set local role authenticated;
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d1')$$,'42501',
 'Journey is not available to this caller','wrong owner gets nondisclosing denial');
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-000000000099')$$,'42501',
 'Journey is not available to this caller','nonexistent gets the same denial');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d3';
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d1')$$,'42501',
 'Non-anonymous Traveller identity required','anonymous identity is denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000d1';
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d2')$$,'22023',
 'Journey has no unresolved incident finalization','ACTIVE Journey has no result');
reset role;

update public.journeys set status='COMPLETED',completed_at=clock_timestamp()
 where id='20000000-0000-4000-8000-0000000000d2';
set local role authenticated;
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d2')$$,'22023',
 'Journey has no unresolved incident finalization','COMPLETED is not an incident');
reset role;
update public.journeys set status='CANCELLED',completed_at=null,
 ended_at=clock_timestamp() where id='20000000-0000-4000-8000-0000000000d3';
update public.journeys set status='AUTO_ENDED',ended_at=clock_timestamp()
 where id='20000000-0000-4000-8000-0000000000d4';
-- Simulate a corrupt terminal row in this disposable rollback fixture only.
-- The owner RPC must fail closed instead of inventing missing provenance.
update public.journeys set status='UNRESOLVED_INCIDENT',ended_at=clock_timestamp()
 where id='20000000-0000-4000-8000-0000000000d5';
set local role authenticated;
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d3')$$,'22023',
 'Journey has no unresolved incident finalization','CANCELLED is not an incident');
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d4')$$,'22023',
 'Journey has no unresolved incident finalization','AUTO_ENDED is not an incident');
select throws_ok($$select * from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d5')$$,'23514',
 'Inconsistent unresolved finalization provenance','missing provenance fails closed');
select is((select r.access_expires_at from public.get_owner_unresolved_incident_finalization_v1(
 '20000000-0000-4000-8000-0000000000d1') r),
 (select access_expires_at from owner_read),
 'read after simulated expiry still reports immutable original expiry');
reset role;
select ok(not private.trusted_incident_detail_allowed_at(
 '10000000-0000-4000-8000-0000000000d2',
 '20000000-0000-4000-8000-0000000000d1',
 (select access_expires_at from owner_read)),
 'owner read does not reopen contact detail at expiry');
select is((select count(*) from private.unresolved_incident_finalizations),1::bigint,
 'all owner reads had no finalization side effects');
select * from finish();
rollback;

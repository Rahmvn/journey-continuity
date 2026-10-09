-- Disposable fully migrated local database. Every fixture rolls back.
begin;
select no_plan();

select has_table('private','unresolved_incident_finalizations','distinct immutable finalization table');
select ok(has_function_privilege('authenticated',
  'public.finalize_unresolved_incident_v1(uuid,uuid,timestamptz,text,uuid,bigint,timestamptz,bigint,timestamptz)',
  'EXECUTE'),'owner finalizer is callable');
select ok(not has_function_privilege('anon',
  'public.finalize_unresolved_incident_v1(uuid,uuid,timestamptz,text,uuid,bigint,timestamptz,bigint,timestamptz)',
  'EXECUTE'),'anonymous role cannot finalize');
select ok(not has_table_privilege('authenticated','private.unresolved_incident_finalizations','SELECT,INSERT,UPDATE,DELETE'),
  'owner cannot forge finalization provenance');
select ok(not has_table_privilege('authenticated','public.verification_cases','SELECT'),
 'contacts cannot bypass case RPC through direct SELECT');
select ok(not has_table_privilege('authenticated','public.verification_case_location_points','SELECT'),
 'contacts cannot directly read precise case points');
select ok(not has_table_privilege('authenticated','public.verification_case_reports','SELECT,INSERT'),
 'contacts cannot bypass read-only report RPC policy');
select is(extract(epoch from ((('2026-03-08 01:00 America/New_York'::timestamptz +
 interval '168 hours')-'2026-03-08 01:00 America/New_York'::timestamptz))),
 604800::numeric,'incident access uses elapsed hours across daylight saving');
select ok(not private.unresolved_incident_boundary_reached(
 '2026-10-09 00:00 UTC','2026-10-08 23:59:59.999 UTC'),
 'authoritative finalizer boundary rejects one millisecond early');
select ok(private.unresolved_incident_boundary_reached(
 '2026-10-09 00:00 UTC','2026-10-09 00:00 UTC'),
 'authoritative finalizer boundary permits exact instant');
select ok(private.unresolved_incident_boundary_reached(
 '2026-10-09 00:00 UTC','2026-10-09 00:00:00.001 UTC'),
 'authoritative finalizer boundary permits later instant');
select is((select count(*) from private.unresolved_incident_finalizations),0::bigint,
  'migration backfills no incident');
select ok((select status='ACTIVE' from public.journeys
  where id='20000000-0000-4000-8000-000000000040'),
  'historical Journey is unchanged');

insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
values
 ('10000000-0000-4000-8000-0000000000b1','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','incident-owner@example.invalid',now(),now(),false),
 ('10000000-0000-4000-8000-0000000000b2','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','incident-other@example.invalid',now(),now(),false),
 ('10000000-0000-4000-8000-0000000000b3','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','incident-contact@example.invalid',now(),now(),false),
 ('10000000-0000-4000-8000-0000000000b4','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','incident-anon@example.invalid',now(),now(),true);
update auth.users set email_confirmed_at=now() where id='10000000-0000-4000-8000-0000000000b3';

set local role authenticated;
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000b1';
with times as (select date_trunc('milliseconds',clock_timestamp())-interval '3 days' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-0000000000b1','10000000-0000-4000-8000-0000000000b1',
 'Fixture',admitted,admitted+interval '1 hour','ACTIVE',
 '30000000-0000-4000-8000-0000000000b1','alabarin-unresolved-boundary-v1' from times;
with times as (select date_trunc('milliseconds',clock_timestamp())-interval '1 hour' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-0000000000b2','10000000-0000-4000-8000-0000000000b1',
 'Fixture',admitted,admitted+interval '2 hours','ACTIVE',
 '30000000-0000-4000-8000-0000000000b2','alabarin-unresolved-boundary-v1' from times;
with times as (select date_trunc('milliseconds',clock_timestamp())-interval '3 days' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-0000000000b5','10000000-0000-4000-8000-0000000000b1',
 'Fixture',admitted,admitted+interval '1 hour','ACTIVE',
 '30000000-0000-4000-8000-0000000000b5','alabarin-unresolved-boundary-v1' from times;
with times as (select date_trunc('milliseconds',clock_timestamp())-interval '3 days' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-0000000000b6','10000000-0000-4000-8000-0000000000b1',
 'Fixture',admitted,admitted+interval '1 hour','ACTIVE',
 '30000000-0000-4000-8000-0000000000b6','alabarin-unresolved-boundary-v1' from times;
with times as (select date_trunc('milliseconds',clock_timestamp())-interval '3 days' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-0000000000b7','10000000-0000-4000-8000-0000000000b1',
 'Fixture',admitted,admitted+interval '1 hour','ACTIVE',
 '30000000-0000-4000-8000-0000000000b7','alabarin-unresolved-boundary-v1' from times;
reset role;

insert into public.journey_monitoring_state(journey_id,owner_id,phase,last_cloud_contact_at)
values
 ('20000000-0000-4000-8000-0000000000b1','10000000-0000-4000-8000-0000000000b1',
  'EVIDENCE_FRESH',clock_timestamp()-interval '1 hour'),
 ('20000000-0000-4000-8000-0000000000b2','10000000-0000-4000-8000-0000000000b1',
  'EVIDENCE_FRESH',clock_timestamp()-interval '1 hour'),
 ('20000000-0000-4000-8000-0000000000b5','10000000-0000-4000-8000-0000000000b1',
  'EVIDENCE_FRESH',clock_timestamp()-interval '1 hour'),
 ('20000000-0000-4000-8000-0000000000b6','10000000-0000-4000-8000-0000000000b1',
  'EVIDENCE_FRESH',clock_timestamp()-interval '1 hour'),
 ('20000000-0000-4000-8000-0000000000b7','10000000-0000-4000-8000-0000000000b1',
  'EVIDENCE_FRESH',clock_timestamp()-interval '1 hour');
select public.evaluate_due_journeys();
select is((select decision from private.read_unresolved_boundary(
 '20000000-0000-4000-8000-0000000000b1','10000000-0000-4000-8000-0000000000b1',clock_timestamp())),
 'ELIGIBLE','current exact VERIFYING and OPEN case qualify');
insert into public.verification_case_location_points(case_id,position,telemetry_sequence,
 event_time,latitude,longitude,accuracy_meters,connectivity_state)
select id,1,1,clock_timestamp()-interval '2 hours',0,0,10,'WIFI'
from public.verification_cases where journey_id='20000000-0000-4000-8000-0000000000b1';
insert into public.trusted_contact_relationships(id,traveller_user_id,contact_user_id,
 display_name,contact_email_normalized,status)
values ('40000000-0000-4000-8000-0000000000b3','10000000-0000-4000-8000-0000000000b1',
 '10000000-0000-4000-8000-0000000000b3','Contact','incident-contact@example.invalid','ACCEPTED');
insert into public.journey_trusted_contact_access(journey_id,relationship_id,traveller_user_id,
 contact_user_id)
values ('20000000-0000-4000-8000-0000000000b1','40000000-0000-4000-8000-0000000000b3',
 '10000000-0000-4000-8000-0000000000b1','10000000-0000-4000-8000-0000000000b3');

set local role authenticated;
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000b2';
select throws_ok($$select * from public.finalize_unresolved_incident_v1(
 '20000000-0000-4000-8000-0000000000b1',null,null,null,null,null,null,null,null)$$,
 '42501','Journey is not available to this caller','wrong owner nondisclosing');
select throws_ok($$select * from public.finalize_unresolved_incident_v1(
 '20000000-0000-4000-8000-000000000099',null,null,null,null,null,null,null,null)$$,
 '42501','Journey is not available to this caller','nonexistent same response');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000b4';
select throws_ok($$select * from public.finalize_unresolved_incident_v1(
 '20000000-0000-4000-8000-0000000000b1',null,null,null,null,null,null,null,null)$$,
 '42501','Non-anonymous Traveller identity required','anonymous identity denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000b1';
select throws_ok($$update public.journeys set status='UNRESOLVED_INCIDENT',ended_at=now()
 where id='20000000-0000-4000-8000-0000000000b1'$$,'42501',null,
 'owner cannot directly forge incident');

-- Resolve the exact arguments from the owner-scoped 10A observation.
create temporary table incident_b1_identity as
select * from public.get_unresolved_boundary_eligibility_v1(
 '20000000-0000-4000-8000-0000000000b1');
create temporary table incident_b2_identity as
select * from public.get_unresolved_boundary_eligibility_v1(
 '20000000-0000-4000-8000-0000000000b2');
create temporary table incident_b5_identity as
select * from public.get_unresolved_boundary_eligibility_v1(
 '20000000-0000-4000-8000-0000000000b5');
create temporary table incident_b6_identity as
select * from public.get_unresolved_boundary_eligibility_v1(
 '20000000-0000-4000-8000-0000000000b6');
create temporary table incident_b7_identity as
select * from public.get_unresolved_boundary_eligibility_v1(
 '20000000-0000-4000-8000-0000000000b7');
select is((select f.decision from incident_b2_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'TOO_EARLY',
 'server rejects before its exact boundary');
select is((select status from public.journeys where id='20000000-0000-4000-8000-0000000000b2'),
 'ACTIVE','too-early Journey remains ACTIVE');
select is((select f.decision from incident_b1_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version+1,i.history_baseline_at) f),'BLOCKED',
 'stale or invented guard revision cannot finalize');
select is((select f.decision from incident_b1_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,'50000000-0000-4000-8000-0000000000b1',i.opening_event_id,
 i.verifying_started_at,i.guard_version,i.history_baseline_at) f),'BLOCKED',
 'different verification case cannot stand in for current case');
select is((select f.decision from incident_b1_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'UNRESOLVED_INCIDENT',
 'authoritative finalizer commits exact eligible case');
select is((select status from public.journeys where id='20000000-0000-4000-8000-0000000000b1'),
 'UNRESOLVED_INCIDENT','incident is a distinct terminal status');
reset role;
select ok((select access_expires_at=finalized_at+interval '168 hours' and
 boundary_at <= finalized_at and verification_case_id=(select id from public.verification_cases
 where journey_id='20000000-0000-4000-8000-0000000000b1')
 from private.unresolved_incident_finalizations where journey_id='20000000-0000-4000-8000-0000000000b1'),
 'immutable provenance and exact seven elapsed days');
select is((select phase from public.journey_monitoring_state
 where journey_id='20000000-0000-4000-8000-0000000000b1'),'CLOSED',
 'authoritative monitoring closes');
select is((select status from public.verification_cases
 where journey_id='20000000-0000-4000-8000-0000000000b1'),'OPEN',
 'genuinely unresolved case is preserved OPEN');
select is((select count(*) from public.verification_case_location_points
 where case_id=(select id from public.verification_cases
 where journey_id='20000000-0000-4000-8000-0000000000b1')),1::bigint,
 'existing authenticated case-point evidence is retained');
select is((select count(*) from public.journey_monitoring_events
 where journey_id='20000000-0000-4000-8000-0000000000b1' and
 reason_code='UNRESOLVED_BOUNDARY_MONITORING_ENDED'),1::bigint,
 'distinct monitoring closure provenance');
select is((select count(*) from public.journey_monitoring_events
 where journey_id='20000000-0000-4000-8000-0000000000b1' and
 reason_code='DEVICE_CONTACT_RESTORED'),0::bigint,
 'no false contact restoration');
select ok((select sensitive_access_expires_at =
 (select access_expires_at from private.unresolved_incident_finalizations
 where journey_id='20000000-0000-4000-8000-0000000000b1')
 from public.journey_trusted_contact_access
 where journey_id='20000000-0000-4000-8000-0000000000b1'),
 'existing grant is capped to immutable incident expiry');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000b3';
select ok(private.trusted_incident_detail_allowed_at(
 '10000000-0000-4000-8000-0000000000b3','20000000-0000-4000-8000-0000000000b1',
 (select access_expires_at-interval '1 microsecond' from private.unresolved_incident_finalizations
 where journey_id='20000000-0000-4000-8000-0000000000b1')),
 'verified accepted contact may read just before expiry');
select ok(not private.trusted_incident_detail_allowed_at(
 '10000000-0000-4000-8000-0000000000b3','20000000-0000-4000-8000-0000000000b1',
 (select access_expires_at from private.unresolved_incident_finalizations
 where journey_id='20000000-0000-4000-8000-0000000000b1')),
 'incident detail denied at exact expiry');
select ok(not private.trusted_incident_detail_allowed_at(
 '10000000-0000-4000-8000-0000000000b3','20000000-0000-4000-8000-0000000000b1',
 (select access_expires_at+interval '1 microsecond' from private.unresolved_incident_finalizations
 where journey_id='20000000-0000-4000-8000-0000000000b1')),
 'incident detail denied after expiry');
set local role authenticated;
select ok((select (public.get_verification_case(verification_case_id)->>'status')='OPEN'
 from incident_b1_identity),
 'real case-detail route allows verified contact during window');
select throws_ok($$select public.submit_verification_case_report(
 (select verification_case_id from incident_b1_identity),
 'OTHER',now(),'A note')$$,'42501','Reporting is closed for this incident',
 'no post-terminal report submission');
select ok((public.get_trusted_journey_snapshot('20000000-0000-4000-8000-0000000000b1')->>'available_case_id') is not null,
 'snapshot retains case link only inside incident window');
select ok((select journey_status='UNRESOLVED_INCIDENT' from public.list_trusted_journeys()
 where journey_id='20000000-0000-4000-8000-0000000000b1'),
 'legacy trusted summary remains factual');
select ok((select journey_status='UNRESOLVED_INCIDENT' from public.list_trusted_journey_statuses_v1()
 where journey_id='20000000-0000-4000-8000-0000000000b1'),
 'status v1 projects distinct terminal state');
select ok((select journey_status='UNRESOLVED_INCIDENT' from public.list_trusted_journey_statuses_v2()
 where journey_id='20000000-0000-4000-8000-0000000000b1'),
 'status v2 projects distinct terminal state');
select is(public.get_trusted_journey_status_v1('20000000-0000-4000-8000-0000000000b1')->>'journey_status',
 'UNRESOLVED_INCIDENT','v1 status wrapper preserves distinct status');
select is(public.get_trusted_journey_status_v2('20000000-0000-4000-8000-0000000000b1')->>'journey_status',
 'UNRESOLVED_INCIDENT','v2 status wrapper preserves distinct status');
reset role;
update auth.users set email_confirmed_at=null
where id='10000000-0000-4000-8000-0000000000b3';
select ok(not private.trusted_incident_detail_allowed_at(
 '10000000-0000-4000-8000-0000000000b3','20000000-0000-4000-8000-0000000000b1',
 clock_timestamp()),'unverified contact cannot read incident detail');
set local role authenticated;
select throws_ok($$select public.list_trusted_journeys()$$,
 '42501','Verified trusted identity required','unverified summary caller denied');
reset role;
update auth.users set email_confirmed_at=clock_timestamp()
where id='10000000-0000-4000-8000-0000000000b3';
update public.journey_trusted_contact_access set revoked_at=clock_timestamp()
where journey_id='20000000-0000-4000-8000-0000000000b1';
select ok(not private.trusted_incident_detail_allowed_at(
 '10000000-0000-4000-8000-0000000000b3','20000000-0000-4000-8000-0000000000b1',
 clock_timestamp()),'revocation immediately removes detail access');
set local role authenticated;
select throws_ok($$select public.get_verification_case(
 (select verification_case_id from incident_b1_identity))$$,
 '42501','Verification case is not available to this caller',
 'revoked contact cannot read case detail');
select is((select count(*) from public.list_trusted_journeys()
 where journey_id='20000000-0000-4000-8000-0000000000b1'),0::bigint,
 'revocation removes legacy summary');
select throws_ok($$select public.get_trusted_journey_status_v2(
 '20000000-0000-4000-8000-0000000000b1')$$,
 '42501','Journey is not available to this caller',
 'revocation removes status wrapper access');
reset role;
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000b1';
set local role authenticated;
select is((select f.decision from incident_b1_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'UNRESOLVED_INCIDENT',
 'identical retry returns committed outcome');
reset role;
select is((select count(*) from private.unresolved_incident_finalizations),1::bigint,
 'retry creates no second finalization');
set local role authenticated;
select throws_ok($$update public.journeys set status='ACTIVE',ended_at=null
 where id='20000000-0000-4000-8000-0000000000b1'$$,'JT001',null,
 'stale owner sync cannot reopen incident');
select throws_ok($$update public.journeys set status='COMPLETED',ended_at=null,completed_at=now()
 where id='20000000-0000-4000-8000-0000000000b1'$$,'JT001',null,
 'stale completion cannot replace incident');
select throws_ok($$select * from public.record_journey_heartbeat(
 '20000000-0000-4000-8000-0000000000b1',1,clock_timestamp(),
 null,null,'WIFI',0)$$,'22023','Heartbeat requires an ACTIVE Journey',
 'late authenticated heartbeat cannot restore incident Journey');
select throws_ok($$update private.unresolved_incident_finalizations
 set access_expires_at=access_expires_at+interval '1 day'$$,'42501',null,
 'owner cannot extend incident access');
select public.stop_journey_monitoring_v1('20000000-0000-4000-8000-0000000000b2');
select is((select f.decision from incident_b2_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'BLOCKED',
 'CANCELLED first remains authoritative');
update public.journeys set status='COMPLETED',completed_at=clock_timestamp()
where id='20000000-0000-4000-8000-0000000000b6';
select is((select f.decision from incident_b6_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'BLOCKED',
 'COMPLETED first remains authoritative');
reset role;
select public.resolve_open_verification_case('20000000-0000-4000-8000-0000000000b5',
 'DEVICE_CONTACT_RESTORED',clock_timestamp());
update public.journey_monitoring_state set phase='EVIDENCE_FRESH',verifying_started_at=null,
 last_cloud_contact_at=clock_timestamp()-interval '1 hour'
where journey_id='20000000-0000-4000-8000-0000000000b5';
select public.evaluate_due_journeys();
set local role authenticated;
select is((select f.decision from incident_b5_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'BLOCKED',
 'resolved old case cannot finalize after a new verification episode opens');
create temporary table incident_b5_fresh as
select * from public.get_unresolved_boundary_eligibility_v1(
 '20000000-0000-4000-8000-0000000000b5');
select ok((select fresh.verification_case_id<>old.verification_case_id and
 fresh.decision='ELIGIBLE' from incident_b5_fresh fresh,incident_b5_identity old),
 'late coherent verification has a new exact identity');
select is((select f.decision from incident_b5_fresh i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'UNRESOLVED_INCIDENT',
 'genuine late new episode may qualify immediately after original boundary');
reset role;
-- Simulate the already-authoritative Section 09 terminal outcome; its own RPC
-- is separately covered by the existing AUTO_ENDED tests.
update public.journeys set status='AUTO_ENDED',ended_at=date_trunc('milliseconds',clock_timestamp())
where id='20000000-0000-4000-8000-0000000000b7';
set local role authenticated;
select is((select f.decision from incident_b7_identity i,
 lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,i.boundary_at,
 i.policy_version,i.verification_case_id,i.opening_event_id,i.verifying_started_at,
 i.guard_version,i.history_baseline_at) f),'BLOCKED',
 'AUTO_ENDED first cannot be rewritten as unresolved incident');
reset role;

select * from finish();
rollback;

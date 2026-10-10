-- Disposable fully migrated local PostgreSQL only; every fixture rolls back.
begin;
select no_plan();

select has_function('public', 'get_owner_probable_end_auto_end_finalization_v1',
    array['uuid'], 'owner read takes only exact Journey ID, no original device claim');
select ok(has_function_privilege('authenticated',
    'public.get_owner_probable_end_auto_end_finalization_v1(uuid)', 'EXECUTE'),
    'authenticated owner RPC is granted');
select ok(not has_function_privilege('anon',
    'public.get_owner_probable_end_auto_end_finalization_v1(uuid)', 'EXECUTE'), 'anon lacks execute');
select ok(not has_function_privilege('service_role',
    'public.get_owner_probable_end_auto_end_finalization_v1(uuid)', 'EXECUTE'), 'no broad service-role grant');
select ok(not exists (select 1 from pg_proc p,
    lateral aclexplode(p.proacl) a where p.oid=
    'public.get_owner_probable_end_auto_end_finalization_v1(uuid)'::regprocedure
    and a.grantee=0 and a.privilege_type='EXECUTE'), 'PUBLIC lacks execute');
select ok((select prosecdef and provolatile='s' and proconfig=array['search_path=""']
    from pg_proc where oid='public.get_owner_probable_end_auto_end_finalization_v1(uuid)'::regprocedure),
    'stable definer uses a fixed empty search path');
select ok(not has_table_privilege('authenticated', 'private.probable_end_confirmation_windows', 'SELECT'),
    'private table SELECT remains unavailable');

insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
values
 ('10000000-0000-4000-8000-0000000000f1','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','auto-read-owner@example.invalid',now(),now(),false),
 ('10000000-0000-4000-8000-0000000000f2','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','auto-read-other@example.invalid',now(),now(),false),
 ('10000000-0000-4000-8000-0000000000f3','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','auto-read-anon@example.invalid',now(),now(),true);

set local role authenticated;
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000f1';
with t as (select date_trunc('milliseconds',clock_timestamp())-interval '2 hours' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
 unresolved_admission_id,unresolved_policy_version)
select ('20000000-0000-4000-8000-0000000000f'||n)::uuid,
 '10000000-0000-4000-8000-0000000000f1','Synthetic private destination',
 admitted,admitted+interval '1 hour','ACTIVE',
 ('60000000-0000-4000-8000-0000000000f'||n)::uuid,'alabarin-unresolved-boundary-v1'
from t cross join generate_series(1,9) n;
select public.record_journey_heartbeat('20000000-0000-4000-8000-0000000000f1',1,
    clock_timestamp(),null,null,'WIFI',0);
reset role;

-- Only the disposable fixture supplies past server registration, exactly as
-- existing Section 09 SQL acceptance does. Production constants/functions stay intact.
insert into private.probable_end_confirmation_windows(
 presentation_episode_id,journey_id,owner_id,candidate_episode_id,detector_generation,
 policy_version,history_baseline_at,guard_version,registered_at,not_before_at)
select '30000000-0000-4000-8000-0000000000f1',s.journey_id,s.owner_id,
 '40000000-0000-4000-8000-0000000000f1',17,'alabarin-probable-end-v1',
 date_trunc('milliseconds',s.verification_guard_baseline_at),s.verification_guard_version,
 date_trunc('milliseconds',now()-interval '31 minutes'),
 date_trunc('milliseconds',now()-interval '1 minute')
from public.journey_monitoring_state s where s.journey_id='20000000-0000-4000-8000-0000000000f1';
-- An older unfinalized episode must not be mistaken for the committed window.
insert into private.probable_end_confirmation_windows(
 presentation_episode_id,journey_id,owner_id,candidate_episode_id,detector_generation,
 policy_version,history_baseline_at,guard_version,registered_at,not_before_at)
select '30000000-0000-4000-8000-0000000000f9',s.journey_id,s.owner_id,
 '40000000-0000-4000-8000-0000000000f9',16,'alabarin-probable-end-v1',
 date_trunc('milliseconds',s.verification_guard_baseline_at),s.verification_guard_version,
 date_trunc('milliseconds',now()-interval '40 minutes'),
 date_trunc('milliseconds',now()-interval '10 minutes')
from public.journey_monitoring_state s where s.journey_id='20000000-0000-4000-8000-0000000000f1';
set local role authenticated;
select is((select decision from public.finalize_probable_end_auto_end_v1(
 '20000000-0000-4000-8000-0000000000f1','30000000-0000-4000-8000-0000000000f1')),
 'AUTO_ENDED','unchanged real Section 09 finalizer commits the outcome');
reset role;

-- Snapshot all persisted public/private rows, including access expiry, before reads.
create temporary table before_owner_reads as
select 'public.'||t.tablename as name,
 query_to_xml(format('select * from public.%I order by to_jsonb(%I)::text',t.tablename,t.tablename),false,true,'')::text as rows
from pg_tables t where t.schemaname='public'
union all
select 'private.'||t.tablename,
 query_to_xml(format('select * from private.%I order by to_jsonb(%I)::text',t.tablename,t.tablename),false,true,'')::text
from pg_tables t where t.schemaname='private';

set local request.jwt.claim.session_id='50000000-0000-4000-8000-0000000000f2';
set local role authenticated;
create temporary table auto_owner_read as select * from
 public.get_owner_probable_end_auto_end_finalization_v1('20000000-0000-4000-8000-0000000000f1');
select is((select decision from auto_owner_read),'AUTO_ENDED','same owner second session recovers without original claim');
select is((select count(*) from auto_owner_read),1::bigint,'exactly one terminal receipt');
select ok((select journey_id='20000000-0000-4000-8000-0000000000f1'::uuid and
 owner_id='10000000-0000-4000-8000-0000000000f1'::uuid and journey_status='AUTO_ENDED'
 from auto_owner_read),'exact owner/Journey/status binding');
select ok((select presentation_episode_id='30000000-0000-4000-8000-0000000000f1'::uuid and
 candidate_episode_id='40000000-0000-4000-8000-0000000000f1'::uuid and detector_generation=17
 from auto_owner_read),'exact presentation/candidate/generation, not older unfinalized episode');
select ok((select not_before_at=registered_at+interval '30 minutes' and finalized_at>=not_before_at
 and ended_at=finalized_at from auto_owner_read),'original exact window and terminal timestamps');
reset role;
select ok((select r.presentation_episode_id=w.presentation_episode_id and
 r.candidate_episode_id=w.candidate_episode_id and r.detector_generation=w.detector_generation and
 r.policy_version=w.policy_version and r.history_baseline_at=w.history_baseline_at and
 r.guard_version=w.guard_version and r.registered_at=w.registered_at and
 r.not_before_at=w.not_before_at and r.finalized_at=w.finalized_at and r.ended_at=j.ended_at
 from auto_owner_read r join private.probable_end_confirmation_windows w using(presentation_episode_id)
 join public.journeys j on j.id=w.journey_id),'every provenance field exactly matches committed data');
select is((select to_jsonb(r) from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f1') r),(select to_jsonb(r) from auto_owner_read r),
 'duplicate read is stable');
select is((select array_agg(k order by k) from auto_owner_read r,
 lateral jsonb_object_keys(to_jsonb(r)) k),
 array['candidate_episode_id','decision','detector_generation','ended_at','finalized_at',
 'guard_version','history_baseline_at','journey_id','journey_status','not_before_at',
 'owner_id','policy_version','presentation_episode_id','registered_at']::text[],
 'exact bounded response: no coordinates, reports, contact data, local claim, rendered presentation or user response');

set local role authenticated;
select throws_ok($$select * from private.probable_end_confirmation_windows$$,'42501',null,
 'authenticated direct table read is denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000f2';
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f1')$$,'42501','Journey is not available to this caller',
 'wrong owner gets nondisclosing denial');
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-000000000099')$$,'42501','Journey is not available to this caller',
 'nonexistent gets identical nondisclosing denial');
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000f3';
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f1')$$,'42501','Non-anonymous Traveller identity required',
 'authenticated anonymous identity denied');
set local request.jwt.claim.sub='';
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f1')$$,'42501','Non-anonymous Traveller identity required',
 'missing authenticated identity denied');
reset role;
set local role anon;
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f1')$$,'42501',null,'anon SQL role denied');
reset role;
set local request.jwt.claim.sub='10000000-0000-4000-8000-0000000000f1';
set local role authenticated;
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f2')$$,'22023','Journey has no automatic-end finalization',
 'ACTIVE cannot fabricate a terminal result');
reset role;

select ok(not exists (
 select 1 from before_owner_reads b where b.rows is distinct from
 query_to_xml(format('select * from %s order by to_jsonb(%I)::text',b.name,split_part(b.name,'.',2)),false,true,'')::text),
 'all owner reads leave every public/private row and access expiry unchanged');

-- Deliberately corrupt only privileged rollback fixtures to test fail-closed reads.
update public.journeys set status='COMPLETED',completed_at=clock_timestamp()
 where id='20000000-0000-4000-8000-0000000000f2';
update public.journeys set status='CANCELLED',ended_at=clock_timestamp()
 where id='20000000-0000-4000-8000-0000000000f3';
update public.journeys set status='UNRESOLVED_INCIDENT',ended_at=clock_timestamp()
 where id='20000000-0000-4000-8000-0000000000f4';
update public.journeys set status='AUTO_ENDED',ended_at=clock_timestamp()
 where id in ('20000000-0000-4000-8000-0000000000f5','20000000-0000-4000-8000-0000000000f6',
 '20000000-0000-4000-8000-0000000000f7','20000000-0000-4000-8000-0000000000f8');
update public.journeys set status='AUTO_ENDED',ended_at='infinity'::timestamptz
 where id='20000000-0000-4000-8000-0000000000f9';
set local role authenticated;
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f2')$$,'22023','Journey has no automatic-end finalization','COMPLETED rejected');
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f3')$$,'22023','Journey has no automatic-end finalization','CANCELLED rejected');
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f4')$$,'22023','Journey has no automatic-end finalization','UNRESOLVED_INCIDENT remains distinct');
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f5')$$,'23514','Inconsistent automatic-end provenance',
 'missing window fails closed');
reset role;
insert into private.probable_end_confirmation_windows(
 presentation_episode_id,journey_id,owner_id,candidate_episode_id,detector_generation,
 policy_version,history_baseline_at,guard_version,registered_at,not_before_at,finalized_at)
select ('30000000-0000-4000-8000-0000000000f'||n)::uuid,j.id,j.owner_id,
 ('40000000-0000-4000-8000-0000000000f'||n)::uuid,1,'alabarin-probable-end-v1',
 j.started_at,0,j.ended_at-interval '31 minutes',j.ended_at-interval '1 minute',
 case when n=6 then j.ended_at-interval '1 second' else j.ended_at end
from public.journeys j cross join generate_series(6,7) n
where j.id=('20000000-0000-4000-8000-0000000000f'||n)::uuid;
-- A second finalized row is never chosen arbitrarily, even if one matches ended_at.
insert into private.probable_end_confirmation_windows
select '30000000-0000-4000-8000-0000000000f0',journey_id,owner_id,
 '40000000-0000-4000-8000-0000000000f0',detector_generation+1,policy_version,
 history_baseline_at,guard_version,registered_at,not_before_at,finalized_at
from private.probable_end_confirmation_windows where presentation_episode_id='30000000-0000-4000-8000-0000000000f7';
insert into private.probable_end_confirmation_windows(
 presentation_episode_id,journey_id,owner_id,candidate_episode_id,detector_generation,
 policy_version,history_baseline_at,guard_version,registered_at,not_before_at,finalized_at)
select '30000000-0000-4000-8000-0000000000f8',j.id,
 '10000000-0000-4000-8000-0000000000f2','40000000-0000-4000-8000-0000000000f8',
 1,'alabarin-probable-end-v1',j.started_at,0,j.ended_at-interval '31 minutes',
 j.ended_at-interval '1 minute',j.ended_at
from public.journeys j where j.id='20000000-0000-4000-8000-0000000000f8';
insert into private.probable_end_confirmation_windows(
 presentation_episode_id,journey_id,owner_id,candidate_episode_id,detector_generation,
 policy_version,history_baseline_at,guard_version,registered_at,not_before_at,finalized_at)
values ('30000000-0000-4000-8000-0000000000fa','20000000-0000-4000-8000-0000000000f9',
 '10000000-0000-4000-8000-0000000000f1','40000000-0000-4000-8000-0000000000fa',
 1,'alabarin-probable-end-v1',now(),0,'infinity','infinity','infinity');
set local role authenticated;
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f6')$$,'23514','Inconsistent automatic-end provenance',
 'mismatched finalized timestamp fails closed');
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f7')$$,'23514','Inconsistent automatic-end provenance',
 'ambiguous finalized window fails closed');
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f8')$$,'23514','Inconsistent automatic-end provenance',
 'inconsistent window ownership fails closed');
select throws_ok($$select * from public.get_owner_probable_end_auto_end_finalization_v1(
 '20000000-0000-4000-8000-0000000000f9')$$,'23514','Inconsistent automatic-end provenance',
 'nonfinite timestamps impossible from the real finalizer fail closed');
select throws_ok($$update public.journeys set status='ACTIVE',ended_at=null
 where id='20000000-0000-4000-8000-0000000000f1'$$,'JT001','Terminal Journey state cannot be rewritten',
 'existing terminal monotonicity remains intact');
reset role;
select throws_ok($$update private.probable_end_confirmation_windows set finalized_at=clock_timestamp()
 where presentation_episode_id='30000000-0000-4000-8000-0000000000f1'$$,'23514',
 'Confirmation window is immutable','existing window immutability unchanged');

select * from finish();
rollback;

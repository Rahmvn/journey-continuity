# Disposable fully migrated local PostgreSQL. Real Section 09 finalization stays
# uncommitted while another same-owner session performs the snapshot-only read.
setup
{
    insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
    values ('10000000-0000-4000-8000-000000000101',
      '00000000-0000-0000-0000-000000000000','authenticated','authenticated',
      'auto-read-race@example.invalid',now(),now(),false);
    select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000101',false);
    insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
      unresolved_admission_id,unresolved_policy_version)
    values ('20000000-0000-4000-8000-000000000101',
      '10000000-0000-4000-8000-000000000101','Fixture',
      date_trunc('milliseconds',now()-interval '2 hours'),
      date_trunc('milliseconds',now()-interval '1 hour'),'ACTIVE',
      '60000000-0000-4000-8000-000000000101','alabarin-unresolved-boundary-v1');
    select public.record_journey_heartbeat('20000000-0000-4000-8000-000000000101',
      1,clock_timestamp(),null,null,'WIFI',0);
    insert into private.probable_end_confirmation_windows(
      presentation_episode_id,journey_id,owner_id,candidate_episode_id,detector_generation,
      policy_version,history_baseline_at,guard_version,registered_at,not_before_at)
    select '30000000-0000-4000-8000-000000000101',s.journey_id,s.owner_id,
      '40000000-0000-4000-8000-000000000101',17,'alabarin-probable-end-v1',
      date_trunc('milliseconds',s.verification_guard_baseline_at),s.verification_guard_version,
      date_trunc('milliseconds',now()-interval '31 minutes'),
      date_trunc('milliseconds',now()-interval '1 minute')
    from public.journey_monitoring_state s where s.journey_id='20000000-0000-4000-8000-000000000101';
}
teardown
{
    delete from private.probable_end_confirmation_windows
      where journey_id='20000000-0000-4000-8000-000000000101';
    delete from public.journey_heartbeats
      where journey_id='20000000-0000-4000-8000-000000000101';
    delete from public.journey_monitoring_events
      where journey_id='20000000-0000-4000-8000-000000000101';
    delete from public.journey_monitoring_state
      where journey_id='20000000-0000-4000-8000-000000000101';
    delete from private.unresolved_boundary_admissions
      where journey_id='20000000-0000-4000-8000-000000000101';
    delete from public.journeys where id='20000000-0000-4000-8000-000000000101';
    delete from auth.users where id='10000000-0000-4000-8000-000000000101';
}
session "finalizer"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-000000000101';
    set request.jwt.claim.session_id='50000000-0000-4000-8000-000000000101';
}
step "f_begin" { begin; }
step "f_finalize" {
    do $$ declare d text; begin
      select decision into d from public.finalize_probable_end_auto_end_v1(
        '20000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000101');
      assert d='AUTO_ENDED';
    end $$;
}
step "f_end" { commit; }
session "reader"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-000000000101';
    set request.jwt.claim.session_id='50000000-0000-4000-8000-000000000102';
}
step "r_begin_repeatable" { begin isolation level repeatable read; }
step "r_before_commit" {
    do $$ begin
      perform * from public.get_owner_probable_end_auto_end_finalization_v1(
        '20000000-0000-4000-8000-000000000101');
      raise exception 'Uncommitted automatic end was visible';
    exception when sqlstate '22023' then null;
    end $$;
}
step "r_same_snapshot" {
    do $$ begin
      perform * from public.get_owner_probable_end_auto_end_finalization_v1(
        '20000000-0000-4000-8000-000000000101');
      raise exception 'Repeatable snapshot changed after writer commit';
    exception when sqlstate '22023' then null;
    end $$;
}
step "r_end" { commit; }
step "r_after_commit" {
    do $$ declare r record; j public.journeys%rowtype; begin
      select * into strict r from public.get_owner_probable_end_auto_end_finalization_v1(
        '20000000-0000-4000-8000-000000000101');
      select * into strict j from public.journeys where id=r.journey_id;
      assert r.decision='AUTO_ENDED' and r.journey_status='AUTO_ENDED';
      assert r.presentation_episode_id='30000000-0000-4000-8000-000000000101'::uuid;
      assert r.candidate_episode_id='40000000-0000-4000-8000-000000000101'::uuid;
      assert r.detector_generation=17 and r.guard_version=0;
      assert r.policy_version='alabarin-probable-end-v1' and r.history_baseline_at is not null;
      assert r.ended_at=j.ended_at and r.finalized_at=r.ended_at;
      assert r.not_before_at=r.registered_at+interval '30 minutes' and r.finalized_at>=r.not_before_at;
    end $$;
}
permutation "f_begin" "f_finalize" "r_before_commit" "f_end" "r_after_commit"
permutation "f_begin" "f_finalize" "r_begin_repeatable" "r_before_commit" "f_end" "r_same_snapshot" "r_end" "r_after_commit"

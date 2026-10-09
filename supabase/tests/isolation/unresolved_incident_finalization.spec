# Fully migrated disposable PostgreSQL only. The exact case identity is frozen
# before the transactions compete, as it would be in a 10C reconciliation claim.
setup
{
    insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
    values ('10000000-0000-4000-8000-0000000000c1',
      '00000000-0000-0000-0000-000000000000','authenticated','authenticated',
      'incident-race@example.invalid',now(),now(),false);
    insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous,
      email_confirmed_at)
    values ('10000000-0000-4000-8000-0000000000c2',
      '00000000-0000-0000-0000-000000000000','authenticated','authenticated',
      'incident-race-contact@example.invalid',now(),now(),false,now());
    select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-0000000000c1',false);
    insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
      unresolved_admission_id,unresolved_policy_version)
    values ('20000000-0000-4000-8000-0000000000c1',
      '10000000-0000-4000-8000-0000000000c1','Fixture',
      date_trunc('milliseconds',now()-interval '3 days'),
      date_trunc('milliseconds',now()-interval '3 days')+interval '1 hour',
      'ACTIVE','30000000-0000-4000-8000-0000000000c1',
      'alabarin-unresolved-boundary-v1');
    insert into public.journey_monitoring_state(journey_id,owner_id,phase,last_cloud_contact_at)
    values ('20000000-0000-4000-8000-0000000000c1',
      '10000000-0000-4000-8000-0000000000c1','EVIDENCE_FRESH',now()-interval '1 hour');
    select public.evaluate_due_journeys();
    insert into public.trusted_contact_relationships(id,traveller_user_id,contact_user_id,
      display_name,contact_email_normalized,status)
    values ('40000000-0000-4000-8000-0000000000c2',
      '10000000-0000-4000-8000-0000000000c1',
      '10000000-0000-4000-8000-0000000000c2','Contact',
      'incident-race-contact@example.invalid','ACCEPTED');
    insert into public.journey_trusted_contact_access(journey_id,relationship_id,
      traveller_user_id,contact_user_id)
    values ('20000000-0000-4000-8000-0000000000c1',
      '40000000-0000-4000-8000-0000000000c2',
      '10000000-0000-4000-8000-0000000000c1',
      '10000000-0000-4000-8000-0000000000c2');
    create table public.isolation_unresolved_identity as
    select * from private.read_unresolved_boundary(
      '20000000-0000-4000-8000-0000000000c1',
      '10000000-0000-4000-8000-0000000000c1',clock_timestamp());
    grant select on public.isolation_unresolved_identity to authenticated;
    -- This old, matured Section 09 window represents a prior CLEAR opportunity.
    -- Current VERIFYING/OPEN facts make it ineligible; both finalizers can never
    -- be eligible simultaneously. The historical clock is compressed only in
    -- this disposable isolation fixture, as in the Section 09 isolation suite.
    with fixture_time as (
      select date_trunc('milliseconds',clock_timestamp()-interval '31 minutes') as registered_at
    ) insert into private.probable_end_confirmation_windows (
      presentation_episode_id,journey_id,owner_id,candidate_episode_id,
      detector_generation,policy_version,history_baseline_at,guard_version,
      registered_at,not_before_at)
    select '30000000-0000-4000-8000-0000000000c1',s.journey_id,s.owner_id,
      '40000000-0000-4000-8000-0000000000c1',1,'alabarin-probable-end-v1',
      date_trunc('milliseconds',s.verification_guard_baseline_at),
      s.verification_guard_version-2,
      t.registered_at,t.registered_at+interval '30 minutes'
    from public.journey_monitoring_state s cross join fixture_time t
    where s.journey_id='20000000-0000-4000-8000-0000000000c1';
}
teardown
{
    drop table public.isolation_unresolved_identity;
    -- Only the disposable isolation database is used; TRUNCATE bypasses the
    -- production immutable-UPDATE/DELETE trigger during fixture cleanup.
    truncate private.unresolved_incident_finalizations;
    delete from private.probable_end_confirmation_windows where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.trusted_contact_access_events where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.verification_case_reports where case_id in
      (select id from public.verification_cases where journey_id=
       '20000000-0000-4000-8000-0000000000c1');
    delete from public.journey_trusted_contact_access where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.trusted_contact_relationships where id=
      '40000000-0000-4000-8000-0000000000c2';
    delete from public.trusted_contact_notification_outbox where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.verification_cases where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.journey_heartbeats where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.journey_monitoring_events where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.journey_monitoring_state where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from private.unresolved_boundary_admissions where journey_id=
      '20000000-0000-4000-8000-0000000000c1';
    delete from public.journeys where id='20000000-0000-4000-8000-0000000000c1';
    delete from auth.users where id='10000000-0000-4000-8000-0000000000c1';
    delete from auth.users where id='10000000-0000-4000-8000-0000000000c2';
}
session "incident"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-0000000000c1';
}
step "i_begin" { begin; }
step "i_wins" {
    do $$ declare d text; begin
      select f.decision into d from public.isolation_unresolved_identity i,
      lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,
      i.boundary_at,i.policy_version,i.verification_case_id,i.opening_event_id,
      i.verifying_started_at,i.guard_version,i.history_baseline_at) f;
      assert d='UNRESOLVED_INCIDENT';
    end $$;
}
step "i_loses" {
    do $$ declare d text; begin
      select f.decision into d from public.isolation_unresolved_identity i,
      lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,
      i.boundary_at,i.policy_version,i.verification_case_id,i.opening_event_id,
      i.verifying_started_at,i.guard_version,i.history_baseline_at) f;
      assert d='BLOCKED';
    end $$;
}
step "i_commit" { commit; }
session "opponent"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-0000000000c1';
}
step "o_begin" { begin; }
step "o_complete" {
    update public.journeys set status='COMPLETED',completed_at=clock_timestamp()
    where id='20000000-0000-4000-8000-0000000000c1';
}
step "o_heartbeat" {
    do $$ begin perform public.record_journey_heartbeat(
      '20000000-0000-4000-8000-0000000000c1',1,clock_timestamp(),
      null,null,'WIFI',0); end $$;
}
step "o_cancel" {
    do $$ declare s text; begin
      select journey_status into s from public.stop_journey_monitoring_v1(
        '20000000-0000-4000-8000-0000000000c1');
      assert s='CANCELLED';
    end $$;
}
step "o_duplicate" {
    do $$ declare d text; begin
      select f.decision into d from public.isolation_unresolved_identity i,
      lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,
      i.boundary_at,i.policy_version,i.verification_case_id,i.opening_event_id,
      i.verifying_started_at,i.guard_version,i.history_baseline_at) f;
      assert d='UNRESOLVED_INCIDENT';
    end $$;
}
step "o_stale_active" {
    -- Mirrors the owner's journeys(id) PostgREST upsert conflict update, using
    -- the older ACTIVE projection and its original admitted ETA/provenance.
    insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,
      status,completed_at,ended_at,unresolved_admission_id,unresolved_policy_version)
    select id,owner_id,destination,started_at,expected_arrival_at,
      'ACTIVE',null,null,unresolved_admission_id,unresolved_policy_version
    from public.journeys where id='20000000-0000-4000-8000-0000000000c1'
    on conflict (id) do update set status=excluded.status,
      completed_at=excluded.completed_at,ended_at=excluded.ended_at,
      destination=excluded.destination,started_at=excluded.started_at,
      expected_arrival_at=excluded.expected_arrival_at,
      unresolved_admission_id=excluded.unresolved_admission_id,
      unresolved_policy_version=excluded.unresolved_policy_version;
}
step "o_stale_active_rejected" {
    do $$ begin
      insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,
        status,completed_at,ended_at,unresolved_admission_id,unresolved_policy_version)
      select id,owner_id,destination,started_at,expected_arrival_at,
        'ACTIVE',null,null,unresolved_admission_id,unresolved_policy_version
      from public.journeys where id='20000000-0000-4000-8000-0000000000c1'
      on conflict (id) do update set status=excluded.status,
        completed_at=excluded.completed_at,ended_at=excluded.ended_at,
        destination=excluded.destination,started_at=excluded.started_at,
        expected_arrival_at=excluded.expected_arrival_at,
        unresolved_admission_id=excluded.unresolved_admission_id,
        unresolved_policy_version=excluded.unresolved_policy_version;
      assert false, 'stale ACTIVE owner upsert unexpectedly succeeded';
    exception when SQLSTATE 'JT001' then null;
    end $$;
}
step "o_commit" { commit; }
session "observer"
step "completed_won" {
    do $$ begin assert (select status='COMPLETED' from public.journeys
      where id='20000000-0000-4000-8000-0000000000c1');
      assert not exists(select 1 from private.unresolved_incident_finalizations
        where journey_id='20000000-0000-4000-8000-0000000000c1'); end $$;
}
step "incident_won" {
    do $$ begin assert (select status='UNRESOLVED_INCIDENT' from public.journeys
      where id='20000000-0000-4000-8000-0000000000c1');
      assert (select status='OPEN' from public.verification_cases
        where journey_id='20000000-0000-4000-8000-0000000000c1'); end $$;
}
step "heartbeat_won" {
    do $$ begin assert (select status='ACTIVE' from public.journeys
      where id='20000000-0000-4000-8000-0000000000c1');
      assert (select status='RESOLVED' from public.verification_cases
        where journey_id='20000000-0000-4000-8000-0000000000c1'); end $$;
}
step "cancel_won" {
    do $$ begin assert (select status='CANCELLED' from public.journeys
      where id='20000000-0000-4000-8000-0000000000c1');
      assert not exists(select 1 from private.unresolved_incident_finalizations
        where journey_id='20000000-0000-4000-8000-0000000000c1'); end $$;
}
step "one_finalization" {
    do $$ begin assert (select count(*)=1 from private.unresolved_incident_finalizations
      where journey_id='20000000-0000-4000-8000-0000000000c1'); end $$;
}
step "incident_provenance_intact" {
    do $$ begin
      assert (select j.status='UNRESOLVED_INCIDENT' and
        j.expected_arrival_at=f.original_eta and j.ended_at=f.finalized_at and
        j.owner_id=f.owner_id and j.unresolved_admission_id=f.admission_id and
        f.boundary_at=a.boundary_at and f.original_eta=a.original_eta and
        f.verification_case_id=i.verification_case_id and
        f.opening_event_id=i.opening_event_id and
        f.access_expires_at=f.finalized_at+interval '168 hours'
        from public.journeys j
        join private.unresolved_incident_finalizations f on f.journey_id=j.id
        join private.unresolved_boundary_admissions a on a.journey_id=j.id
        cross join public.isolation_unresolved_identity i
        where j.id='20000000-0000-4000-8000-0000000000c1');
      assert (select count(*)=1 from private.unresolved_incident_finalizations
        where journey_id='20000000-0000-4000-8000-0000000000c1');
    end $$;
}
step "auto_won" {
    do $$ begin
      assert (select status='AUTO_ENDED' from public.journeys
        where id='20000000-0000-4000-8000-0000000000c1');
      assert not exists(select 1 from private.unresolved_incident_finalizations
        where journey_id='20000000-0000-4000-8000-0000000000c1');
      assert (select finalized_at is not null from private.probable_end_confirmation_windows
        where presentation_episode_id='30000000-0000-4000-8000-0000000000c3');
    end $$;
}
step "verification_blocks_auto" {
    do $$ begin
      assert (select status='ACTIVE' from public.journeys
        where id='20000000-0000-4000-8000-0000000000c1');
      assert (select phase='VERIFYING' from public.journey_monitoring_state
        where journey_id='20000000-0000-4000-8000-0000000000c1');
      assert (select count(*)=1 from public.verification_cases
        where journey_id='20000000-0000-4000-8000-0000000000c1' and status='OPEN');
      assert (select finalized_at is null from private.probable_end_confirmation_windows
        where presentation_episode_id='30000000-0000-4000-8000-0000000000c3');
    end $$;
}
session "verification"
step "v_begin" { begin; }
step "v_replace_case" {
    do $$ begin perform public.resolve_open_verification_case(
      '20000000-0000-4000-8000-0000000000c1',
      'DEVICE_CONTACT_RESTORED',clock_timestamp());
    update public.journey_monitoring_state set phase='EVIDENCE_FRESH',
      verifying_started_at=null,last_cloud_contact_at=clock_timestamp()-interval '1 hour'
    where journey_id='20000000-0000-4000-8000-0000000000c1';
    perform public.evaluate_due_journeys(); end $$;
}
step "v_commit" { commit; }
step "v_seed_fresh_auto_window" {
    -- A post-resolution confirmation window, advanced past its 30-minute
    -- not-before only by disposable fixture time. Production RPC is unchanged.
    with fixture_time as (
      select date_trunc('milliseconds',clock_timestamp()-interval '31 minutes') as registered_at
    ) insert into private.probable_end_confirmation_windows (
      presentation_episode_id,journey_id,owner_id,candidate_episode_id,
      detector_generation,policy_version,history_baseline_at,guard_version,
      registered_at,not_before_at)
    select '30000000-0000-4000-8000-0000000000c3',s.journey_id,s.owner_id,
      '40000000-0000-4000-8000-0000000000c3',1,'alabarin-probable-end-v1',
      date_trunc('milliseconds',s.verification_guard_baseline_at),
      s.verification_guard_version,
      t.registered_at,t.registered_at+interval '30 minutes'
    from public.journey_monitoring_state s cross join fixture_time t
    where s.journey_id='20000000-0000-4000-8000-0000000000c1';
}
step "v_open_new_verification" {
    do $$ declare v_at timestamptz := date_trunc('milliseconds',clock_timestamp());
      v_event_id bigint; begin
      update public.journey_monitoring_state
      set phase='VERIFYING',verifying_started_at=v_at
      where journey_id='20000000-0000-4000-8000-0000000000c1';
      insert into public.journey_monitoring_events(journey_id,event_type,occurred_at,
        previous_phase,new_phase,last_cloud_contact_at,reason_code,threshold_seconds)
      values('20000000-0000-4000-8000-0000000000c1','VERIFYING_STARTED',v_at,
        'EVIDENCE_FRESH','VERIFYING',v_at-interval '1 hour',
        'AUTHENTICATED_DEVICE_EVIDENCE_TIMEOUT',300)
      returning id into v_event_id;
      perform public.open_verification_case('20000000-0000-4000-8000-0000000000c1',
        '10000000-0000-4000-8000-0000000000c1',v_event_id,v_at,
        v_at-interval '1 hour');
    end $$;
}
step "replacement_won" {
    do $$ begin assert (select status='ACTIVE' from public.journeys
      where id='20000000-0000-4000-8000-0000000000c1');
      assert (select count(*)=1 from public.verification_cases
        where journey_id='20000000-0000-4000-8000-0000000000c1' and status='OPEN');
      assert (select count(*)=1 from public.verification_cases
        where journey_id='20000000-0000-4000-8000-0000000000c1' and status='RESOLVED');
      assert not exists(select 1 from private.unresolved_incident_finalizations
        where journey_id='20000000-0000-4000-8000-0000000000c1'); end $$;
}
session "reporter"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-0000000000c2';
}
step "r_begin" { begin; }
step "r_before" {
    do $$ begin perform public.submit_verification_case_report(
      (select verification_case_id from public.isolation_unresolved_identity),
      'OTHER',clock_timestamp(),'Evidence before terminalization'); end $$;
}
step "r_after" {
    do $$ begin
      perform public.submit_verification_case_report(
        (select verification_case_id from public.isolation_unresolved_identity),
        'OTHER',clock_timestamp(),'Too late');
      assert false;
    exception when insufficient_privilege then
      assert (public.get_trusted_journey_snapshot(
        '20000000-0000-4000-8000-0000000000c1')->>'journey_status')='UNRESOLVED_INCIDENT';
    end $$;
}
step "r_commit" { commit; }
session "report_observer"
step "one_preserved_report" {
    do $$ begin assert (select count(*)=1 from public.verification_case_reports
      where case_id=(select verification_case_id from public.isolation_unresolved_identity));
      assert (select status='OPEN' from public.verification_cases
        where journey_id='20000000-0000-4000-8000-0000000000c1'); end $$;
}
step "no_late_report" {
    do $$ begin assert not exists(select 1 from public.verification_case_reports
      where case_id=(select verification_case_id from public.isolation_unresolved_identity)); end $$;
}
session "auto"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-0000000000c1';
}
step "a_begin" { begin; }
step "a_old_blocked" {
    do $$ declare d text; begin
      select decision into d from public.finalize_probable_end_auto_end_v1(
        '20000000-0000-4000-8000-0000000000c1',
        '30000000-0000-4000-8000-0000000000c1');
      assert d='BLOCKED';
    end $$;
}
step "a_fresh_ended" {
    do $$ declare d text; begin
      select decision into d from public.finalize_probable_end_auto_end_v1(
        '20000000-0000-4000-8000-0000000000c1',
        '30000000-0000-4000-8000-0000000000c3');
      assert d='AUTO_ENDED';
    end $$;
}
step "a_fresh_blocked" {
    do $$ declare d text; begin
      select decision into d from public.finalize_probable_end_auto_end_v1(
        '20000000-0000-4000-8000-0000000000c1',
        '30000000-0000-4000-8000-0000000000c3');
      assert d='BLOCKED';
    end $$;
}
step "a_commit" { commit; }
permutation "o_begin" "o_complete" "i_begin" "i_loses" "o_commit" "i_commit" "completed_won"
permutation "i_begin" "i_wins" "o_begin" "o_complete" "i_commit" "o_commit" "incident_won"
permutation "o_begin" "o_heartbeat" "i_begin" "i_loses" "o_commit" "i_commit" "heartbeat_won"
permutation "o_begin" "o_cancel" "i_begin" "i_loses" "o_commit" "i_commit" "cancel_won"
permutation "i_begin" "i_wins" "o_begin" "o_duplicate" "i_commit" "o_commit" "one_finalization"
permutation "v_begin" "v_replace_case" "i_begin" "i_loses" "v_commit" "i_commit" "replacement_won"
permutation "r_begin" "r_before" "i_begin" "i_wins" "r_commit" "i_commit" "one_preserved_report"
permutation "i_begin" "i_wins" "r_begin" "r_after" "i_commit" "r_commit" "no_late_report"
# An older Section 09 window is already ineligible under current VERIFYING/OPEN.
# Incident holds the Journey row; the delayed AUTO attempt waits and cannot rewrite it.
permutation "i_begin" "i_wins" "a_begin" "a_old_blocked" "i_commit" "a_commit" "incident_provenance_intact"
# Resolve the original episode, then use a fresh CLEAR window fixture. Only AUTO
# is eligible; the old incident identity waits behind its terminal write.
permutation "o_heartbeat" "v_seed_fresh_auto_window" "a_begin" "a_fresh_ended" "i_begin" "i_loses" "a_commit" "i_commit" "auto_won"
# A newly opening verification episode holds the state row while delayed AUTO
# waits. It observes current VERIFYING/OPEN and cannot use the older CLEAR read.
permutation "o_heartbeat" "v_seed_fresh_auto_window" "v_begin" "v_open_new_verification" "a_begin" "a_fresh_blocked" "v_commit" "a_commit" "verification_blocks_auto"
# Real owner upsert wins while still ACTIVE; incident waits, then commits.
permutation "o_begin" "o_stale_active" "i_begin" "i_wins" "o_commit" "i_commit" "incident_provenance_intact"
# Incident wins first; the older ACTIVE owner upsert waits and is rejected by
# the existing JT001 terminal guard without changing ETA or incident provenance.
permutation "i_begin" "i_wins" "o_begin" "o_stale_active_rejected" "i_commit" "o_commit" "incident_provenance_intact"

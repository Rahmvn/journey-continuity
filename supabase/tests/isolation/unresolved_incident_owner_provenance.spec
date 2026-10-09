# Disposable fully migrated PostgreSQL. The reader must not see an incident
# until the writer's Journey transition and immutable provenance commit together.
setup
{
    insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
    values ('10000000-0000-4000-8000-0000000000e1',
      '00000000-0000-0000-0000-000000000000','authenticated','authenticated',
      'incident-read-race@example.invalid',now(),now(),false);
    select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-0000000000e1',false);
    insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
      unresolved_admission_id,unresolved_policy_version)
    values ('20000000-0000-4000-8000-0000000000e1',
      '10000000-0000-4000-8000-0000000000e1','Fixture',
      date_trunc('milliseconds',now()-interval '3 days'),
      date_trunc('milliseconds',now()-interval '3 days')+interval '1 hour',
      'ACTIVE','30000000-0000-4000-8000-0000000000e1',
      'alabarin-unresolved-boundary-v1');
    insert into public.journey_monitoring_state(journey_id,owner_id,phase,last_cloud_contact_at)
    values ('20000000-0000-4000-8000-0000000000e1',
      '10000000-0000-4000-8000-0000000000e1','EVIDENCE_FRESH',now()-interval '1 hour');
    select public.evaluate_due_journeys();
    create table public.isolation_incident_read_identity as
      select * from private.read_unresolved_boundary(
        '20000000-0000-4000-8000-0000000000e1',
        '10000000-0000-4000-8000-0000000000e1',clock_timestamp());
    grant select on public.isolation_incident_read_identity to authenticated;
}
teardown
{
    drop table public.isolation_incident_read_identity;
    truncate private.unresolved_incident_finalizations;
    delete from public.verification_cases where journey_id=
      '20000000-0000-4000-8000-0000000000e1';
    delete from public.journey_monitoring_events where journey_id=
      '20000000-0000-4000-8000-0000000000e1';
    delete from public.journey_monitoring_state where journey_id=
      '20000000-0000-4000-8000-0000000000e1';
    delete from private.unresolved_boundary_admissions where journey_id=
      '20000000-0000-4000-8000-0000000000e1';
    delete from public.journeys where id='20000000-0000-4000-8000-0000000000e1';
    delete from auth.users where id='10000000-0000-4000-8000-0000000000e1';
}
session "finalizer"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-0000000000e1';
}
step "f_begin" { begin; }
step "f_commit_incident" {
    do $$ declare d text; begin
      select f.decision into d from public.isolation_incident_read_identity i,
      lateral public.finalize_unresolved_incident_v1(i.journey_id,i.admission_id,
      i.boundary_at,i.policy_version,i.verification_case_id,i.opening_event_id,
      i.verifying_started_at,i.guard_version,i.history_baseline_at) f;
      assert d='UNRESOLVED_INCIDENT';
    end $$;
}
step "f_end" { commit; }
session "reader"
setup {
    set role authenticated;
    set request.jwt.claim.sub='10000000-0000-4000-8000-0000000000e1';
}
step "r_before_commit" {
    do $$ begin
      perform * from public.get_owner_unresolved_incident_finalization_v1(
        '20000000-0000-4000-8000-0000000000e1');
      raise exception 'Uncommitted incident was visible';
    exception when sqlstate '22023' then null;
    end $$;
}
step "r_after_commit" {
    do $$ declare d text; a uuid; c uuid; begin
      select r.decision,r.admission_id,r.verification_case_id into d,a,c
      from public.get_owner_unresolved_incident_finalization_v1(
        '20000000-0000-4000-8000-0000000000e1') r;
      assert d='UNRESOLVED_INCIDENT' and a is not null and c is not null;
    end $$;
}
permutation "f_begin" "f_commit_incident" "r_before_commit" "f_end" "r_after_commit"

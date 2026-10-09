# Read-only 10A eligibility must never combine uncommitted VERIFYING with a
# missing case or an OPEN case with the prior phase. This is a snapshot, not a
# finalization lease. Local disposable PostgreSQL only.
setup
{
    insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
    values ('10000000-0000-4000-8000-000000000031',
        '00000000-0000-0000-0000-000000000000','authenticated','authenticated',
        'isolation-boundary@example.invalid',now(),now(),false);
    select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000031',false);
    insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
        unresolved_admission_id,unresolved_policy_version)
    values ('20000000-0000-4000-8000-000000000031','10000000-0000-4000-8000-000000000031',
        'Fixture',date_trunc('milliseconds',now()-interval '50 hours'),
        date_trunc('milliseconds',now()-interval '49 hours'),'ACTIVE',
        '30000000-0000-4000-8000-000000000031','alabarin-unresolved-boundary-v1');
    insert into public.journey_monitoring_state(journey_id,owner_id,phase,last_cloud_contact_at)
    values ('20000000-0000-4000-8000-000000000031',
        '10000000-0000-4000-8000-000000000031','EVIDENCE_FRESH',now()-interval '1 hour');
}
teardown
{
    delete from public.verification_cases where journey_id='20000000-0000-4000-8000-000000000031';
    delete from public.journey_monitoring_events where journey_id='20000000-0000-4000-8000-000000000031';
    delete from public.journey_monitoring_state where journey_id='20000000-0000-4000-8000-000000000031';
    delete from private.unresolved_boundary_admissions where journey_id='20000000-0000-4000-8000-000000000031';
    delete from public.journeys where id='20000000-0000-4000-8000-000000000031';
    delete from auth.users where id='10000000-0000-4000-8000-000000000031';
}
session "verification"
step "begin_verification" { begin; }
step "enter_verifying" {
    update public.journey_monitoring_state set phase='VERIFYING',
        verifying_started_at=date_trunc('milliseconds',clock_timestamp())
    where journey_id='20000000-0000-4000-8000-000000000031';
}
step "open_case" {
    insert into public.journey_monitoring_events(journey_id,event_type,occurred_at,previous_phase,
        new_phase,reason_code) select journey_id,'VERIFYING_STARTED',verifying_started_at,
        'EVIDENCE_FRESH','VERIFYING','ISOLATION_FIXTURE'
    from public.journey_monitoring_state where journey_id='20000000-0000-4000-8000-000000000031';
    select public.open_verification_case('20000000-0000-4000-8000-000000000031',
        '10000000-0000-4000-8000-000000000031',
        (select id from public.journey_monitoring_events
         where journey_id='20000000-0000-4000-8000-000000000031' and event_type='VERIFYING_STARTED'),
        (select verifying_started_at from public.journey_monitoring_state
         where journey_id='20000000-0000-4000-8000-000000000031'),
        (select last_cloud_contact_at from public.journey_monitoring_state
         where journey_id='20000000-0000-4000-8000-000000000031')) is not null as opened;
}
step "commit_verification" { commit; }
session "reader"
setup {
    set role authenticated;
    set request.jwt.claim.sub = '10000000-0000-4000-8000-000000000031';
}
step "read_before" {
    do $$ declare x record; begin
        select * into strict x from public.get_unresolved_boundary_eligibility_v1(
            '20000000-0000-4000-8000-000000000031');
        assert x.decision = 'NOT_ELIGIBLE' and x.reason = 'NOT_VERIFYING';
    end $$;
}
step "read_during" {
    do $$ declare x record; begin
        select * into strict x from public.get_unresolved_boundary_eligibility_v1(
            '20000000-0000-4000-8000-000000000031');
        assert x.decision = 'NOT_ELIGIBLE' and x.reason = 'NOT_VERIFYING';
    end $$;
}
step "read_after" {
    do $$ declare x record; begin
        select * into strict x from public.get_unresolved_boundary_eligibility_v1(
            '20000000-0000-4000-8000-000000000031');
        assert x.decision = 'ELIGIBLE' and x.reason = 'CURRENT_UNRESOLVED' and
            x.verification_case_id is not null;
    end $$;
}
permutation "read_before" "begin_verification" "enter_verifying" "open_case" "commit_verification" "read_after"
permutation "begin_verification" "enter_verifying" "read_during" "open_case" "commit_verification" "read_after"
permutation "begin_verification" "enter_verifying" "open_case" "read_during" "commit_verification" "read_after"

# Fully migrated disposable PostgreSQL only. The finalizer locks Journey, then monitoring
# state; case opening locks monitoring state before inserting OPEN. All assertions are DO blocks.
setup
{
    insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at, is_anonymous)
    values ('10000000-0000-4000-8000-0000000000b1',
        '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
        'auto-race@example.invalid', now(), now(), false);
    insert into public.journeys (id, owner_id, destination, started_at, expected_arrival_at, status)
    values ('20000000-0000-4000-8000-0000000000b1',
        '10000000-0000-4000-8000-0000000000b1', 'Fixture',
        now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE');
    with t as (select clock_timestamp() as at)
    insert into public.journey_monitoring_state (journey_id, owner_id, phase,
        last_cloud_contact_at, last_authenticated_device_evidence_at,
        last_authenticated_device_evidence_received_at, last_authenticated_device_evidence_transport)
    select '20000000-0000-4000-8000-0000000000b1',
        '10000000-0000-4000-8000-0000000000b1', 'EVIDENCE_FRESH',
        t.at, t.at, t.at, 'CLOUD_HEARTBEAT' from t;
    insert into public.journey_monitoring_events (journey_id, event_type, new_phase, reason_code)
    values ('20000000-0000-4000-8000-0000000000b1', 'VERIFYING_STARTED', 'VERIFYING', 'TEST');
    insert into private.probable_end_confirmation_windows (
        presentation_episode_id, journey_id, owner_id, candidate_episode_id,
        detector_generation, policy_version, history_baseline_at, guard_version,
        registered_at, not_before_at)
    select '30000000-0000-4000-8000-0000000000b1',
        '20000000-0000-4000-8000-0000000000b1',
        '10000000-0000-4000-8000-0000000000b1',
        '40000000-0000-4000-8000-0000000000b1', 1,
        'alabarin-probable-end-v1', date_trunc('milliseconds', verification_guard_baseline_at),
        verification_guard_version, date_trunc('milliseconds', now() - interval '31 minutes'),
        date_trunc('milliseconds', now() - interval '1 minute')
    from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-0000000000b1';
}
teardown
{
    delete from private.probable_end_confirmation_windows
    where presentation_episode_id = '30000000-0000-4000-8000-0000000000b1';
    delete from public.trusted_contact_notification_outbox
    where journey_id = '20000000-0000-4000-8000-0000000000b1';
    delete from public.verification_case_location_points where case_id in
        (select id from public.verification_cases where journey_id = '20000000-0000-4000-8000-0000000000b1');
    delete from public.verification_cases where journey_id = '20000000-0000-4000-8000-0000000000b1';
    delete from public.journey_heartbeats where journey_id = '20000000-0000-4000-8000-0000000000b1';
    delete from public.journey_monitoring_events where journey_id = '20000000-0000-4000-8000-0000000000b1';
    delete from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-0000000000b1';
    delete from public.journeys where id = '20000000-0000-4000-8000-0000000000b1';
    delete from auth.users where id = '10000000-0000-4000-8000-0000000000b1';
}
session "auto"
setup {
    set role authenticated;
    set request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000b1';
}
step "auto_begin" { begin; }
step "auto_ended" {
    do $$ declare d text; begin
        select decision into strict d from public.finalize_probable_end_auto_end_v1(
            '20000000-0000-4000-8000-0000000000b1', '30000000-0000-4000-8000-0000000000b1');
        assert d = 'AUTO_ENDED';
    end $$;
}
step "auto_blocked" {
    do $$ declare d text; begin
        select decision into strict d from public.finalize_probable_end_auto_end_v1(
            '20000000-0000-4000-8000-0000000000b1', '30000000-0000-4000-8000-0000000000b1');
        assert d = 'BLOCKED';
    end $$;
}
step "auto_commit" { commit; }
session "verification"
step "verification_begin" { begin; }
step "verification_phase" {
    update public.journey_monitoring_state set phase = 'VERIFYING', verifying_started_at = clock_timestamp()
    where journey_id = '20000000-0000-4000-8000-0000000000b1';
}
step "verification_open" {
    select public.open_verification_case('20000000-0000-4000-8000-0000000000b1',
        '10000000-0000-4000-8000-0000000000b1',
        (select id from public.journey_monitoring_events
         where journey_id = '20000000-0000-4000-8000-0000000000b1'
           and event_type = 'VERIFYING_STARTED' order by id desc limit 1),
        clock_timestamp(), clock_timestamp()) is not null as opened;
}
step "verification_open_rejected" {
    do $$ begin
        perform public.open_verification_case('20000000-0000-4000-8000-0000000000b1',
            '10000000-0000-4000-8000-0000000000b1',
            (select id from public.journey_monitoring_events
             where journey_id = '20000000-0000-4000-8000-0000000000b1'
               and event_type = 'VERIFYING_STARTED' order by id desc limit 1),
            clock_timestamp(), clock_timestamp());
        raise exception 'case opening after AUTO_ENDED was accepted';
    exception when check_violation then null;
    end $$;
}
step "verification_commit" { commit; }
step "restore_heartbeat" {
    do $$ begin
        perform set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-0000000000b1', false);
        perform public.record_journey_heartbeat('20000000-0000-4000-8000-0000000000b1',
            2, clock_timestamp(), null, null, 'WIFI', 0);
    end $$;
}
session "terminal"
step "terminal_begin" { begin; }
step "terminal_complete" {
    update public.journeys set status = 'COMPLETED', completed_at = clock_timestamp()
    where id = '20000000-0000-4000-8000-0000000000b1';
}
step "terminal_cancel" {
    update public.journeys set status = 'CANCELLED', ended_at = clock_timestamp()
    where id = '20000000-0000-4000-8000-0000000000b1';
}
step "terminal_commit" { commit; }
session "retry"
setup {
    set role authenticated;
    set request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000b1';
}
step "retry_begin" { begin; }
step "retry_ended" {
    do $$ declare d text; begin
        select decision into strict d from public.finalize_probable_end_auto_end_v1(
            '20000000-0000-4000-8000-0000000000b1', '30000000-0000-4000-8000-0000000000b1');
        assert d = 'AUTO_ENDED';
    end $$;
}
step "retry_commit" { commit; }
permutation "verification_begin" "verification_phase" "auto_begin" "auto_blocked" "verification_commit" "auto_commit"
permutation "verification_begin" "verification_open" "auto_begin" "auto_blocked" "verification_commit" "auto_commit"
permutation "terminal_begin" "terminal_complete" "auto_begin" "auto_blocked" "terminal_commit" "auto_commit"
permutation "terminal_begin" "terminal_cancel" "auto_begin" "auto_blocked" "terminal_commit" "auto_commit"
permutation "auto_begin" "auto_ended" "retry_begin" "retry_ended" "auto_commit" "retry_commit"
permutation "auto_begin" "auto_ended" "verification_begin" "verification_open_rejected" "auto_commit" "verification_commit"
permutation "verification_begin" "verification_phase" "verification_commit" "restore_heartbeat" "auto_begin" "auto_blocked" "auto_commit"
# Watchdog-style phase + case opening while finalization is already waiting on
# the state row. Journey NO KEY UPDATE must not block the case's FK KEY SHARE.
permutation "verification_begin" "verification_phase" "auto_begin" "auto_blocked" "verification_open" "verification_commit" "auto_commit"
# Heartbeat's real Journey-first transaction holds the restored freshness while
# the finalizer waits. The higher history version still blocks the old window.
permutation "verification_begin" "verification_phase" "verification_commit" "verification_begin" "restore_heartbeat" "auto_begin" "auto_blocked" "verification_commit" "auto_commit"

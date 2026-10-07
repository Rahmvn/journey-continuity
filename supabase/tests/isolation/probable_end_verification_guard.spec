# PostgreSQL isolationtester fixture; disposable migrated local database only.
# The guard is a snapshot, not a lock/lease against a subsequent verification.
# Snapshot reads must not block. Competing writers may wait, without deadlock.
# Before opening
# commit CLEAR/version0 is valid; afterwards BLOCKED/version1. Before resolution
# commit it stays BLOCKED/version1; afterwards CLEAR/version1 (never version0).
setup
{
    insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at, is_anonymous)
    values ('10000000-0000-4000-8000-000000000093', '00000000-0000-0000-0000-000000000000',
        'authenticated', 'authenticated', 'guard-race@example.invalid', now(), now(), false);
    insert into public.journeys (id, owner_id, destination, started_at, expected_arrival_at, status)
    values ('20000000-0000-4000-8000-000000000093', '10000000-0000-4000-8000-000000000093',
        'Fixture', now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE');
    insert into public.journey_monitoring_state (journey_id, owner_id, phase, last_cloud_contact_at)
    values ('20000000-0000-4000-8000-000000000093', '10000000-0000-4000-8000-000000000093',
        'EVIDENCE_FRESH', now());
    insert into public.journey_monitoring_events (journey_id, event_type, new_phase, reason_code)
    values ('20000000-0000-4000-8000-000000000093', 'VERIFYING_STARTED', 'VERIFYING', 'ISOLATION_FIXTURE');
}
teardown
{
    delete from public.trusted_contact_notification_outbox where journey_id = '20000000-0000-4000-8000-000000000093';
    delete from public.verification_case_location_points where case_id in
        (select id from public.verification_cases where journey_id = '20000000-0000-4000-8000-000000000093');
    delete from public.verification_cases where journey_id = '20000000-0000-4000-8000-000000000093';
    delete from public.journey_monitoring_events where journey_id = '20000000-0000-4000-8000-000000000093';
    delete from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000093';
    delete from public.journeys where id = '20000000-0000-4000-8000-000000000093';
    delete from auth.users where id = '10000000-0000-4000-8000-000000000093';
}
session "verification"
step "open_begin" { begin; }
step "open_case" {
    select public.open_verification_case('20000000-0000-4000-8000-000000000093',
        '10000000-0000-4000-8000-000000000093',
        (select id from public.journey_monitoring_events where journey_id = '20000000-0000-4000-8000-000000000093'),
        clock_timestamp(), clock_timestamp()) is not null as opened;
}
step "open_commit" { commit; }
step "resolve_begin" { begin; }
step "resolve_case" {
    select public.resolve_open_verification_case('20000000-0000-4000-8000-000000000093',
        'DEVICE_CONTACT_RESTORED', clock_timestamp());
}
step "resolve_commit" { commit; }
step "open_competing" {
    insert into public.verification_cases (journey_id, owner_id, opened_from_monitoring_event_id,
        journey_destination, journey_started_at, journey_expected_arrival_at)
    select j.id, j.owner_id, e.id, j.destination, j.started_at, j.expected_arrival_at
    from public.journeys j join public.journey_monitoring_events e on e.journey_id = j.id
    where j.id = '20000000-0000-4000-8000-000000000093'
    on conflict (journey_id) where status = 'OPEN' do nothing;
}
step "complete_begin" { begin; }
step "complete_journey" {
    update public.journeys set status = 'COMPLETED', completed_at = clock_timestamp()
    where id = '20000000-0000-4000-8000-000000000093';
}
step "complete_commit" { commit; }
session "phase"
step "phase_begin" { begin; }
step "phase_enter" {
    update public.journey_monitoring_state set phase = 'VERIFYING', verifying_started_at = clock_timestamp()
    where journey_id = '20000000-0000-4000-8000-000000000093';
}
step "phase_open_case" {
    insert into public.verification_cases (journey_id, owner_id, opened_from_monitoring_event_id,
        journey_destination, journey_started_at, journey_expected_arrival_at)
    select j.id, j.owner_id, e.id, j.destination, j.started_at, j.expected_arrival_at
    from public.journeys j join public.journey_monitoring_events e on e.journey_id = j.id
    where j.id = '20000000-0000-4000-8000-000000000093'
    on conflict (journey_id) where status = 'OPEN' do nothing;
}
step "phase_commit" { commit; }
session "reader"
setup {
    set role authenticated;
    set request.jwt.claim.sub = '10000000-0000-4000-8000-000000000093';
}
step "read_before" {
    do $$ declare g record; begin
        select * into strict g from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000093');
        assert g.decision = 'CLEAR' and g.guard_version = 0;
    end $$;
}
step "read_open" {
    do $$ declare g record; begin
        select * into strict g from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000093');
        assert g.decision = 'BLOCKED' and g.guard_version = 1;
    end $$;
}
step "read_resolving" {
    do $$ declare g record; begin
        select * into strict g from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000093');
        assert g.decision = 'BLOCKED' and g.guard_version = 1;
    end $$;
}
step "read_resolved" {
    do $$ declare g record; begin
        select * into strict g from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000093');
        assert g.decision = 'CLEAR' and g.guard_version = 1;
    end $$;
}
step "read_both" {
    do $$ declare g record; begin
        select * into strict g from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000093');
        assert g.decision = 'BLOCKED' and g.guard_version = 2;
    end $$;
}
step "read_terminal" {
    do $$ declare g record; begin
        select * into strict g from public.get_probable_end_verification_guard_v1('20000000-0000-4000-8000-000000000093');
        assert g.decision = 'BLOCKED' and not g.journey_active and g.reason = 'JOURNEY_NOT_ACTIVE';
    end $$;
}
permutation "open_begin" "open_case" "read_before" "open_commit" "read_open" "resolve_begin" "resolve_case" "read_resolving" "resolve_commit" "read_resolved"
permutation "read_before" "open_begin" "open_case" "open_commit" "resolve_begin" "resolve_case" "read_resolving" "resolve_commit" "read_resolved"
# The state-first writer can insert its case while its competitor is waiting.
# An AFTER-only lock would reserve the competitor's OPEN slot first and deadlock.
permutation "phase_begin" "phase_enter" "open_begin" "open_competing" "phase_open_case" "read_before" "phase_commit" "open_commit" "read_both"
permutation "open_begin" "open_competing" "phase_begin" "phase_enter" "open_commit" "phase_open_case" "phase_commit" "read_both"
permutation "complete_begin" "complete_journey" "read_before" "complete_commit" "read_terminal"

-- Disposable local migrated database only. No hosted writes. Fixtures roll back.
begin;
select no_plan();
select has_table('private', 'unresolved_boundary_admissions', 'separate admission authority table');
select is((select count(*) from private.unresolved_boundary_admissions), 0::bigint, 'no historical backfill');
select ok(has_function_privilege('authenticated', 'public.get_unresolved_boundary_eligibility_v1(uuid)', 'EXECUTE'), 'owner read granted');
select ok(not has_function_privilege('anon', 'public.get_unresolved_boundary_eligibility_v1(uuid)', 'EXECUTE'), 'anon denied');
select ok(not has_function_privilege('authenticated', 'private.read_unresolved_boundary(uuid,uuid,timestamptz)', 'EXECUTE'), 'caller cannot choose evaluation time');
select ok(not has_table_privilege('authenticated', 'private.unresolved_boundary_admissions', 'INSERT,UPDATE,DELETE'), 'cannot forge provenance');
select ok((select expected_arrival_at='2030-01-01 UTC'::timestamptz and
    unresolved_admission_id is null from public.journeys
    where id='20000000-0000-4000-8000-000000000040'),
    'prepolicy overlong legacy ETA preserved with no invented admission');
set local role authenticated;
set local request.jwt.claim.sub='10000000-0000-4000-8000-000000000040';
select lives_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
    values ('20000000-0000-4000-8000-000000000040','10000000-0000-4000-8000-000000000040',
    'Historical fixture','2026-01-01 UTC','2030-01-01 UTC','ACTIVE')
    on conflict(id) do update set expected_arrival_at=excluded.expected_arrival_at,
    status=excluded.status$$, 'historical accepted row may retry unchanged without retroactive cap');
reset role;

insert into auth.users(id, instance_id, aud, role, email, created_at, updated_at, is_anonymous) values
('10000000-0000-4000-8000-000000000010','00000000-0000-0000-0000-000000000000','authenticated','authenticated','boundary@example.invalid',now(),now(),false),
('10000000-0000-4000-8000-000000000011','00000000-0000-0000-0000-000000000000','authenticated','authenticated','other-boundary@example.invalid',now(),now(),false),
('10000000-0000-4000-8000-000000000012','00000000-0000-0000-0000-000000000000','authenticated','authenticated','anon-boundary@example.invalid',now(),now(),true);
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000010';
set local role authenticated;
insert into public.journeys(id, owner_id, destination, started_at, expected_arrival_at, status,
    unresolved_admission_id, unresolved_policy_version) values
('20000000-0000-4000-8000-000000000010','10000000-0000-4000-8000-000000000010','Fixture',
 '2026-01-01 00:00 UTC','2026-01-01 01:00 UTC','ACTIVE',
 '30000000-0000-4000-8000-000000000010','alabarin-unresolved-boundary-v1');
select lives_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
    values ('20000000-0000-4000-8000-000000000020','10000000-0000-4000-8000-000000000010','Fixture',
    '2026-01-01 UTC','2026-01-04 UTC','ACTIVE')$$, 'exactly 72 elapsed hours accepted');
select throws_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
    values ('20000000-0000-4000-8000-000000000021','10000000-0000-4000-8000-000000000010','Fixture',
    '2026-01-01 UTC','2026-01-04 00:00:00.001 UTC','ACTIVE')$$, '23514',
    'ETA must be after admission and within 72 elapsed hours', '72h plus 1ms rejected');
select throws_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
    values ('20000000-0000-4000-8000-000000000021','10000000-0000-4000-8000-000000000010','Fixture',
    '2026-01-01 UTC','2026-01-01 UTC','ACTIVE')$$, '23514', null, 'ETA equal to admission rejected');
select throws_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
    values ('20000000-0000-4000-8000-000000000021','10000000-0000-4000-8000-000000000010','Fixture',
    '2026-01-01 UTC','2025-12-31 UTC','ACTIVE')$$, '23514', null, 'ETA before admission rejected');
select throws_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
    values ('20000000-0000-4000-8000-000000000021','10000000-0000-4000-8000-000000000010','Fixture',
    '2026-01-01 UTC','2030-01-01 UTC','ACTIVE')$$, '23514', null, 'distant ETA rejected');
select throws_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
    values ('20000000-0000-4000-8000-000000000021','10000000-0000-4000-8000-000000000010','Fixture',
    '9999-12-30 UTC','infinity','ACTIVE')$$, '23514', null, 'nonfinite overflow input rejected');
select throws_ok($$update public.journeys set expected_arrival_at = expected_arrival_at + interval '1 millisecond'
    where id = '20000000-0000-4000-8000-000000000010'$$, '23514',
    'Admitted ETA and provenance are immutable', 'owner cannot move ETA');
select throws_ok($$update public.journeys set started_at = started_at - interval '1 hour'
    where id = '20000000-0000-4000-8000-000000000010'$$, '23514', null, 'owner cannot move admitted start');
select throws_ok($$update public.journeys set unresolved_admission_id = '30000000-0000-4000-8000-000000000099'
    where id = '20000000-0000-4000-8000-000000000010'$$, '23514', null, 'conflicting retry rejected');
select lives_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
    unresolved_admission_id,unresolved_policy_version) values
    ('20000000-0000-4000-8000-000000000010','10000000-0000-4000-8000-000000000010','Fixture',
     '2026-01-01 UTC','2026-01-01 01:00 UTC','ACTIVE','30000000-0000-4000-8000-000000000010',
     'alabarin-unresolved-boundary-v1') on conflict(id) do update set
     expected_arrival_at = excluded.expected_arrival_at, unresolved_admission_id = excluded.unresolved_admission_id$$,
    'identical delayed admission retries converge');
select lives_ok($$update public.journeys set unresolved_admission_id = null, unresolved_policy_version = null
    where id = '20000000-0000-4000-8000-000000000010'$$, 'old client omission preserves provenance');
select throws_ok($$update public.journeys set unresolved_admission_id = '30000000-0000-4000-8000-000000000020',
    unresolved_policy_version = 'alabarin-unresolved-boundary-v1' where id = '20000000-0000-4000-8000-000000000020'$$,
    '23514', null, 'legacy row cannot acquire invented authority');
select lives_ok($$update public.journeys set updated_at = now()
    where id = '20000000-0000-4000-8000-000000000020'$$, 'unrelated mutable fields are not frozen');
select throws_ok($$insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
    unresolved_admission_id,unresolved_policy_version) values
    ('20000000-0000-4000-8000-000000000022','10000000-0000-4000-8000-000000000010','Fixture',
     '2026-01-01 UTC','2026-01-01 02:00 UTC','ACTIVE','30000000-0000-4000-8000-000000000010',
     'alabarin-unresolved-boundary-v1')$$, '23505', null,
    'one admitted identity cannot migrate to another Journey');
reset role;
select is((select count(*) from private.unresolved_boundary_admissions), 1::bigint, 'one immutable authority record');
select ok((select boundary_at = original_eta + interval '86400 seconds' and
    original_eta = '2026-01-01 01:00 UTC'::timestamptz and accepted_at > original_eta and
    source = 'DEVICE_ATTESTED_LOCAL_ADMISSION' from private.unresolved_boundary_admissions),
    'delayed offline upload keeps original ETA and distinct server receipt');
set local timezone = 'America/New_York';
select is(extract(epoch from (('2026-03-08 01:00 America/New_York'::timestamptz + interval '86400 seconds') -
    '2026-03-08 01:00 America/New_York'::timestamptz)), 86400::numeric, 'duration ignores DST calendar changes');
set local timezone = 'UTC';

insert into public.journey_monitoring_state(journey_id,owner_id,phase,last_cloud_contact_at)
    values ('20000000-0000-4000-8000-000000000010','10000000-0000-4000-8000-000000000010',
    'EVIDENCE_FRESH','2025-12-31 UTC');
-- Future boundary fixture lets the same exact current verification episode
-- exercise before/exact/after without advancing production clocks.
with original as (select date_trunc('milliseconds',clock_timestamp()) - interval '2 hours' as admitted)
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status,
    unresolved_admission_id,unresolved_policy_version)
select '20000000-0000-4000-8000-000000000023','10000000-0000-4000-8000-000000000010','Fixture',
    admitted, admitted + interval '3 hours','ACTIVE','30000000-0000-4000-8000-000000000023',
    'alabarin-unresolved-boundary-v1' from original;
insert into public.journey_monitoring_state(journey_id,owner_id,phase,last_cloud_contact_at)
    values ('20000000-0000-4000-8000-000000000023','10000000-0000-4000-8000-000000000010',
    'EVIDENCE_FRESH',clock_timestamp()-interval '1 hour');
select is((select reason from private.read_unresolved_boundary('20000000-0000-4000-8000-000000000010',
    '10000000-0000-4000-8000-000000000010','2026-01-02 00:59:59.999 UTC')), 'BOUNDARY_NOT_REACHED', 'just before boundary not eligible');
select is((select reason from private.read_unresolved_boundary('20000000-0000-4000-8000-000000000010',
    '10000000-0000-4000-8000-000000000010',clock_timestamp())), 'NOT_VERIFYING', 'stale silence alone not unresolved');
select public.evaluate_due_journeys();
select is((select decision from private.read_unresolved_boundary('20000000-0000-4000-8000-000000000023',
    '10000000-0000-4000-8000-000000000010',
    (select boundary_at - interval '1 millisecond' from private.unresolved_boundary_admissions
     where journey_id='20000000-0000-4000-8000-000000000023'))), 'NOT_ELIGIBLE',
    'coherent case just before authoritative boundary is not eligible');
select is((select decision from private.read_unresolved_boundary('20000000-0000-4000-8000-000000000023',
    '10000000-0000-4000-8000-000000000010',
    (select boundary_at from private.unresolved_boundary_admissions
     where journey_id='20000000-0000-4000-8000-000000000023'))), 'ELIGIBLE',
    'coherent case at exact authoritative boundary is eligible');
select is((select decision from private.read_unresolved_boundary('20000000-0000-4000-8000-000000000023',
    '10000000-0000-4000-8000-000000000010',
    (select boundary_at + interval '1 millisecond' from private.unresolved_boundary_admissions
     where journey_id='20000000-0000-4000-8000-000000000023'))), 'ELIGIBLE',
    'coherent case after authoritative boundary is eligible');
select is((select decision from public.get_unresolved_boundary_eligibility_v1(
    '20000000-0000-4000-8000-000000000010')), 'ELIGIBLE', 'real watchdog coherent VERIFYING and OPEN qualify');
select ok((select verification_case_id = (select id from public.verification_cases where journey_id = '20000000-0000-4000-8000-000000000010' and status='OPEN')
    from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'exact current verification identity returned');
-- Preserve the actual episode but evaluate deterministic before/exact/after time boundaries.
-- Move fixture admission dates only in an independent rollback test savepoint; never production clocks.
select is((select decision from private.read_unresolved_boundary('20000000-0000-4000-8000-000000000010',
    '10000000-0000-4000-8000-000000000010',clock_timestamp() + interval '1 second')), 'ELIGIBLE', 'late boundary read eligible');
select ok((select count(*) = 1 from public.verification_cases where journey_id='20000000-0000-4000-8000-000000000010' and status='OPEN'), 'read creates no cases');
update public.journey_monitoring_state set phase='EVIDENCE_FRESH', verifying_started_at=null
    where journey_id='20000000-0000-4000-8000-000000000010';
select is((select decision from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'UNKNOWN', 'OPEN plus EVIDENCE_FRESH fails closed');
select public.resolve_open_verification_case('20000000-0000-4000-8000-000000000010','DEVICE_CONTACT_RESTORED',clock_timestamp());
select is((select decision from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'NOT_ELIGIBLE', 'resolved historical case cannot qualify');
update public.journey_monitoring_state set phase='VERIFYING', verifying_started_at=clock_timestamp()
    where journey_id='20000000-0000-4000-8000-000000000010';
select is((select decision from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'UNKNOWN', 'VERIFYING without current OPEN fails closed');
update public.journey_monitoring_state set phase='EVIDENCE_FRESH', verifying_started_at=null,
    last_cloud_contact_at='2025-12-31 UTC' where journey_id='20000000-0000-4000-8000-000000000010';
select public.evaluate_due_journeys();
select is((select decision from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'ELIGIBLE', 'new late verification episode may qualify immediately');
select ok((select verification_case_id <> (select id from public.verification_cases where journey_id='20000000-0000-4000-8000-000000000010' and status='RESOLVED')
    from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')), 'resolved identity not reused');
update public.verification_cases set opened_from_monitoring_event_id =
    (select min(id) from public.journey_monitoring_events where
        journey_id='20000000-0000-4000-8000-000000000010' and event_type='VERIFYING_STARTED')
    where journey_id='20000000-0000-4000-8000-000000000010' and status='OPEN';
select is((select decision from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'UNKNOWN', 'old opening event cannot stand for a newer verification episode');
update public.verification_cases set opened_from_monitoring_event_id =
    (select max(id) from public.journey_monitoring_events where
        journey_id='20000000-0000-4000-8000-000000000010' and event_type='VERIFYING_STARTED')
    where journey_id='20000000-0000-4000-8000-000000000010' and status='OPEN';
update public.journey_monitoring_state set last_authenticated_device_evidence_at=clock_timestamp()
    where journey_id='20000000-0000-4000-8000-000000000010';
select is((select reason from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'SUPERSEDED_EPISODE', 'new authenticated evidence supersedes stale VERIFYING');
set local role authenticated;
set local request.jwt.claim.sub='10000000-0000-4000-8000-000000000011';
select throws_ok($$select * from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')$$,
    '42501','Journey is not available to this caller','wrong owner nondisclosing');
select throws_ok($$select * from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000099')$$,
    '42501','Journey is not available to this caller','nonexistent same nondisclosing error');
set local request.jwt.claim.sub='10000000-0000-4000-8000-000000000012';
select throws_ok($$select * from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')$$,
    '42501','Non-anonymous Traveller identity required','anonymous-auth denied');
set local request.jwt.claim.sub='10000000-0000-4000-8000-000000000010';
select lives_ok($$update public.journeys set status='COMPLETED', completed_at=now()
    where id='20000000-0000-4000-8000-000000000010'$$, 'normal explicit completion still works');
select is((select reason from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000010')),
    'JOURNEY_NOT_ACTIVE', 'terminal Journey ineligible');
select is((select reason from public.get_unresolved_boundary_eligibility_v1('20000000-0000-4000-8000-000000000020')),
    'NO_ADMISSION_PROVENANCE', 'legacy ETA alone gives no authority');
reset role;
select * from finish();
rollback;

begin;
select no_plan();

select has_table('public', 'trusted_contact_sms_preferences', 'SMS preference table exists');
select has_table('public', 'trusted_contact_notification_outbox', 'SMS notification outbox exists');
select is((select relrowsecurity from pg_class where oid = 'public.trusted_contact_sms_preferences'::regclass), true, 'preference RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.trusted_contact_notification_outbox'::regclass), true, 'outbox RLS enabled');
select ok(not has_table_privilege('authenticated', 'public.trusted_contact_notification_outbox', 'SELECT,INSERT,UPDATE,DELETE'), 'authenticated clients have no outbox table privileges');
select ok(not has_table_privilege('anon', 'public.trusted_contact_sms_preferences', 'SELECT,INSERT,UPDATE,DELETE'), 'anonymous clients have no preference table privileges');
select ok(has_function_privilege('service_role', 'public.claim_due_sms_notifications(integer)', 'EXECUTE'), 'service role may claim notifications');
select ok(not has_function_privilege('authenticated', 'public.claim_due_sms_notifications(integer)', 'EXECUTE'), 'authenticated clients cannot claim notifications');
select ok(not has_function_privilege('anon', 'public.record_sms_send_success(uuid,uuid,text)', 'EXECUTE'), 'anonymous clients cannot forge provider acceptance');

insert into auth.users (id, instance_id, aud, role, email, email_confirmed_at, created_at, updated_at)
values
    ('10000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'traveller-m6@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000062', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'eligible-m6@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000063', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'disabled-m6@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000064', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'revoked-m6@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000065', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'unauthorized-m6@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000066', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'nophone-m6@example.invalid', now(), now(), now()),
    ('10000000-0000-0000-0000-000000000067', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'intruder-m6@example.invalid', now(), now(), now());

insert into public.trusted_contact_relationships (
    id, traveller_user_id, contact_user_id, display_name, contact_email_normalized, status, accepted_at, created_at, revoked_at
)
values
    ('30000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000062', 'Eligible contact', 'eligible-m6@example.invalid', 'ACCEPTED', now(), now(), null),
    ('30000000-0000-0000-0000-000000000062', '10000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000063', 'Disabled contact', 'disabled-m6@example.invalid', 'ACCEPTED', now(), now(), null),
    ('30000000-0000-0000-0000-000000000063', '10000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000064', 'Revoked contact', 'revoked-m6@example.invalid', 'REVOKED', now(), now(), now()),
    ('30000000-0000-0000-0000-000000000064', '10000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000065', 'Unauthorized contact', 'unauthorized-m6@example.invalid', 'ACCEPTED', now(), now(), null),
    ('30000000-0000-0000-0000-000000000065', '10000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000066', 'No-phone contact', 'nophone-m6@example.invalid', 'ACCEPTED', now(), now(), null);

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000061', 'Kaduna', now() + interval '3 hours', now(), 'ACTIVE');

delete from public.journey_trusted_contact_access
where journey_id = '20000000-0000-0000-0000-000000000061'
  and relationship_id = '30000000-0000-0000-0000-000000000064';
insert into public.journey_trusted_contact_access (
    journey_id, relationship_id, traveller_user_id, contact_user_id, revoked_at
) values (
    '20000000-0000-0000-0000-000000000061', '30000000-0000-0000-0000-000000000063',
    '10000000-0000-0000-0000-000000000061', '10000000-0000-0000-0000-000000000064', now()
);

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000062';
select lives_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000061', '+234 (801) 234-5678', true
    )$$,
    'accepted contact configures only their own SMS preference'
);
select is(
    (select masked_phone from public.list_trusted_contact_sms_preferences()
     where relationship_id = '30000000-0000-0000-0000-000000000061'),
    '+234******5678',
    'contact-facing preference list returns a masked number'
);
select throws_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000061', '08012345678', true
    )$$,
    '22023', 'Phone number must be in E.164 format', 'invalid E.164 input is rejected'
);
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select throws_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000061', '+2348099999999', true
    )$$,
    '42501', 'Accepted trusted relationship is not available to this caller',
    'traveller cannot enable SMS for the contact'
);
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000067';
select throws_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000061', '+2348099999999', true
    )$$,
    '42501', 'Accepted trusted relationship is not available to this caller',
    'unrelated authenticated user cannot change another contact preference'
);
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000064';
select throws_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000063', '+2348099999999', true
    )$$,
    '42501', 'Accepted trusted relationship is not available to this caller',
    'revoked relationship cannot configure SMS'
);
reset role;

insert into public.trusted_contact_sms_preferences (
    relationship_id, contact_user_id, phone_e164, sms_enabled, consented_at, disabled_at
)
values
    ('30000000-0000-0000-0000-000000000062', '10000000-0000-0000-0000-000000000063', '+2348022222222', false, null, now()),
    ('30000000-0000-0000-0000-000000000063', '10000000-0000-0000-0000-000000000064', '+2348033333333', true, now(), null),
    ('30000000-0000-0000-0000-000000000064', '10000000-0000-0000-0000-000000000065', '+2348044444444', true, now(), null),
    ('30000000-0000-0000-0000-000000000065', '10000000-0000-0000-0000-000000000066', null, false, null, now());

set local role anon;
select throws_ok(
    $$select * from public.list_trusted_contact_sms_preferences()$$,
    '42501', null, 'anonymous user cannot list SMS preferences'
);
select throws_ok(
    $$select * from public.claim_due_sms_notifications(1)$$,
    '42501', null, 'anonymous client cannot claim outbox notifications'
);
reset role;

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000061', 1, now(), 80, false, 'CELLULAR', 0
    )$$,
    'healthy Journey heartbeat initializes monitoring'
);
reset role;
select is((select count(*) from public.trusted_contact_notification_outbox), 0::bigint, 'healthy Journey queues no SMS notification');

update public.journey_monitoring_state
set last_cloud_contact_at = now() - interval '6 minutes'
where journey_id = '20000000-0000-0000-0000-000000000061';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'watchdog opens the first verification case');
select is((select count(*) from public.verification_cases where journey_id = '20000000-0000-0000-0000-000000000061' and status = 'OPEN'), 1::bigint, 'VERIFYING still opens exactly one case');
select is((select count(*) from public.trusted_contact_notification_outbox where notification_kind = 'VERIFICATION_STARTED'), 1::bigint, 'one eligible contact queues exactly one started notification');
select is((select relationship_id from public.trusted_contact_notification_outbox where notification_kind = 'VERIFICATION_STARTED'), '30000000-0000-0000-0000-000000000061'::uuid, 'only the enabled authorized accepted contact is eligible');
select is((select destination_phone_e164 from public.trusted_contact_notification_outbox where notification_kind = 'VERIFICATION_STARTED'), '+2348012345678', 'outbox snapshots normalized destination');
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'repeated watchdog evaluation stays idempotent');
select is((select count(*) from public.trusted_contact_notification_outbox where notification_kind = 'VERIFICATION_STARTED'), 1::bigint, 'repeated watchdog does not duplicate the logical SMS');

update public.trusted_contact_notification_outbox
set next_attempt_at = now() - interval '1 second'
where journey_id = '20000000-0000-0000-0000-000000000061'
  and notification_kind = 'VERIFICATION_STARTED';
create temporary table restoration_started_claim as
select * from public.claim_due_sms_notifications(1);
select is(
    public.record_sms_send_failure(
        (select notification_id from restoration_started_claim),
        (select lease_token from restoration_started_claim),
        'TRANSIENT', 'THROTTLINGEXCEPTION'
    ),
    'RETRY_PENDING',
    'started notification can be waiting for retry when contact returns'
);

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000061', 2, now(), 79, false, 'WIFI', 0
    )$$,
    'fresh heartbeat resolves the verification case'
);
reset role;
select is((select count(*) from public.trusted_contact_notification_outbox where notification_kind = 'DEVICE_CONTACT_RESTORED'), 1::bigint, 'fresh heartbeat queues exactly one restoration notification');
select is((select count(*) from public.trusted_contact_notification_outbox where notification_kind = 'JOURNEY_COMPLETED'), 0::bigint, 'restoration does not fabricate completion notification');
select is(
    (select state from public.trusted_contact_notification_outbox
     where journey_id = '20000000-0000-0000-0000-000000000061'
       and notification_kind = 'VERIFICATION_STARTED'),
    'FAILED',
    'retry-pending started notification becomes terminal when contact is restored'
);
select is(
    (select failure_classification from public.trusted_contact_notification_outbox
     where journey_id = '20000000-0000-0000-0000-000000000061'
       and notification_kind = 'VERIFICATION_STARTED'),
    'SUPERSEDED',
    'restoration classifies the stale started notification as superseded'
);

-- Simulate a stale row that escaped transition cleanup. Claim-time validation must
-- independently terminalize it and return no work for the resolved case.
update public.trusted_contact_notification_outbox
set state = 'PENDING', failure_classification = null, last_failure_code = null,
    terminal_at = null, next_attempt_at = now() - interval '1 second'
where journey_id = '20000000-0000-0000-0000-000000000061'
  and notification_kind = 'VERIFICATION_STARTED';
update public.trusted_contact_notification_outbox
set next_attempt_at = now() + interval '1 hour'
where journey_id = '20000000-0000-0000-0000-000000000061'
  and notification_kind = 'DEVICE_CONTACT_RESTORED';
create temporary table stale_started_claim as select * from public.claim_due_sms_notifications(1);
select is((select count(*) from stale_started_claim), 0::bigint, 'claim RPC refuses a started notification for a resolved case');
select is(
    (select failure_classification from public.trusted_contact_notification_outbox
     where journey_id = '20000000-0000-0000-0000-000000000061'
       and notification_kind = 'VERIFICATION_STARTED'),
    'SUPERSEDED',
    'claim-time defense terminalizes escaped stale started work'
);

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-0000-0000-000000000062', '10000000-0000-0000-0000-000000000061', 'Kano', now() + interval '4 hours', now(), 'ACTIVE');
delete from public.journey_trusted_contact_access
where journey_id = '20000000-0000-0000-0000-000000000062'
  and relationship_id = '30000000-0000-0000-0000-000000000064';
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000062', 1, now(), 70, false, 'CELLULAR', 0
    )$$,
    'second Journey initializes monitoring'
);
reset role;
update public.journey_monitoring_state
set last_cloud_contact_at = now() - interval '6 minutes'
where journey_id = '20000000-0000-0000-0000-000000000062';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'second Journey enters VERIFYING');
update public.journeys set status = 'COMPLETED', completed_at = now()
where id = '20000000-0000-0000-0000-000000000062';
select is((select resolution_reason from public.verification_cases where journey_id = '20000000-0000-0000-0000-000000000062'), 'JOURNEY_COMPLETED', 'offline completion resolves the case as completed');
select is((select count(*) from public.trusted_contact_notification_outbox o join public.verification_cases c on c.id = o.verification_case_id where c.journey_id = '20000000-0000-0000-0000-000000000062' and o.notification_kind = 'JOURNEY_COMPLETED'), 1::bigint, 'completion queues exactly one completion notification');
select is((select count(*) from public.trusted_contact_notification_outbox o join public.verification_cases c on c.id = o.verification_case_id where c.journey_id = '20000000-0000-0000-0000-000000000062' and o.notification_kind = 'DEVICE_CONTACT_RESTORED'), 0::bigint, 'completion does not fabricate a restoration notification');
select is(
    (select failure_classification from public.trusted_contact_notification_outbox
     where journey_id = '20000000-0000-0000-0000-000000000062'
       and notification_kind = 'VERIFICATION_STARTED'),
    'SUPERSEDED',
    'completion terminalizes its still-pending started notification'
);

-- Provider-accepted started history is immutable when the case later resolves.
insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-0000-0000-000000000064', '10000000-0000-0000-0000-000000000061', 'Minna', now() + interval '2 hours', now(), 'ACTIVE');
delete from public.journey_trusted_contact_access
where journey_id = '20000000-0000-0000-0000-000000000064'
  and relationship_id = '30000000-0000-0000-0000-000000000064';
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000064', 1, now(), 72, false, 'CELLULAR', 0
    )$$,
    'provider-accepted history Journey initializes monitoring'
);
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '6 minutes'
where journey_id = '20000000-0000-0000-0000-000000000064';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'provider-accepted history Journey enters VERIFYING');
update public.trusted_contact_notification_outbox set next_attempt_at = now() + interval '1 hour';
update public.trusted_contact_notification_outbox set next_attempt_at = now() - interval '1 second'
where journey_id = '20000000-0000-0000-0000-000000000064'
  and notification_kind = 'VERIFICATION_STARTED';
create temporary table accepted_started_claim as select * from public.claim_due_sms_notifications(1);
select is(
    public.record_sms_send_success(
        (select notification_id from accepted_started_claim),
        (select lease_token from accepted_started_claim),
        'provider-accepted-started-id'
    ),
    true,
    'started notification can record provider acceptance while case is open'
);
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000064', 2, now(), 71, false, 'WIFI', 0
    )$$,
    'provider-accepted history Journey restores contact'
);
reset role;
select is(
    (select state from public.trusted_contact_notification_outbox
     where journey_id = '20000000-0000-0000-0000-000000000064'
       and notification_kind = 'VERIFICATION_STARTED'),
    'PROVIDER_ACCEPTED',
    'resolution does not rewrite provider-accepted started history'
);
select is(
    (select provider_message_id from public.trusted_contact_notification_outbox
     where journey_id = '20000000-0000-0000-0000-000000000064'
       and notification_kind = 'VERIFICATION_STARTED'),
    'provider-accepted-started-id',
    'resolution preserves the historical provider message id'
);

-- Closest practical database-boundary overlap: a row has an active claim when
-- resolution occurs. Resolution invalidates its lease before acknowledgement.
insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-0000-0000-000000000065', '10000000-0000-0000-0000-000000000061', 'Jos', now() + interval '2 hours', now(), 'ACTIVE');
delete from public.journey_trusted_contact_access
where journey_id = '20000000-0000-0000-0000-000000000065'
  and relationship_id = '30000000-0000-0000-0000-000000000064';
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000065', 1, now(), 69, false, 'CELLULAR', 0
    )$$,
    'claim-overlap Journey initializes monitoring'
);
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '6 minutes'
where journey_id = '20000000-0000-0000-0000-000000000065';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'claim-overlap Journey enters VERIFYING');
update public.trusted_contact_notification_outbox set next_attempt_at = now() + interval '1 hour';
update public.trusted_contact_notification_outbox set next_attempt_at = now() - interval '1 second'
where journey_id = '20000000-0000-0000-0000-000000000065'
  and notification_kind = 'VERIFICATION_STARTED';
create temporary table overlapping_started_claim as select * from public.claim_due_sms_notifications(1);
select is((select count(*) from overlapping_started_claim), 1::bigint, 'started row has an active claim before resolution');
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000065', 2, now(), 68, false, 'WIFI', 0
    )$$,
    'resolution overlaps an active started-notification lease'
);
reset role;
select is(
    (select failure_classification from public.trusted_contact_notification_outbox
     where id = (select notification_id from overlapping_started_claim)),
    'SUPERSEDED',
    'resolution terminalizes an actively leased started notification'
);
select is(
    public.record_sms_send_success(
        (select notification_id from overlapping_started_claim),
        (select lease_token from overlapping_started_claim),
        'late-provider-ack'
    ),
    false,
    'superseded lease cannot record a late provider acceptance acknowledgement'
);
update public.trusted_contact_notification_outbox set next_attempt_at = now() + interval '1 hour';
update public.trusted_contact_notification_outbox set next_attempt_at = now() - interval '1 second'
where journey_id = '20000000-0000-0000-0000-000000000065'
  and notification_kind = 'DEVICE_CONTACT_RESTORED';
create temporary table overlap_resolution_claim as select * from public.claim_due_sms_notifications(1);
select is((select notification_kind from overlap_resolution_claim), 'DEVICE_CONTACT_RESTORED', 'resolution notification remains claimable after started work is superseded');
select is(
    (select count(*) from overlap_resolution_claim where notification_kind = 'VERIFICATION_STARTED'),
    0::bigint,
    'stale started notification is never returned after overlap resolution'
);

-- Isolate one due row to exercise lease exclusion, abandoned recovery, and success terminality.
update public.trusted_contact_notification_outbox set next_attempt_at = now() + interval '1 hour';
update public.trusted_contact_notification_outbox set next_attempt_at = now() - interval '1 second'
where id = (
    select id from public.trusted_contact_notification_outbox
    where state = 'PENDING'
      and notification_kind in ('DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED')
    order by created_at limit 1
);
create temporary table first_claim as select * from public.claim_due_sms_notifications(1);
select is((select count(*) from first_claim), 1::bigint, 'one due row is claimed');
select is((select count(*) from public.claim_due_sms_notifications(1)), 0::bigint, 'active lease prevents overlapping claim of the same row');
update public.trusted_contact_notification_outbox set lease_expires_at = now() - interval '1 second'
where id = (select notification_id from first_claim);
create temporary table recovered_claim as select * from public.claim_due_sms_notifications(1);
select is((select notification_id from recovered_claim), (select notification_id from first_claim), 'abandoned lease becomes claimable after expiry');
select is((select attempt_count from recovered_claim), 2, 'abandoned lease recovery increments the bounded attempt count');
select is(
    public.record_sms_send_success(
        (select notification_id from recovered_claim),
        (select lease_token from recovered_claim),
        'provider-accepted-id'
    ), true, 'active lease can record provider acceptance'
);
select is((select state from public.trusted_contact_notification_outbox where id = (select notification_id from recovered_claim)), 'PROVIDER_ACCEPTED', 'success is stored as provider accepted, not delivery');
select is((select count(*) from public.claim_due_sms_notifications(1)), 0::bigint, 'provider-accepted row is never reclaimed');

-- Retryable and permanent provider failures remain transport-only state.
update public.trusted_contact_notification_outbox set next_attempt_at = now() - interval '1 second'
where state = 'PENDING' and id = (select id from public.trusted_contact_notification_outbox where state = 'PENDING' order by created_at limit 1);
create temporary table retry_claim as select * from public.claim_due_sms_notifications(1);
select is(
    public.record_sms_send_failure(
        (select notification_id from retry_claim), (select lease_token from retry_claim),
        'TRANSIENT', 'THROTTLINGEXCEPTION'
    ), 'RETRY_PENDING', 'retryable failure schedules a bounded retry'
);
select ok((select next_attempt_at > now() from public.trusted_contact_notification_outbox where id = (select notification_id from retry_claim)), 'retry receives exponential backoff');
update public.trusted_contact_notification_outbox set next_attempt_at = now() - interval '1 second'
where id = (select notification_id from retry_claim);
create temporary table permanent_claim as select * from public.claim_due_sms_notifications(1);
select is(
    public.record_sms_send_failure(
        (select notification_id from permanent_claim), (select lease_token from permanent_claim),
        'PERMANENT', 'VALIDATIONEXCEPTION'
    ), 'FAILED', 'permanent provider failure is terminal'
);
select is((select state from public.trusted_contact_notification_outbox where id = (select notification_id from permanent_claim)), 'FAILED', 'permanent failure row remains terminal');
select is((select phase from public.journey_monitoring_state where journey_id = '20000000-0000-0000-0000-000000000061'), 'EVIDENCE_FRESH', 'SMS failure does not alter restored monitoring phase');

update public.trusted_contact_notification_outbox
set attempt_count = 4, next_attempt_at = now() - interval '1 second'
where id = (select id from public.trusted_contact_notification_outbox where state = 'PENDING' order by created_at limit 1);
create temporary table max_attempt_claim as select * from public.claim_due_sms_notifications(1);
select is((select attempt_count from max_attempt_claim), 5, 'fifth claim reaches the centralized maximum attempt count');
select is(
    public.record_sms_send_failure(
        (select notification_id from max_attempt_claim), (select lease_token from max_attempt_claim),
        'TRANSIENT', 'INTERNALSERVEREXCEPTION'
    ), 'FAILED', 'retryable failure becomes terminal at the maximum attempt count'
);
select is((select failure_classification from public.trusted_contact_notification_outbox where id = (select notification_id from max_attempt_claim)), 'MAX_ATTEMPTS', 'bounded retries record a safe maximum-attempt classification');

-- Opt-out terminalizes unsent history and prevents future enqueue while cases continue normally.
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000062';
select lives_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000061', null, false
    )$$,
    'contact can opt out immediately'
);
reset role;
select is((select sms_enabled from public.trusted_contact_sms_preferences where relationship_id = '30000000-0000-0000-0000-000000000061'), false, 'opt-out is persisted');

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-0000-0000-000000000063', '10000000-0000-0000-0000-000000000061', 'Zaria', now() + interval '2 hours', now(), 'ACTIVE');
delete from public.journey_trusted_contact_access
where journey_id = '20000000-0000-0000-0000-000000000063'
  and relationship_id = '30000000-0000-0000-0000-000000000064';
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select * from public.record_journey_heartbeat(
        '20000000-0000-0000-0000-000000000063', 1, now(), 65, false, 'CELLULAR', 0
    )$$,
    'opt-out test Journey initializes normally'
);
reset role;
update public.journey_monitoring_state set last_cloud_contact_at = now() - interval '6 minutes'
where journey_id = '20000000-0000-0000-0000-000000000063';
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'verification still opens after contact opts out');
select is((select count(*) from public.verification_cases where journey_id = '20000000-0000-0000-0000-000000000063'), 1::bigint, 'opt-out does not affect deterministic verification case creation');
select is((select count(*) from public.trusted_contact_notification_outbox o join public.verification_cases c on c.id = o.verification_case_id where c.journey_id = '20000000-0000-0000-0000-000000000063'), 0::bigint, 'disabled SMS queues no notification');

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000062';
select lives_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000061', null, true
    )$$,
    'contact may explicitly opt back in using their retained number'
);
reset role;
insert into public.trusted_contact_notification_outbox (
    journey_id, verification_case_id, relationship_id, contact_user_id,
    notification_kind, destination_phone_e164
)
select c.journey_id, c.id, '30000000-0000-0000-0000-000000000061',
       '10000000-0000-0000-0000-000000000062', 'VERIFICATION_STARTED', '+2348012345678'
from public.verification_cases as c
where c.journey_id = '20000000-0000-0000-0000-000000000063';
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000062';
select lives_ok(
    $$select * from public.set_trusted_contact_sms_preference(
        '30000000-0000-0000-0000-000000000061', '+2348098765432', true
    )$$,
    'contact may replace their configured number'
);
reset role;
select is((select destination_phone_e164 from public.trusted_contact_notification_outbox where verification_case_id = (select id from public.verification_cases where journey_id = '20000000-0000-0000-0000-000000000063') and notification_kind = 'VERIFICATION_STARTED'), '+2348012345678', 'phone change never rewrites historical destination snapshots');
select is((select failure_classification from public.trusted_contact_notification_outbox where verification_case_id = (select id from public.verification_cases where journey_id = '20000000-0000-0000-0000-000000000063') and notification_kind = 'VERIFICATION_STARTED'), 'PREFERENCE_CHANGED', 'unsent old-destination work becomes terminal after phone change');

insert into public.trusted_contact_notification_outbox (
    journey_id, verification_case_id, relationship_id, contact_user_id,
    notification_kind, destination_phone_e164
)
select c.journey_id, c.id, '30000000-0000-0000-0000-000000000061',
       '10000000-0000-0000-0000-000000000062', 'DEVICE_CONTACT_RESTORED', '+2348098765432'
from public.verification_cases as c
where c.journey_id = '20000000-0000-0000-0000-000000000063';
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
select lives_ok(
    $$select public.revoke_trusted_contact('30000000-0000-0000-0000-000000000061')$$,
    'traveller revocation still uses the bounded relationship RPC'
);
reset role;
select is((select state from public.trusted_contact_notification_outbox where verification_case_id = (select id from public.verification_cases where journey_id = '20000000-0000-0000-0000-000000000063') and notification_kind = 'DEVICE_CONTACT_RESTORED'), 'FAILED', 'revocation immediately terminalizes unsent SMS work');
select is((select failure_classification from public.trusted_contact_notification_outbox where verification_case_id = (select id from public.verification_cases where journey_id = '20000000-0000-0000-0000-000000000063') and notification_kind = 'DEVICE_CONTACT_RESTORED'), 'RELATIONSHIP_REVOKED', 'revocation records a safe transport failure classification');

select * from finish();
rollback;

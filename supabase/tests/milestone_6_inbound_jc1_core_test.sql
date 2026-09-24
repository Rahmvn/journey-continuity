begin;

select no_plan();

select has_table('private', 'fallback_authenticated_envelopes', 'authenticated envelope evidence exists');
select has_table('private', 'fallback_inbound_receipts', 'immutable inbound receipts exist');
select has_table('private', 'fallback_observation_reconciliation_events', 'reconciliation evidence exists');
select has_column('public', 'journey_monitoring_state', 'last_authenticated_device_evidence_at', 'transport-neutral evidence time exists');
select has_column('public', 'journey_monitoring_state', 'last_authenticated_device_evidence_transport', 'transport-neutral evidence source exists');
select ok(not has_schema_privilege('anon', 'private', 'USAGE'), 'anonymous callers cannot use the private schema');
select ok(not has_schema_privilege('authenticated', 'private', 'USAGE'), 'authenticated callers cannot use the private schema');
select ok(not has_table_privilege('authenticated', 'private.fallback_installation_keys', 'SELECT'), 'clients cannot read encrypted key material');
select ok(not has_table_privilege('authenticated', 'private.fallback_inbound_receipts', 'SELECT'), 'clients cannot read inbound receipt evidence');
select ok(not has_function_privilege(
    'anon',
    'public.record_fallback_inbound_result_backend(text,text,timestamptz,timestamptz,integer,bytea,text,text,bigint,bytea,bigint,bigint,timestamptz,integer,integer,integer,integer,boolean,text)',
    'EXECUTE'
), 'anonymous callers cannot invoke backend ingestion');
select ok(not has_function_privilege(
    'authenticated',
    'public.record_fallback_inbound_result_backend(text,text,timestamptz,timestamptz,integer,bytea,text,text,bigint,bytea,bigint,bigint,timestamptz,integer,integer,integer,integer,boolean,text)',
    'EXECUTE'
), 'authenticated callers cannot invoke backend ingestion');
select ok(has_function_privilege(
    'service_role',
    'public.record_fallback_inbound_result_backend(text,text,timestamptz,timestamptz,integer,bytea,text,text,bigint,bytea,bigint,bigint,timestamptz,integer,integer,integer,integer,boolean,text)',
    'EXECUTE'
), 'trusted server execution can invoke backend ingestion');

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at)
values (
    '10000000-0000-4000-8000-000000000091',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'inbound-owner@example.invalid', now(), now()
);

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values
    ('20000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091', 'SMS first', now() + interval '1 hour', now() - interval '1 hour', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000092', '10000000-0000-4000-8000-000000000091', 'Internet first', now() + interval '1 hour', now() - interval '1 hour', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000093', '10000000-0000-4000-8000-000000000091', 'Revoked key', now() + interval '1 hour', now() - interval '1 hour', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000094', '10000000-0000-4000-8000-000000000091', 'Revoked binding', now() + interval '1 hour', now() - interval '1 hour', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000095', '10000000-0000-4000-8000-000000000091', 'Watchdog', now() + interval '1 hour', now() - interval '1 hour', 'ACTIVE'),
    ('20000000-0000-4000-8000-000000000096', '10000000-0000-4000-8000-000000000091', 'Completion', now() + interval '1 hour', now() - interval '1 hour', 'ACTIVE');

insert into private.fallback_installations (id, owner_id, installation_identifier)
values (
    '30000000-0000-4000-8000-000000000091',
    '10000000-0000-4000-8000-000000000091',
    '31000000-0000-4000-8000-000000000091'
);

insert into private.fallback_installation_keys (
    id, installation_id, owner_id, key_id, encrypted_master_key, encryption_iv,
    encryption_version, lifecycle_status, retired_at, revoked_at
) values
    ('40000000-0000-4000-8000-000000000091', '30000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091', 1001, decode(repeat('11', 48), 'hex'), decode(repeat('12', 12), 'hex'), 1, 'ACTIVE', null, null),
    ('40000000-0000-4000-8000-000000000092', '30000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091', 1002, decode(repeat('21', 48), 'hex'), decode(repeat('22', 12), 'hex'), 1, 'RETIRED', now() - interval '1 day', null),
    ('40000000-0000-4000-8000-000000000093', '30000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091', 1003, decode(repeat('31', 48), 'hex'), decode(repeat('32', 12), 'hex'), 1, 'REVOKED', null, now() - interval '1 day');

insert into private.journey_fallback_bindings (
    id, journey_id, owner_id, installation_id, installation_key_id, key_id,
    journey_handle, binding_status, revoked_at
) values
    ('50000000-0000-4000-8000-000000000091', '20000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091', '30000000-0000-4000-8000-000000000091', '40000000-0000-4000-8000-000000000091', 1001, decode(repeat('a1', 12), 'hex'), 'ACTIVE', null),
    ('50000000-0000-4000-8000-000000000092', '20000000-0000-4000-8000-000000000092', '10000000-0000-4000-8000-000000000091', '30000000-0000-4000-8000-000000000091', '40000000-0000-4000-8000-000000000092', 1002, decode(repeat('a2', 12), 'hex'), 'ACTIVE', null),
    ('50000000-0000-4000-8000-000000000093', '20000000-0000-4000-8000-000000000093', '10000000-0000-4000-8000-000000000091', '30000000-0000-4000-8000-000000000091', '40000000-0000-4000-8000-000000000093', 1003, decode(repeat('a3', 12), 'hex'), 'ACTIVE', null),
    ('50000000-0000-4000-8000-000000000094', '20000000-0000-4000-8000-000000000094', '10000000-0000-4000-8000-000000000091', '30000000-0000-4000-8000-000000000091', '40000000-0000-4000-8000-000000000091', 1001, decode(repeat('a4', 12), 'hex'), 'REVOKED', now() - interval '1 minute'),
    ('50000000-0000-4000-8000-000000000095', '20000000-0000-4000-8000-000000000095', '10000000-0000-4000-8000-000000000091', '30000000-0000-4000-8000-000000000091', '40000000-0000-4000-8000-000000000091', 1001, decode(repeat('a5', 12), 'hex'), 'ACTIVE', null),
    ('50000000-0000-4000-8000-000000000096', '20000000-0000-4000-8000-000000000096', '10000000-0000-4000-8000-000000000091', '30000000-0000-4000-8000-000000000091', '40000000-0000-4000-8000-000000000091', 1001, decode(repeat('a6', 12), 'hex'), 'ACTIVE', null);

create temporary table resolved_material as
select * from public.resolve_fallback_ingestion_material_backend(1001, decode(repeat('a1', 12), 'hex'));
select is((select installation_row_id from resolved_material), '30000000-0000-4000-8000-000000000091'::uuid, 'resolver identifies the internal installation row explicitly');
select is((select installation_identifier from resolved_material), '31000000-0000-4000-8000-000000000091'::uuid, 'resolver returns the original provisioning identifier for wrap AAD');
select ok((select installation_row_id <> installation_identifier from resolved_material), 'regression fixture has distinct internal and provisioning identifiers');
select ok(not has_function_privilege('anon', 'public.resolve_fallback_ingestion_material_backend(bigint,bytea)', 'EXECUTE'), 'anonymous clients cannot resolve key material');
select ok(not has_function_privilege('authenticated', 'public.resolve_fallback_ingestion_material_backend(bigint,bytea)', 'EXECUTE'), 'authenticated clients cannot resolve key material');
select ok(has_function_privilege('service_role', 'public.resolve_fallback_ingestion_material_backend(bigint,bytea)', 'EXECUTE'), 'service role can resolve the corrected material');

insert into public.journey_monitoring_state (
    journey_id, owner_id, phase, last_cloud_contact_at, latest_heartbeat_sequence,
    last_authenticated_device_evidence_at, last_authenticated_device_evidence_transport,
    last_authenticated_device_evidence_received_at
) select id, owner_id, 'EVIDENCE_FRESH', now() - interval '10 minutes', 1,
         now() - interval '10 minutes', 'CLOUD_HEARTBEAT', now() - interval '10 minutes'
from public.journeys where id in (
    '20000000-0000-4000-8000-000000000091',
    '20000000-0000-4000-8000-000000000092',
    '20000000-0000-4000-8000-000000000095',
    '20000000-0000-4000-8000-000000000096'
);

create function pg_temp.record_jc1(
    p_event_id text, p_key_id bigint, p_handle_hex text, p_envelope bigint,
    p_telemetry bigint, p_event_time timestamptz, p_digest_hex text,
    p_event_type text default 'OBSERVATION', p_arrived_at timestamptz default now()
)
returns table (
    receipt_id uuid, classification text, duplicate_provider_event boolean,
    duplicate_envelope boolean, reconciliation text, evidence_advanced boolean
)
language sql
as $$
    select * from public.record_fallback_inbound_result_backend(
        'verified-test-adapter', p_event_id, p_arrived_at, now(), 102,
        decode(p_digest_hex, 'hex'), 'AUTHENTICATED', p_event_type, p_key_id,
        decode(p_handle_hex, 'hex'), p_envelope, p_telemetry, p_event_time,
        65432100, 32109800, 125, 67, true, 'CELLULAR'
    )
$$;

create temporary table first_sms as
select * from pg_temp.record_jc1('sms-first-1', 1001, repeat('a1', 12), 10, 5, date_trunc('second', now() - interval '1 minute'), repeat('01', 32));
select is((select classification from first_sms), 'AUTHENTICATED_NEW', 'SMS-first envelope authenticates as new');
select is((select reconciliation from first_sms), 'SMS_CREATED_CANONICAL', 'SMS-first materializes the canonical observation');
select is((select evidence_advanced from first_sms), true, 'timely new SMS advances authenticated device evidence');
select is((select count(*) from public.telemetry_observations where journey_id = '20000000-0000-4000-8000-000000000091' and sequence = 5), 1::bigint, 'one canonical observation exists for Journey and telemetry sequence');
select is((select last_authenticated_device_evidence_transport from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), 'FALLBACK_SMS', 'evidence transport is recorded explicitly');
select is((select last_cloud_contact_at from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), now() - interval '10 minutes', 'SMS does not rewrite cloud contact time');
select is((select last_authenticated_device_evidence_at from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), date_trunc('second', now() - interval '1 minute'), 'evidence uses JC1 observation time, not receipt time');

create temporary table same_provider_event as
select * from pg_temp.record_jc1('sms-first-1', 1001, repeat('a1', 12), 10, 5, date_trunc('second', now() - interval '1 minute'), repeat('01', 32));
select is((select duplicate_provider_event from same_provider_event), true, 'same provider event is idempotent');
select is((select count(*) from private.fallback_inbound_receipts where provider_event_id = 'sms-first-1'), 1::bigint, 'provider-event retry creates no receipt duplicate');

create temporary table same_envelope as
select * from pg_temp.record_jc1('sms-first-duplicate-envelope', 1001, repeat('a1', 12), 10, 5, date_trunc('second', now() - interval '1 minute'), repeat('01', 32));
select is((select classification from same_envelope), 'AUTHENTICATED_DUPLICATE', 'same envelope through another provider event is classified duplicate');
select is((select duplicate_envelope from same_envelope), true, 'duplicate envelope is reported');
select is((select count(*) from private.fallback_inbound_receipts where key_id = 1001 and journey_handle = decode(repeat('a1', 12), 'hex') and envelope_sequence = 10), 2::bigint, 'distinct provider receipt evidence is preserved');
select is((select count(*) from private.fallback_authenticated_envelopes where key_id = 1001 and journey_handle = decode(repeat('a1', 12), 'hex') and envelope_sequence = 10), 1::bigint, 'replayed envelope creates no authenticated envelope duplicate');

create temporary table conflicting_envelope as
select * from pg_temp.record_jc1('sms-first-conflicting-envelope', 1001, repeat('a1', 12), 10, 6, date_trunc('second', now() - interval '30 seconds'), repeat('02', 32));
select is((select classification from conflicting_envelope), 'AUTHENTICATED_ENVELOPE_CONFLICT', 'same envelope identity with another digest is flagged');
select is((select reconciliation from conflicting_envelope), 'ENVELOPE_SEQUENCE_CONFLICT', 'envelope sequence conflict is preserved deterministically');

create temporary table delayed_lower as
select * from pg_temp.record_jc1('delayed-lower', 1001, repeat('a1', 12), 9, 4, date_trunc('second', now() - interval '4 minutes'), repeat('03', 32));
select is((select classification from delayed_lower), 'AUTHENTICATED_NEW', 'out-of-order lower sequence remains authenticated history');
select is((select evidence_advanced from delayed_lower), false, 'lower envelope and telemetry sequence cannot regress current evidence');
select is((select latest_authenticated_fallback_envelope_sequence from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000091'), 10::bigint, 'out-of-order arrival does not regress latest envelope sequence');

insert into public.telemetry_observations (
    journey_id, sequence, event_time, latitude, longitude, accuracy_meters,
    battery_percent, charging, connectivity_state, received_at
) values
    ('20000000-0000-4000-8000-000000000092', 8, date_trunc('second', now() - interval '2 minutes'), 6.54321, 3.21098, 12.5, 67, true, 'CELLULAR', now() - interval '90 seconds'),
    ('20000000-0000-4000-8000-000000000092', 9, now() - interval '1 minute', 1.0, 2.0, 25.0, 50, false, 'WIFI', now() - interval '50 seconds');

create temporary table internet_first_match as
select * from pg_temp.record_jc1('internet-first-match', 1002, repeat('a2', 12), 1, 8, date_trunc('second', now() - interval '2 minutes'), repeat('04', 32));
select is((select classification from internet_first_match), 'AUTHENTICATED_NEW', 'RETIRED key authenticates for an existing active binding');
select is((select reconciliation from internet_first_match), 'SMS_MATCHED_INTERNET', 'internet-first matching SMS attaches to the canonical observation');
select is((select count(*) from public.telemetry_observations where journey_id = '20000000-0000-4000-8000-000000000092' and sequence = 8), 1::bigint, 'internet-first reconciliation does not duplicate the observation');

create temporary table internet_first_conflict as
select * from pg_temp.record_jc1('internet-first-conflict', 1002, repeat('a2', 12), 2, 9, date_trunc('second', now() - interval '1 minute'), repeat('05', 32));
select is((select reconciliation from internet_first_conflict), 'SMS_CONFLICT', 'conflicting internet-first observation is flagged');
select is((select latitude from public.telemetry_observations where journey_id = '20000000-0000-4000-8000-000000000092' and sequence = 9), 1.0::double precision, 'conflicting SMS never overwrites canonical internet data');

create temporary table sms_then_internet as
select * from pg_temp.record_jc1('sms-then-internet', 1001, repeat('a1', 12), 11, 6, date_trunc('second', now() - interval '30 seconds'), repeat('06', 32));
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
update public.telemetry_observations
set received_at = now(), accuracy_meters = 12.50
where journey_id = '20000000-0000-4000-8000-000000000091' and sequence = 6;
reset role;
select is((select count(*) from private.fallback_observation_reconciliation_events where outcome = 'INTERNET_MATCHED_SMS' and telemetry_sequence = 6), 1::bigint, 'matching later internet telemetry converges with SMS-first observation');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
update public.telemetry_observations
set latitude = 1.25, longitude = 2.5
where journey_id = '20000000-0000-4000-8000-000000000091' and sequence = 6;
reset role;
select is((select count(*) from private.fallback_observation_reconciliation_events where outcome = 'INTERNET_CONFLICT_SMS' and telemetry_sequence = 6), 1::bigint, 'conflicting later internet telemetry is preserved as discrepancy evidence');
select is((select latitude from public.telemetry_observations where journey_id = '20000000-0000-4000-8000-000000000091' and sequence = 6), 6.54321::double precision, 'conflicting later internet telemetry cannot silently overwrite canonical data');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000091';
update public.telemetry_observations
set sequence = 7
where journey_id = '20000000-0000-4000-8000-000000000091' and sequence = 6;
reset role;
select is((select count(*) from public.telemetry_observations where journey_id = '20000000-0000-4000-8000-000000000091' and sequence = 6), 1::bigint, 'internet reconciliation cannot move a fallback-created logical observation identity');
select is((select count(*) from public.telemetry_observations where journey_id = '20000000-0000-4000-8000-000000000091' and sequence = 7), 0::bigint, 'conflicting identity update creates no replacement canonical observation');

create temporary table revoked_key as
select * from pg_temp.record_jc1('revoked-key', 1003, repeat('a3', 12), 1, 1, date_trunc('second', now() - interval '1 minute'), repeat('07', 32));
create temporary table revoked_binding as
select * from pg_temp.record_jc1('revoked-binding', 1001, repeat('a4', 12), 1, 1, date_trunc('second', now() - interval '1 minute'), repeat('08', 32));
select is((select classification from revoked_key), 'KEY_REVOKED', 'recording rechecks and rejects a revoked key');
select is((select classification from revoked_binding), 'BINDING_REVOKED', 'recording rechecks and rejects a revoked binding');
select is((select count(*) from private.fallback_authenticated_envelopes where key_id in (1003) or journey_handle = decode(repeat('a4', 12), 'hex')), 0::bigint, 'revoked material creates no authenticated envelope');

create temporary table malformed as
select * from public.record_fallback_inbound_result_backend(
    'verified-test-adapter', 'malformed', null, now(), 5, decode(repeat('09', 32), 'hex'), 'MALFORMED'
);
select is((select classification from malformed), 'MALFORMED', 'malformed transport receipt is retained without key lookup');
select is((select key_id from private.fallback_inbound_receipts where provider_event_id = 'malformed'), null::bigint, 'malformed receipt contains no invented cryptographic identity');

create temporary table timely_watchdog as
select * from pg_temp.record_jc1('watchdog-timely', 1001, repeat('a5', 12), 1, 1, date_trunc('second', now() - interval '30 seconds'), repeat('0a', 32));
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'watchdog evaluates transport-neutral evidence');
select is((select phase from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000095'), 'EVIDENCE_FRESH', 'timely authenticated fallback prevents a false silence transition');
select is((select last_cloud_contact_at from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000095'), now() - interval '10 minutes', 'fallback freshness never claims internet recovery');

update public.journey_monitoring_state
set last_authenticated_device_evidence_at = now() - interval '10 minutes',
    last_authenticated_device_evidence_transport = 'CLOUD_HEARTBEAT',
    last_authenticated_device_evidence_received_at = now() - interval '10 minutes',
    latest_authenticated_fallback_envelope_sequence = 1,
    latest_authenticated_device_telemetry_sequence = 1
where journey_id = '20000000-0000-4000-8000-000000000095';
create temporary table delayed_watchdog as
select * from pg_temp.record_jc1('watchdog-delayed', 1001, repeat('a5', 12), 2, 2, date_trunc('second', now() - interval '9 minutes'), repeat('0b', 32), 'OBSERVATION', now() - interval '8 minutes');
select is((select evidence_advanced from delayed_watchdog), false, 'delayed valid JC1 is retained but is not current evidence');
select lives_ok($$select * from public.evaluate_due_journeys()$$, 'watchdog evaluates after delayed historical SMS');
select is((select phase from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000095'), 'VERIFYING', 'delayed SMS does not falsely resolve current silence');

create temporary table verifying_recovery as
select * from pg_temp.record_jc1('watchdog-current', 1001, repeat('a5', 12), 3, 3, date_trunc('second', now() - interval '20 seconds'), repeat('0c', 32));
select is((select phase from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000095'), 'EVIDENCE_FRESH', 'timely authenticated fallback resolves evidence silence');
select is((select status from public.verification_cases where journey_id = '20000000-0000-4000-8000-000000000095' order by opened_at desc limit 1), 'RESOLVED', 'timely authenticated fallback resolves the open verification case deterministically');
select is((select last_cloud_contact_at from public.journey_monitoring_state where journey_id = '20000000-0000-4000-8000-000000000095'), now() - interval '10 minutes', 'verification recovery through SMS still does not mark cloud HTTP contact restored');

insert into public.telemetry_observations (
    journey_id, sequence, event_time, latitude, longitude, accuracy_meters,
    battery_percent, charging, connectivity_state
) values (
    '20000000-0000-4000-8000-000000000096', 2,
    date_trunc('second', now() - interval '30 seconds'), 6.54321, 3.21098,
    12.5, 67, true, 'CELLULAR'
);
create temporary table completion_stale_active as
select * from pg_temp.record_jc1('completion-stale-active', 1001, repeat('a6', 12), 1, 1, date_trunc('second', now() - interval '1 minute'), repeat('0d', 32), 'JOURNEY_COMPLETED');
select is((select reconciliation from completion_stale_active), 'COMPLETION_STALE', 'completion older than newer canonical telemetry cannot terminate an active Journey');
select is((select status from public.journeys where id = '20000000-0000-4000-8000-000000000096'), 'ACTIVE', 'delayed completion cannot rewrite a newer active state boundary');
create temporary table completion_first as
select * from pg_temp.record_jc1('completion-first', 1001, repeat('a6', 12), 2, 2, date_trunc('second', now() - interval '10 seconds'), repeat('0f', 32), 'JOURNEY_COMPLETED');
select is((select status from public.journeys where id = '20000000-0000-4000-8000-000000000096'), 'COMPLETED', 'current authenticated completion closes an active Journey');
create temporary table completion_delayed as
select * from pg_temp.record_jc1('completion-delayed', 1001, repeat('a6', 12), 3, 0, date_trunc('second', now() - interval '20 minutes'), repeat('0e', 32), 'JOURNEY_COMPLETED', now() - interval '19 minutes');
select is((select reconciliation from completion_delayed), 'COMPLETION_STALE', 'delayed completion remains history and cannot rewrite terminal state');
select is((select completed_at from public.journeys where id = '20000000-0000-4000-8000-000000000096'), date_trunc('second', now() - interval '10 seconds'), 'delayed completion does not replace the accepted terminal event time');

select throws_ok(
    $$update private.fallback_inbound_receipts set result_classification = 'MALFORMED' where provider_event_id = 'sms-first-1'$$,
    '55000', 'Fallback transport evidence is immutable', 'receipt evidence cannot be updated'
);
select throws_ok(
    $$delete from private.fallback_authenticated_envelopes where key_id = 1001$$,
    '55000', 'Fallback transport evidence is immutable', 'authenticated envelope evidence cannot be deleted'
);

-- Failure receipts are terminal by provider identity, including historical vocabulary.
create temporary table classified_failures as
select f.failure, result.*
from (values ('AUTHENTICATION_FAILED'), ('KEY_UNWRAP_FAILED'), ('ENVELOPE_AUTHENTICATION_FAILED')) f(failure)
cross join lateral public.record_fallback_inbound_result_backend(
    'verified-test-adapter', 'failure-' || f.failure, now(), now(), 102,
    decode(repeat('ab', 32), 'hex'), f.failure, 'OBSERVATION', 1001,
    decode(repeat('a1', 12), 'hex'), 500
) result;
select is((select count(*) from classified_failures), 3::bigint, 'historical and stage-specific failure classifications persist');
select ok((select bool_and(classification = failure and not evidence_advanced) from classified_failures), 'failures do not advance evidence');
select is((select count(*) from private.fallback_authenticated_envelopes where envelope_sequence = 500), 0::bigint, 'failures create no authenticated envelope');
select is((select count(*) from private.fallback_observation_reconciliation_events where receipt_id in (select receipt_id from classified_failures)), 0::bigint, 'failures do not reach reconciliation');
create temporary table repeated_failed_event as
select * from pg_temp.record_jc1('failure-AUTHENTICATION_FAILED', 1001, repeat('a1', 12), 500, 500, now() - interval '1 hour', repeat('ab', 32));
select is((select classification from repeated_failed_event), 'AUTHENTICATION_FAILED', 'old provider event remains idempotently failed after fix');
select ok((select duplicate_provider_event from repeated_failed_event), 'old failed provider event is not reprocessed');
create temporary table new_event_after_failure as
select * from pg_temp.record_jc1('new-event-after-failure', 1001, repeat('a1', 12), 500, 500, date_trunc('second', now() - interval '30 minutes'), repeat('ab', 32));
select is((select classification from new_event_after_failure), 'AUTHENTICATED_NEW', 'same previously failed envelope can authenticate under a new provider event');
create temporary table another_event_after_failure as
select * from pg_temp.record_jc1('another-event-after-failure', 1001, repeat('a1', 12), 500, 500, date_trunc('second', now() - interval '30 minutes'), repeat('ab', 32));
select is((select classification from another_event_after_failure), 'AUTHENTICATED_DUPLICATE', 'another event cannot duplicate the authenticated envelope');
select is((select count(*) from public.telemetry_observations where journey_id='20000000-0000-4000-8000-000000000091' and sequence=500), 1::bigint, 'new event after failure creates only one canonical observation');
select throws_ok(
    $$update private.fallback_inbound_receipts set result_classification='KEY_UNWRAP_FAILED' where provider_event_id='failure-AUTHENTICATION_FAILED'$$,
    '55000', 'Fallback transport evidence is immutable', 'historical failure receipt cannot be rewritten'
);

select * from finish();
rollback;

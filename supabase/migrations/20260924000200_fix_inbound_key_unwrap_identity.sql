begin;

-- Preserve ciphertext, key/binding identity, and immutable historical receipts.
-- The provisioning client identifier, not the internal installation row UUID,
-- is part of the existing version-1 at-rest encryption AAD.
drop function public.resolve_fallback_ingestion_material_backend(bigint, bytea);

create or replace function public.resolve_fallback_ingestion_material_backend(
    p_key_id bigint,
    p_journey_handle bytea
)
returns table (
    binding_id uuid,
    journey_id uuid,
    owner_id uuid,
    installation_row_id uuid,
    installation_identifier uuid,
    key_id bigint,
    journey_handle bytea,
    binding_status text,
    binding_version integer,
    key_lifecycle_status text,
    encrypted_master_key bytea,
    encryption_iv bytea,
    encryption_version integer
)
language sql
security definer
set search_path = ''
as $$
    select b.id, b.journey_id, b.owner_id, b.installation_id, i.installation_identifier, b.key_id, b.journey_handle,
           b.binding_status, b.binding_version, k.lifecycle_status,
           k.encrypted_master_key, k.encryption_iv, k.encryption_version
    from private.journey_fallback_bindings as b
    join private.fallback_installation_keys as k on k.id = b.installation_key_id
    join private.fallback_installations as i on i.id = b.installation_id
        and i.owner_id = b.owner_id
    where b.key_id = p_key_id and b.journey_handle = p_journey_handle
      and p_key_id between 0 and 4294967295
      and octet_length(p_journey_handle) = 12
    limit 1
$$;

revoke all on function public.resolve_fallback_ingestion_material_backend(bigint, bytea)
    from public, anon, authenticated;
grant execute on function public.resolve_fallback_ingestion_material_backend(bigint, bytea)
    to service_role;

alter table private.fallback_inbound_receipts
    drop constraint fallback_inbound_receipts_result_classification_check,
    add constraint fallback_inbound_receipts_result_classification_check check (
        result_classification in (
            'MALFORMED', 'KEY_OR_BINDING_NOT_FOUND', 'KEY_REVOKED', 'BINDING_REVOKED',
            'KEK_UNAVAILABLE', 'AUTHENTICATION_FAILED', 'KEY_UNWRAP_FAILED',
            'ENVELOPE_AUTHENTICATION_FAILED', 'AUTHENTICATED_NEW',
            'AUTHENTICATED_DUPLICATE', 'AUTHENTICATED_ENVELOPE_CONFLICT'
        )
    );

-- Only the accepted classification vocabulary changes in this function.
-- Existing provider-event idempotency, envelope identity, and reconciliation
-- behavior remain identical to 20260924000100.
create or replace function public.record_fallback_inbound_result_backend(
    p_provider text,
    p_provider_event_id text,
    p_provider_arrived_at timestamptz,
    p_received_at timestamptz,
    p_raw_body_length integer,
    p_jc1_digest bytea,
    p_classification text,
    p_event_type text default null,
    p_key_id bigint default null,
    p_journey_handle bytea default null,
    p_envelope_sequence bigint default null,
    p_telemetry_sequence bigint default null,
    p_observation_event_time timestamptz default null,
    p_latitude_e7 integer default null,
    p_longitude_e7 integer default null,
    p_accuracy_decimeters integer default null,
    p_battery_percent integer default null,
    p_charging boolean default null,
    p_connectivity_state text default null
)
returns table (
    receipt_id uuid,
    classification text,
    duplicate_provider_event boolean,
    duplicate_envelope boolean,
    reconciliation text,
    evidence_advanced boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_receipt private.fallback_inbound_receipts%rowtype;
    v_binding private.journey_fallback_bindings%rowtype;
    v_key private.fallback_installation_keys%rowtype;
    v_journey public.journeys%rowtype;
    v_envelope private.fallback_authenticated_envelopes%rowtype;
    v_existing_envelope private.fallback_authenticated_envelopes%rowtype;
    v_observation public.telemetry_observations%rowtype;
    v_actual_classification text := p_classification;
    v_reconciliation text;
    v_duplicate_envelope boolean := false;
    v_evidence_advanced boolean := false;
    v_max_telemetry bigint;
    v_latest_observation_event_time timestamptz;
    v_previous_phase text;
begin
    if p_provider !~ '^[A-Za-z0-9_.-]{1,64}$'
       or length(p_provider_event_id) not between 1 and 200
       or octet_length(p_provider_event_id) > 200
       or p_provider_event_id ~ '[[:cntrl:]]'
       or p_raw_body_length not between 1 and 160
       or octet_length(p_jc1_digest) <> 32
       or p_received_at is null
       or (p_provider_arrived_at is not null and p_provider_arrived_at > p_received_at + interval '5 minutes')
       or p_classification not in (
           'MALFORMED', 'KEY_OR_BINDING_NOT_FOUND', 'KEY_REVOKED', 'BINDING_REVOKED',
           'KEK_UNAVAILABLE', 'AUTHENTICATION_FAILED', 'KEY_UNWRAP_FAILED',
           'ENVELOPE_AUTHENTICATION_FAILED', 'AUTHENTICATED'
       ) then
        raise exception 'Invalid fallback inbound result' using errcode = '22023';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('fallback-provider-event:' || p_provider || ':' || p_provider_event_id, 0)
    );
    select r.* into v_receipt from private.fallback_inbound_receipts as r
    where r.provider = p_provider and r.provider_event_id = p_provider_event_id;
    if found then
        return query select v_receipt.id, v_receipt.result_classification, true,
            v_receipt.result_classification in ('AUTHENTICATED_DUPLICATE', 'AUTHENTICATED_ENVELOPE_CONFLICT'),
            (select re.outcome from private.fallback_observation_reconciliation_events as re
             where re.receipt_id = v_receipt.id order by re.id desc limit 1), false;
        return;
    end if;

    if p_classification <> 'AUTHENTICATED' then
        insert into private.fallback_inbound_receipts (
            provider, provider_event_id, provider_arrived_at, received_at, raw_body_length,
            jc1_digest, result_classification, key_id, journey_handle, envelope_sequence
        ) values (
            p_provider, p_provider_event_id, p_provider_arrived_at, p_received_at, p_raw_body_length,
            p_jc1_digest, p_classification, p_key_id, p_journey_handle, p_envelope_sequence
        ) returning * into v_receipt;
        return query select v_receipt.id, v_receipt.result_classification, false, false, null::text, false;
        return;
    end if;

    if p_event_type not in ('OBSERVATION', 'JOURNEY_COMPLETED')
       or p_key_id not between 0 and 4294967295
       or octet_length(p_journey_handle) <> 12
       or p_envelope_sequence not between 1 and 4294967295
       or p_telemetry_sequence < 0
       or p_observation_event_time is null
       or p_observation_event_time <> date_trunc('second', p_observation_event_time)
       or p_latitude_e7 not between -900000000 and 900000000
       or p_longitude_e7 not between -1800000000 and 1800000000
       or p_accuracy_decimeters not between 0 and 65535
       or (p_battery_percent is not null and p_battery_percent not between 0 and 100)
       or p_connectivity_state not in ('NONE', 'CELLULAR', 'WIFI', 'OTHER', 'UNKNOWN') then
        raise exception 'Invalid authenticated fallback body' using errcode = '22023';
    end if;

    select b.* into v_binding from private.journey_fallback_bindings as b
    where b.key_id = p_key_id and b.journey_handle = p_journey_handle for update;
    if not found then
        v_actual_classification := 'KEY_OR_BINDING_NOT_FOUND';
    else
        select k.* into v_key from private.fallback_installation_keys as k
        where k.id = v_binding.installation_key_id for share;
        if v_binding.binding_status <> 'ACTIVE' then
            v_actual_classification := 'BINDING_REVOKED';
        elsif v_key.lifecycle_status = 'REVOKED' then
            v_actual_classification := 'KEY_REVOKED';
        end if;
    end if;

    if v_actual_classification <> 'AUTHENTICATED' then
        insert into private.fallback_inbound_receipts (
            provider, provider_event_id, provider_arrived_at, received_at, raw_body_length,
            jc1_digest, result_classification, key_id, journey_handle, envelope_sequence
        ) values (
            p_provider, p_provider_event_id, p_provider_arrived_at, p_received_at, p_raw_body_length,
            p_jc1_digest, v_actual_classification, p_key_id, p_journey_handle, p_envelope_sequence
        ) returning * into v_receipt;
        return query select v_receipt.id, v_receipt.result_classification, false, false, null::text, false;
        return;
    end if;

    select e.* into v_existing_envelope from private.fallback_authenticated_envelopes as e
    where e.key_id = p_key_id and e.journey_handle = p_journey_handle
      and e.envelope_sequence = p_envelope_sequence;
    if found then
        v_duplicate_envelope := true;
        v_envelope := v_existing_envelope;
        if v_existing_envelope.jc1_digest = p_jc1_digest then
            v_actual_classification := 'AUTHENTICATED_DUPLICATE';
            v_reconciliation := 'DUPLICATE_ENVELOPE';
        else
            v_actual_classification := 'AUTHENTICATED_ENVELOPE_CONFLICT';
            v_reconciliation := 'ENVELOPE_SEQUENCE_CONFLICT';
        end if;
    else
        insert into private.fallback_authenticated_envelopes (
            binding_id, journey_id, owner_id, key_id, journey_handle, envelope_sequence,
            event_type, telemetry_sequence, observation_event_time, latitude_e7, longitude_e7,
            accuracy_decimeters, battery_percent, charging, connectivity_state, jc1_digest, authenticated_at
        ) values (
            v_binding.id, v_binding.journey_id, v_binding.owner_id, p_key_id, p_journey_handle,
            p_envelope_sequence, p_event_type, p_telemetry_sequence, p_observation_event_time,
            p_latitude_e7, p_longitude_e7, p_accuracy_decimeters, p_battery_percent,
            p_charging, p_connectivity_state, p_jc1_digest, p_received_at
        ) returning * into v_envelope;
        v_actual_classification := 'AUTHENTICATED_NEW';
    end if;

    insert into private.fallback_inbound_receipts (
        provider, provider_event_id, provider_arrived_at, received_at, raw_body_length,
        jc1_digest, result_classification, key_id, journey_handle, envelope_sequence,
        authenticated_envelope_id, binding_id, journey_id, telemetry_sequence, observation_event_time
    ) values (
        p_provider, p_provider_event_id, p_provider_arrived_at, p_received_at, p_raw_body_length,
        p_jc1_digest, v_actual_classification, p_key_id, p_journey_handle, p_envelope_sequence,
        v_envelope.id, v_binding.id, v_binding.journey_id, p_telemetry_sequence, p_observation_event_time
    ) returning * into v_receipt;

    if v_duplicate_envelope then
        insert into private.fallback_observation_reconciliation_events (
            envelope_id, receipt_id, journey_id, telemetry_sequence, outcome
        ) values (v_envelope.id, v_receipt.id, v_binding.journey_id, p_telemetry_sequence, v_reconciliation);
        return query select v_receipt.id, v_actual_classification, false, true, v_reconciliation, false;
        return;
    end if;

    select j.* into v_journey from public.journeys as j where j.id = v_binding.journey_id for update;
    if p_event_type = 'JOURNEY_COMPLETED' then
        select coalesce(max(o.sequence), 0), max(o.event_time)
        into v_max_telemetry, v_latest_observation_event_time
        from public.telemetry_observations as o where o.journey_id = v_journey.id;
        if v_journey.status = 'ACTIVE'
           and p_observation_event_time >= v_journey.started_at
           and p_observation_event_time <= p_received_at + interval '1 minute'
           and p_telemetry_sequence >= v_max_telemetry
           and p_observation_event_time >= coalesce(v_latest_observation_event_time, v_journey.started_at)
           and p_envelope_sequence >= (
                select coalesce(max(e.envelope_sequence), p_envelope_sequence)
                from private.fallback_authenticated_envelopes as e
                where e.binding_id = v_binding.id
           ) then
            update public.journeys
            set status = 'COMPLETED', completed_at = p_observation_event_time
            where id = v_journey.id and status = 'ACTIVE';
            v_reconciliation := 'COMPLETION_APPLIED';
        else
            v_reconciliation := 'COMPLETION_STALE';
        end if;
    else
        select o.* into v_observation from public.telemetry_observations as o
        where o.journey_id = v_journey.id and o.sequence = p_telemetry_sequence;
        if not found then
            insert into public.telemetry_observations (
                journey_id, sequence, event_time, latitude, longitude, accuracy_meters,
                battery_percent, charging, connectivity_state, received_at
            ) values (
                v_journey.id, p_telemetry_sequence, p_observation_event_time,
                p_latitude_e7::double precision / 10000000,
                p_longitude_e7::double precision / 10000000,
                p_accuracy_decimeters::double precision / 10,
                p_battery_percent, p_charging, p_connectivity_state, p_received_at
            );
            v_reconciliation := 'SMS_CREATED_CANONICAL';
        elsif private.fallback_observation_matches(
            v_observation.event_time, v_observation.latitude, v_observation.longitude,
            v_observation.accuracy_meters, v_observation.battery_percent,
            v_observation.charging, v_observation.connectivity_state, v_envelope
        ) then
            v_reconciliation := 'SMS_MATCHED_INTERNET';
        else
            v_reconciliation := 'SMS_CONFLICT';
        end if;
    end if;

    insert into private.fallback_observation_reconciliation_events (
        envelope_id, receipt_id, journey_id, telemetry_sequence, outcome
    ) values (v_envelope.id, v_receipt.id, v_journey.id, p_telemetry_sequence, v_reconciliation);

    if p_event_type = 'OBSERVATION'
       and v_journey.status = 'ACTIVE'
       and p_observation_event_time >= v_journey.started_at
       and p_observation_event_time <= p_received_at + interval '1 minute'
       and p_observation_event_time >= p_received_at - interval '5 minutes'
       and (p_provider_arrived_at is null
            or (p_provider_arrived_at >= p_observation_event_time - interval '1 minute'
                and p_provider_arrived_at <= p_received_at + interval '5 minutes'
                and p_provider_arrived_at <= p_observation_event_time + interval '5 minutes'))
       and p_telemetry_sequence >= (
            select coalesce(max(o.sequence), 0) from public.telemetry_observations as o
            where o.journey_id = v_journey.id
       ) then
        select s.phase into v_previous_phase
        from public.journey_monitoring_state as s
        where s.journey_id = v_journey.id
        for update;
        update public.journey_monitoring_state as s
        set phase = case when s.phase = 'VERIFYING' then 'EVIDENCE_FRESH' else s.phase end,
            verifying_started_at = case when s.phase = 'VERIFYING' then null else s.verifying_started_at end,
            last_authenticated_device_evidence_at = p_observation_event_time,
            last_authenticated_device_evidence_transport = 'FALLBACK_SMS',
            last_authenticated_device_evidence_received_at = p_received_at,
            latest_authenticated_fallback_envelope_sequence = p_envelope_sequence,
            latest_authenticated_device_telemetry_sequence = p_telemetry_sequence,
            updated_at = greatest(s.updated_at, p_received_at)
        where s.journey_id = v_journey.id
          and s.phase <> 'CLOSED'
          and p_envelope_sequence > coalesce(s.latest_authenticated_fallback_envelope_sequence, 0)
          and p_telemetry_sequence > coalesce(s.latest_authenticated_device_telemetry_sequence, -1)
          and p_observation_event_time > coalesce(s.last_authenticated_device_evidence_at, '-infinity'::timestamptz);
        v_evidence_advanced := found;
        if v_evidence_advanced and v_previous_phase = 'VERIFYING' then
            insert into public.journey_monitoring_events (
                journey_id, event_type, occurred_at, previous_phase, new_phase,
                last_cloud_contact_at, reason_code, threshold_seconds
            ) values (
                v_journey.id, 'CONTACT_RESTORED', p_received_at, 'VERIFYING', 'EVIDENCE_FRESH',
                (select s.last_cloud_contact_at from public.journey_monitoring_state as s
                 where s.journey_id = v_journey.id),
                'AUTHENTICATED_FALLBACK_EVIDENCE', null
            );
            perform public.resolve_open_verification_case(
                v_journey.id, 'DEVICE_CONTACT_RESTORED', p_received_at
            );
        end if;
    end if;

    return query select v_receipt.id, v_actual_classification, false, false,
        v_reconciliation, v_evidence_advanced;
end;
$$;

commit;

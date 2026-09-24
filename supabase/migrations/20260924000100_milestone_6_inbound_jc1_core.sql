begin;

alter table public.journey_monitoring_state
    add column last_authenticated_device_evidence_at timestamptz,
    add column last_authenticated_device_evidence_transport text,
    add column last_authenticated_device_evidence_received_at timestamptz,
    add column latest_authenticated_fallback_envelope_sequence bigint
        check (latest_authenticated_fallback_envelope_sequence between 1 and 4294967295),
    add column latest_authenticated_device_telemetry_sequence bigint
        check (latest_authenticated_device_telemetry_sequence >= 0),
    add constraint journey_monitoring_evidence_transport_valid check (
        last_authenticated_device_evidence_transport is null
        or last_authenticated_device_evidence_transport in ('CLOUD_HEARTBEAT', 'FALLBACK_SMS')
    ),
    add constraint journey_monitoring_evidence_shape check (
        (last_authenticated_device_evidence_at is null
            and last_authenticated_device_evidence_transport is null
            and last_authenticated_device_evidence_received_at is null)
        or (last_authenticated_device_evidence_at is not null
            and last_authenticated_device_evidence_transport is not null
            and last_authenticated_device_evidence_received_at is not null)
    );

update public.journey_monitoring_state
set last_authenticated_device_evidence_at = last_cloud_contact_at,
    last_authenticated_device_evidence_transport = 'CLOUD_HEARTBEAT',
    last_authenticated_device_evidence_received_at = last_cloud_contact_at
where last_cloud_contact_at is not null;

create or replace function private.sync_cloud_contact_to_authenticated_evidence()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if new.last_cloud_contact_at is not null
       and (new.last_authenticated_device_evidence_at is null
            or new.last_cloud_contact_at >= new.last_authenticated_device_evidence_at) then
        new.last_authenticated_device_evidence_at := new.last_cloud_contact_at;
        new.last_authenticated_device_evidence_transport := 'CLOUD_HEARTBEAT';
        new.last_authenticated_device_evidence_received_at := new.last_cloud_contact_at;
    end if;
    return new;
end;
$$;

create trigger journey_monitoring_sync_cloud_evidence
before insert or update of last_cloud_contact_at on public.journey_monitoring_state
for each row execute function private.sync_cloud_contact_to_authenticated_evidence();

create table private.fallback_authenticated_envelopes (
    id uuid primary key default gen_random_uuid(),
    binding_id uuid not null references private.journey_fallback_bindings(id) on delete restrict,
    journey_id uuid not null references public.journeys(id) on delete restrict,
    owner_id uuid not null references auth.users(id) on delete restrict,
    key_id bigint not null check (key_id between 0 and 4294967295),
    journey_handle bytea not null check (octet_length(journey_handle) = 12),
    envelope_sequence bigint not null check (envelope_sequence between 1 and 4294967295),
    event_type text not null check (event_type in ('OBSERVATION', 'JOURNEY_COMPLETED')),
    telemetry_sequence bigint not null check (telemetry_sequence >= 0),
    observation_event_time timestamptz not null,
    latitude_e7 integer not null check (latitude_e7 between -900000000 and 900000000),
    longitude_e7 integer not null check (longitude_e7 between -1800000000 and 1800000000),
    accuracy_decimeters integer not null check (accuracy_decimeters between 0 and 65535),
    battery_percent integer check (battery_percent between 0 and 100),
    charging boolean,
    connectivity_state text not null check (
        connectivity_state in ('NONE', 'CELLULAR', 'WIFI', 'OTHER', 'UNKNOWN')
    ),
    jc1_digest bytea not null check (octet_length(jc1_digest) = 32),
    authenticated_at timestamptz not null,
    unique (key_id, journey_handle, envelope_sequence),
    unique (id, journey_id, telemetry_sequence)
);

create table private.fallback_inbound_receipts (
    id uuid primary key default gen_random_uuid(),
    provider text not null check (provider ~ '^[A-Za-z0-9_.-]{1,64}$'),
    provider_event_id text not null check (length(provider_event_id) between 1 and 200),
    provider_arrived_at timestamptz,
    received_at timestamptz not null,
    raw_body_length integer not null check (raw_body_length between 1 and 160),
    jc1_digest bytea not null check (octet_length(jc1_digest) = 32),
    result_classification text not null check (result_classification in (
        'MALFORMED', 'KEY_OR_BINDING_NOT_FOUND', 'KEY_REVOKED', 'BINDING_REVOKED',
        'KEK_UNAVAILABLE', 'AUTHENTICATION_FAILED', 'AUTHENTICATED_NEW',
        'AUTHENTICATED_DUPLICATE', 'AUTHENTICATED_ENVELOPE_CONFLICT'
    )),
    key_id bigint check (key_id between 0 and 4294967295),
    journey_handle bytea check (journey_handle is null or octet_length(journey_handle) = 12),
    envelope_sequence bigint check (envelope_sequence between 1 and 4294967295),
    authenticated_envelope_id uuid references private.fallback_authenticated_envelopes(id) on delete restrict,
    binding_id uuid references private.journey_fallback_bindings(id) on delete restrict,
    journey_id uuid references public.journeys(id) on delete restrict,
    telemetry_sequence bigint check (telemetry_sequence >= 0),
    observation_event_time timestamptz,
    created_at timestamptz not null default now(),
    unique (provider, provider_event_id),
    constraint fallback_receipt_header_shape check (
        (key_id is null and journey_handle is null and envelope_sequence is null)
        or (key_id is not null and journey_handle is not null and envelope_sequence is not null)
    ),
    constraint fallback_receipt_authenticated_shape check (
        (authenticated_envelope_id is null and result_classification not in (
            'AUTHENTICATED_NEW', 'AUTHENTICATED_DUPLICATE', 'AUTHENTICATED_ENVELOPE_CONFLICT'
        ))
        or (authenticated_envelope_id is not null and binding_id is not null and journey_id is not null
            and telemetry_sequence is not null and observation_event_time is not null
            and result_classification in (
                'AUTHENTICATED_NEW', 'AUTHENTICATED_DUPLICATE', 'AUTHENTICATED_ENVELOPE_CONFLICT'
            ))
    )
);

create table private.fallback_observation_reconciliation_events (
    id bigint generated always as identity primary key,
    envelope_id uuid not null references private.fallback_authenticated_envelopes(id) on delete restrict,
    receipt_id uuid references private.fallback_inbound_receipts(id) on delete restrict,
    journey_id uuid not null references public.journeys(id) on delete restrict,
    telemetry_sequence bigint not null check (telemetry_sequence >= 0),
    outcome text not null check (outcome in (
        'SMS_CREATED_CANONICAL', 'SMS_MATCHED_INTERNET', 'SMS_CONFLICT',
        'DUPLICATE_ENVELOPE', 'ENVELOPE_SEQUENCE_CONFLICT',
        'INTERNET_MATCHED_SMS', 'INTERNET_CONFLICT_SMS',
        'COMPLETION_APPLIED', 'COMPLETION_STALE'
    )),
    conflicting_observation jsonb,
    occurred_at timestamptz not null default now()
);

create unique index fallback_internet_reconciliation_once
on private.fallback_observation_reconciliation_events (envelope_id, outcome)
where outcome in ('INTERNET_MATCHED_SMS', 'INTERNET_CONFLICT_SMS');

revoke all on all tables in schema private from public, anon, authenticated;
revoke all on all sequences in schema private from public, anon, authenticated;

create or replace function private.reject_immutable_fallback_evidence_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    raise exception 'Fallback transport evidence is immutable' using errcode = '55000';
end;
$$;

create trigger fallback_envelopes_immutable
before update or delete on private.fallback_authenticated_envelopes
for each row execute function private.reject_immutable_fallback_evidence_mutation();
create trigger fallback_receipts_immutable
before update or delete on private.fallback_inbound_receipts
for each row execute function private.reject_immutable_fallback_evidence_mutation();
create trigger fallback_reconciliation_events_immutable
before update or delete on private.fallback_observation_reconciliation_events
for each row execute function private.reject_immutable_fallback_evidence_mutation();

create or replace function public.resolve_fallback_ingestion_material_backend(
    p_key_id bigint,
    p_journey_handle bytea
)
returns table (
    binding_id uuid,
    journey_id uuid,
    owner_id uuid,
    installation_id uuid,
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
    select b.id, b.journey_id, b.owner_id, b.installation_id, b.key_id, b.journey_handle,
           b.binding_status, b.binding_version, k.lifecycle_status,
           k.encrypted_master_key, k.encryption_iv, k.encryption_version
    from private.journey_fallback_bindings as b
    join private.fallback_installation_keys as k on k.id = b.installation_key_id
    where b.key_id = p_key_id and b.journey_handle = p_journey_handle
      and p_key_id between 0 and 4294967295
      and octet_length(p_journey_handle) = 12
    limit 1
$$;

create or replace function public.get_fallback_inbound_receipt_backend(
    p_provider text,
    p_provider_event_id text
)
returns table (
    receipt_id uuid,
    classification text,
    duplicate_provider_event boolean,
    duplicate_envelope boolean,
    reconciliation text,
    evidence_advanced boolean
)
language sql
security definer
set search_path = ''
as $$
    select r.id, r.result_classification, true,
           r.result_classification in ('AUTHENTICATED_DUPLICATE', 'AUTHENTICATED_ENVELOPE_CONFLICT'),
           e.outcome, false
    from private.fallback_inbound_receipts as r
    left join lateral (
        select re.outcome
        from private.fallback_observation_reconciliation_events as re
        where re.receipt_id = r.id
        order by re.id desc limit 1
    ) as e on true
    where r.provider = p_provider and r.provider_event_id = p_provider_event_id
$$;

create or replace function private.fallback_observation_matches(
    p_event_time timestamptz,
    p_latitude double precision,
    p_longitude double precision,
    p_accuracy double precision,
    p_battery integer,
    p_charging boolean,
    p_connectivity text,
    p_envelope private.fallback_authenticated_envelopes
)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select date_trunc('second', p_event_time) = p_envelope.observation_event_time
       and round(p_latitude * 10000000)::integer = p_envelope.latitude_e7
       and round(p_longitude * 10000000)::integer = p_envelope.longitude_e7
       and round(p_accuracy * 10)::integer = p_envelope.accuracy_decimeters
       and p_battery is not distinct from p_envelope.battery_percent
       and p_charging is not distinct from p_envelope.charging
       and p_connectivity = p_envelope.connectivity_state
$$;

create or replace function private.reconcile_internet_telemetry_with_fallback()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_envelope private.fallback_authenticated_envelopes%rowtype;
    v_matches boolean;
begin
    if (select auth.uid()) is null then return new; end if;
    select e.* into v_envelope
    from private.fallback_authenticated_envelopes as e
    where e.journey_id = old.journey_id
      and e.telemetry_sequence = old.sequence
      and e.event_type = 'OBSERVATION'
    order by e.authenticated_at, e.id
    limit 1;
    if not found then return new; end if;

    v_matches := new.journey_id = old.journey_id
        and new.sequence = old.sequence
        and private.fallback_observation_matches(
            new.event_time, new.latitude, new.longitude, new.accuracy_meters,
            new.battery_percent, new.charging, new.connectivity_state, v_envelope
        );
    if v_matches then
        insert into private.fallback_observation_reconciliation_events (
            envelope_id, journey_id, telemetry_sequence, outcome
        ) values (v_envelope.id, old.journey_id, old.sequence, 'INTERNET_MATCHED_SMS')
        on conflict (envelope_id, outcome)
        where outcome in ('INTERNET_MATCHED_SMS', 'INTERNET_CONFLICT_SMS') do nothing;
        return new;
    end if;

    insert into private.fallback_observation_reconciliation_events (
        envelope_id, journey_id, telemetry_sequence, outcome, conflicting_observation
    ) values (
        v_envelope.id, old.journey_id, old.sequence, 'INTERNET_CONFLICT_SMS',
        jsonb_build_object(
            'event_time', new.event_time,
            'latitude', new.latitude,
            'longitude', new.longitude,
            'accuracy_meters', new.accuracy_meters,
            'battery_percent', new.battery_percent,
            'charging', new.charging,
            'connectivity_state', new.connectivity_state
        )
    ) on conflict (envelope_id, outcome)
      where outcome in ('INTERNET_MATCHED_SMS', 'INTERNET_CONFLICT_SMS') do nothing;
    return old;
end;
$$;

create trigger telemetry_reconcile_authenticated_fallback
before update on public.telemetry_observations
for each row execute function private.reconcile_internet_telemetry_with_fallback();

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
           'KEK_UNAVAILABLE', 'AUTHENTICATION_FAILED', 'AUTHENTICATED'
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

create or replace function public.evaluate_due_journeys()
returns table (evaluated_at timestamptz, verifying_started integer, monitoring_closed integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_now timestamptz := now(); v_threshold_seconds constant integer := 300;
    v_verifying_started integer := 0; v_monitoring_closed integer := 0;
    v_due record; v_event_id bigint;
begin
    for v_due in
        select s.journey_id, s.owner_id, s.phase as previous_phase, s.last_cloud_contact_at,
               greatest(s.last_cloud_contact_at, s.last_authenticated_device_evidence_at) as last_evidence_at
        from public.journey_monitoring_state as s
        join public.journeys as j on j.id = s.journey_id
        where j.status = 'ACTIVE' and s.phase = 'EVIDENCE_FRESH'
          and greatest(s.last_cloud_contact_at, s.last_authenticated_device_evidence_at)
              <= v_now - make_interval(secs => v_threshold_seconds)
        for update of s
    loop
        update public.journey_monitoring_state
        set phase = 'VERIFYING', verifying_started_at = v_now, updated_at = v_now
        where journey_id = v_due.journey_id and phase = 'EVIDENCE_FRESH';
        if found then
            insert into public.journey_monitoring_events (
                journey_id, event_type, occurred_at, previous_phase, new_phase,
                last_cloud_contact_at, reason_code, threshold_seconds
            ) values (v_due.journey_id, 'VERIFYING_STARTED', v_now, v_due.previous_phase, 'VERIFYING',
                      v_due.last_cloud_contact_at, 'AUTHENTICATED_DEVICE_EVIDENCE_TIMEOUT', v_threshold_seconds)
            returning id into v_event_id;
            perform public.open_verification_case(
                v_due.journey_id, v_due.owner_id, v_event_id, v_now, v_due.last_cloud_contact_at
            );
            v_verifying_started := v_verifying_started + 1;
        end if;
    end loop;

    with due as (
        select s.journey_id, s.phase as previous_phase, s.last_cloud_contact_at
        from public.journey_monitoring_state as s join public.journeys as j on j.id = s.journey_id
        where j.status = 'COMPLETED' and s.phase <> 'CLOSED' for update of s
    ), changed as (
        update public.journey_monitoring_state as s
        set phase = 'CLOSED', verifying_started_at = null, closed_at = v_now, updated_at = v_now
        from due where s.journey_id = due.journey_id and s.phase <> 'CLOSED'
        returning s.journey_id, due.previous_phase, due.last_cloud_contact_at
    )
    insert into public.journey_monitoring_events (
        journey_id, event_type, occurred_at, previous_phase, new_phase,
        last_cloud_contact_at, reason_code, threshold_seconds
    ) select journey_id, 'MONITORING_CLOSED', v_now, previous_phase, 'CLOSED',
             last_cloud_contact_at, 'JOURNEY_COMPLETED', null from changed;
    get diagnostics v_monitoring_closed = row_count;
    return query select v_now, v_verifying_started, v_monitoring_closed;
end;
$$;

revoke all on function private.sync_cloud_contact_to_authenticated_evidence() from public, anon, authenticated, service_role;
revoke all on function private.reject_immutable_fallback_evidence_mutation() from public, anon, authenticated, service_role;
revoke all on function private.fallback_observation_matches(
    timestamptz, double precision, double precision, double precision, integer, boolean, text,
    private.fallback_authenticated_envelopes
) from public, anon, authenticated, service_role;
revoke all on function private.reconcile_internet_telemetry_with_fallback() from public, anon, authenticated, service_role;
revoke all on function public.resolve_fallback_ingestion_material_backend(bigint, bytea)
from public, anon, authenticated;
revoke all on function public.get_fallback_inbound_receipt_backend(text, text)
from public, anon, authenticated;
revoke all on function public.record_fallback_inbound_result_backend(
    text, text, timestamptz, timestamptz, integer, bytea, text, text, bigint, bytea,
    bigint, bigint, timestamptz, integer, integer, integer, integer, boolean, text
) from public, anon, authenticated;

grant execute on function public.resolve_fallback_ingestion_material_backend(bigint, bytea) to service_role;
grant execute on function public.get_fallback_inbound_receipt_backend(text, text) to service_role;
grant execute on function public.record_fallback_inbound_result_backend(
    text, text, timestamptz, timestamptz, integer, bytea, text, text, bigint, bytea,
    bigint, bigint, timestamptz, integer, integer, integer, integer, boolean, text
) to service_role;

commit;

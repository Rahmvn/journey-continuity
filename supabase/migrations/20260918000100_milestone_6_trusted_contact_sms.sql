begin;

create table public.trusted_contact_sms_preferences (
    relationship_id uuid primary key references public.trusted_contact_relationships(id) on delete restrict,
    contact_user_id uuid not null references auth.users(id) on delete restrict,
    phone_e164 text check (phone_e164 is null or phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
    sms_enabled boolean not null default false,
    consented_at timestamptz,
    disabled_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint trusted_contact_sms_preference_consent_consistent check (
        (sms_enabled and phone_e164 is not null and consented_at is not null and disabled_at is null)
        or (not sms_enabled and disabled_at is not null)
    )
);

create table public.trusted_contact_notification_outbox (
    id uuid primary key default gen_random_uuid(),
    journey_id uuid not null references public.journeys(id) on delete restrict,
    verification_case_id uuid not null references public.verification_cases(id) on delete restrict,
    relationship_id uuid not null references public.trusted_contact_relationships(id) on delete restrict,
    contact_user_id uuid not null references auth.users(id) on delete restrict,
    notification_kind text not null check (
        notification_kind in ('VERIFICATION_STARTED', 'DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED')
    ),
    destination_phone_e164 text not null check (destination_phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
    state text not null default 'PENDING' check (
        state in ('PENDING', 'SENDING', 'PROVIDER_ACCEPTED', 'RETRY_PENDING', 'FAILED')
    ),
    created_at timestamptz not null default now(),
    next_attempt_at timestamptz not null default now(),
    attempt_count integer not null default 0 check (attempt_count between 0 and 5),
    lease_token uuid,
    leased_at timestamptz,
    lease_expires_at timestamptz,
    provider_message_id text check (provider_message_id is null or length(provider_message_id) between 1 and 256),
    failure_classification text check (
        failure_classification is null or failure_classification in (
            'TRANSIENT', 'PERMANENT', 'MAX_ATTEMPTS', 'SMS_DISABLED',
            'RELATIONSHIP_REVOKED', 'AUTHORIZATION_REVOKED', 'PREFERENCE_CHANGED'
        )
    ),
    last_failure_code text check (
        last_failure_code is null
        or (length(last_failure_code) between 1 and 80 and last_failure_code ~ '^[A-Z0-9_.-]+$')
    ),
    sent_at timestamptz,
    terminal_at timestamptz,
    updated_at timestamptz not null default now(),
    unique (verification_case_id, relationship_id, notification_kind),
    constraint trusted_contact_notification_lease_consistent check (
        (state = 'SENDING' and lease_token is not null and leased_at is not null and lease_expires_at is not null)
        or (state <> 'SENDING' and lease_token is null and leased_at is null and lease_expires_at is null)
    ),
    constraint trusted_contact_notification_terminal_consistent check (
        (state = 'PROVIDER_ACCEPTED' and provider_message_id is not null and sent_at is not null and terminal_at is not null)
        or (state = 'FAILED' and failure_classification is not null and terminal_at is not null)
        or (state in ('PENDING', 'SENDING', 'RETRY_PENDING') and sent_at is null and terminal_at is null)
    )
);

create index trusted_contact_notification_due_idx
on public.trusted_contact_notification_outbox (next_attempt_at, created_at)
where state in ('PENDING', 'RETRY_PENDING', 'SENDING');

alter table public.trusted_contact_sms_preferences enable row level security;
alter table public.trusted_contact_notification_outbox enable row level security;

revoke all on table public.trusted_contact_sms_preferences from public, anon, authenticated, service_role;
revoke all on table public.trusted_contact_notification_outbox from public, anon, authenticated, service_role;

create or replace function public.sms_notification_max_attempts()
returns integer
language sql
immutable
set search_path = ''
as $$ select 5 $$;

create or replace function public.sms_notification_lease_ttl()
returns interval
language sql
immutable
set search_path = ''
as $$ select interval '2 minutes' $$;

create or replace function public.sms_notification_retry_base_delay()
returns interval
language sql
immutable
set search_path = ''
as $$ select interval '30 seconds' $$;

comment on function public.sms_notification_max_attempts() is
    'Provisional Milestone 6 bounded SMS dispatch policy.';
comment on function public.sms_notification_lease_ttl() is
    'Provisional Milestone 6 abandoned-claim recovery policy.';
comment on function public.sms_notification_retry_base_delay() is
    'Provisional Milestone 6 exponential retry base delay.';

create or replace function public.list_trusted_contact_sms_preferences()
returns table (
    relationship_id uuid,
    relationship_display_name text,
    sms_enabled boolean,
    masked_phone text,
    has_phone boolean,
    consented_at timestamptz,
    disabled_at timestamptz,
    updated_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare v_contact_id uuid := (select auth.uid());
begin
    if v_contact_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    return query
    select r.id,
           r.display_name,
           coalesce(p.sms_enabled, false),
           case when p.phone_e164 is null then null
                else left(p.phone_e164, 4) || repeat('*', greatest(length(p.phone_e164) - 8, 3)) || right(p.phone_e164, 4)
           end,
           p.phone_e164 is not null,
           p.consented_at,
           p.disabled_at,
           p.updated_at
    from public.trusted_contact_relationships as r
    left join public.trusted_contact_sms_preferences as p on p.relationship_id = r.id
    where r.contact_user_id = v_contact_id and r.status = 'ACCEPTED'
    order by r.accepted_at desc;
end;
$$;

create or replace function public.set_trusted_contact_sms_preference(
    p_relationship_id uuid,
    p_phone_e164 text,
    p_sms_enabled boolean
)
returns table (
    relationship_id uuid,
    sms_enabled boolean,
    masked_phone text,
    consented_at timestamptz,
    disabled_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_contact_id uuid := (select auth.uid());
    v_now timestamptz := now();
    v_phone text;
    v_existing_phone text;
    v_enabled boolean;
    v_consented_at timestamptz;
    v_disabled_at timestamptz;
begin
    if v_contact_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_sms_enabled is null then
        raise exception 'SMS enabled state is required' using errcode = '22023';
    end if;
    if not exists (
        select 1 from public.trusted_contact_relationships as r
        where r.id = p_relationship_id
          and r.contact_user_id = v_contact_id
          and r.status = 'ACCEPTED'
          and r.revoked_at is null
    ) then
        raise exception 'Accepted trusted relationship is not available to this caller' using errcode = '42501';
    end if;

    select p.phone_e164 into v_existing_phone
    from public.trusted_contact_sms_preferences as p
    where p.relationship_id = p_relationship_id
    for update;

    if p_phone_e164 is not null and btrim(p_phone_e164) <> '' then
        v_phone := regexp_replace(btrim(p_phone_e164), '[[:space:]().-]', '', 'g');
        if v_phone !~ '^\+[1-9][0-9]{7,14}$' then
            raise exception 'Phone number must be in E.164 format' using errcode = '22023';
        end if;
    else
        v_phone := v_existing_phone;
    end if;
    if p_sms_enabled and v_phone is null then
        raise exception 'Phone number is required to enable SMS' using errcode = '22023';
    end if;

    if v_existing_phone is distinct from v_phone or not p_sms_enabled then
        update public.trusted_contact_notification_outbox as o
        set state = 'FAILED',
            failure_classification = case when not p_sms_enabled then 'SMS_DISABLED' else 'PREFERENCE_CHANGED' end,
            last_failure_code = case when not p_sms_enabled then 'SMS_DISABLED' else 'PREFERENCE_CHANGED' end,
            lease_token = null, leased_at = null, lease_expires_at = null,
            terminal_at = v_now, updated_at = v_now
        where o.relationship_id = p_relationship_id
          and o.state in ('PENDING', 'RETRY_PENDING', 'SENDING');
    end if;

    insert into public.trusted_contact_sms_preferences (
        relationship_id, contact_user_id, phone_e164, sms_enabled,
        consented_at, disabled_at, created_at, updated_at
    ) values (
        p_relationship_id, v_contact_id, v_phone, p_sms_enabled,
        case when p_sms_enabled then v_now else null end,
        case when p_sms_enabled then null else v_now end,
        v_now, v_now
    )
    on conflict on constraint trusted_contact_sms_preferences_pkey do update
    set phone_e164 = excluded.phone_e164,
        sms_enabled = excluded.sms_enabled,
        consented_at = excluded.consented_at,
        disabled_at = excluded.disabled_at,
        updated_at = excluded.updated_at
    returning trusted_contact_sms_preferences.sms_enabled,
              trusted_contact_sms_preferences.consented_at,
              trusted_contact_sms_preferences.disabled_at
    into v_enabled, v_consented_at, v_disabled_at;

    return query
    select p_relationship_id, v_enabled,
           case when v_phone is null then null
                else left(v_phone, 4) || repeat('*', greatest(length(v_phone) - 8, 3)) || right(v_phone, 4)
           end,
           v_consented_at, v_disabled_at;
end;
$$;

create or replace function public.enqueue_verification_case_sms_notifications(
    p_case_id uuid,
    p_notification_kind text,
    p_created_at timestamptz
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare v_inserted integer;
begin
    if p_notification_kind not in ('VERIFICATION_STARTED', 'DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED') then
        raise exception 'Unsupported SMS notification kind' using errcode = '22023';
    end if;

    insert into public.trusted_contact_notification_outbox (
        journey_id, verification_case_id, relationship_id, contact_user_id,
        notification_kind, destination_phone_e164, created_at, next_attempt_at, updated_at
    )
    select c.journey_id, c.id, r.id, r.contact_user_id,
           p_notification_kind, p.phone_e164, p_created_at, p_created_at, p_created_at
    from public.verification_cases as c
    join public.journey_trusted_contact_access as a
      on a.journey_id = c.journey_id and a.revoked_at is null
    join public.trusted_contact_relationships as r
      on r.id = a.relationship_id
     and r.contact_user_id = a.contact_user_id
     and r.status = 'ACCEPTED'
     and r.revoked_at is null
    join public.trusted_contact_sms_preferences as p
      on p.relationship_id = r.id
     and p.contact_user_id = r.contact_user_id
     and p.sms_enabled
     and p.phone_e164 is not null
    where c.id = p_case_id
      and (
          p_notification_kind = 'VERIFICATION_STARTED'
          or (c.status = 'RESOLVED' and c.sensitive_access_expires_at > p_created_at)
      )
    on conflict (verification_case_id, relationship_id, notification_kind) do nothing;
    get diagnostics v_inserted = row_count;
    return v_inserted;
end;
$$;

-- Milestone 5 transition extension: opening evidence and SMS enqueue remain in one transaction.
create or replace function public.open_verification_case(
    p_journey_id uuid,
    p_owner_id uuid,
    p_monitoring_event_id bigint,
    p_opened_at timestamptz,
    p_last_cloud_contact_at timestamptz
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare v_case_id uuid;
begin
    insert into public.verification_cases (
        journey_id, owner_id, opened_at, opened_from_monitoring_event_id,
        last_cloud_contact_at, latest_known_telemetry_sequence,
        last_location_event_time, latitude, longitude, accuracy_meters,
        battery_percent, charging, connectivity_state,
        journey_destination, journey_started_at, journey_expected_arrival_at
    )
    select j.id, j.owner_id, p_opened_at, p_monitoring_event_id,
           p_last_cloud_contact_at, t.sequence, t.event_time, t.latitude, t.longitude,
           t.accuracy_meters, t.battery_percent, t.charging, t.connectivity_state,
           j.destination, j.started_at, j.expected_arrival_at
    from public.journeys as j
    left join lateral (
        select o.* from public.telemetry_observations as o
        where o.journey_id = j.id
        order by o.sequence desc limit 1
    ) as t on true
    where j.id = p_journey_id and j.owner_id = p_owner_id
    returning id into v_case_id;

    insert into public.verification_case_location_points (
        case_id, position, telemetry_sequence, event_time, latitude, longitude,
        accuracy_meters, battery_percent, connectivity_state
    )
    select v_case_id,
           row_number() over (order by recent.sequence)::smallint,
           recent.sequence, recent.event_time, recent.latitude, recent.longitude,
           recent.accuracy_meters, recent.battery_percent, recent.connectivity_state
    from (
        select o.* from public.telemetry_observations as o
        where o.journey_id = p_journey_id
        order by o.sequence desc limit 5
    ) as recent;

    begin
        perform public.enqueue_verification_case_sms_notifications(
            v_case_id, 'VERIFICATION_STARTED', p_opened_at
        );
    exception when others then
        raise warning 'SMS outbox enqueue failed for verification case %', v_case_id;
    end;
    return v_case_id;
end;
$$;

-- Milestone 5 transition extension: resolution remains authoritative even if outbox enqueue fails.
create or replace function public.resolve_open_verification_case(
    p_journey_id uuid,
    p_reason text,
    p_resolved_at timestamptz
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_case_id uuid;
    v_notification_kind text;
begin
    if p_reason not in ('DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED') then
        raise exception 'Unsupported verification-case resolution reason' using errcode = '22023';
    end if;
    update public.verification_cases
    set status = 'RESOLVED', resolution_reason = p_reason, resolved_at = p_resolved_at,
        sensitive_access_expires_at = p_resolved_at + public.sensitive_case_access_ttl()
    where journey_id = p_journey_id and status = 'OPEN'
    returning id into v_case_id;
    if v_case_id is null then
        return false;
    end if;

    v_notification_kind := case p_reason
        when 'DEVICE_CONTACT_RESTORED' then 'DEVICE_CONTACT_RESTORED'
        else 'JOURNEY_COMPLETED'
    end;
    begin
        perform public.enqueue_verification_case_sms_notifications(
            v_case_id, v_notification_kind, p_resolved_at
        );
    exception when others then
        raise warning 'SMS outbox enqueue failed for resolved verification case %', v_case_id;
    end;
    return true;
end;
$$;

-- Revocation keeps evidence/history but immediately prevents unsent SMS dispatch.
create or replace function public.revoke_trusted_contact(p_subject_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_now timestamptz := now();
    v_relationship_id uuid;
begin
    if v_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    update public.trusted_contact_invitations
    set status = 'REVOKED', revoked_at = v_now
    where id = p_subject_id and traveller_user_id = v_user_id and status = 'PENDING';
    if found then return true; end if;

    update public.trusted_contact_relationships
    set status = 'REVOKED', revoked_at = v_now
    where id = p_subject_id and traveller_user_id = v_user_id and status = 'ACCEPTED'
    returning id into v_relationship_id;
    if v_relationship_id is null then return false; end if;

    update public.journey_trusted_contact_access
    set revoked_at = v_now, sensitive_access_expires_at = v_now
    where relationship_id = v_relationship_id and revoked_at is null;
    update public.trusted_contact_notification_outbox as o
    set state = 'FAILED', failure_classification = 'RELATIONSHIP_REVOKED',
        last_failure_code = 'RELATIONSHIP_REVOKED',
        lease_token = null, leased_at = null, lease_expires_at = null,
        terminal_at = v_now, updated_at = v_now
    where relationship_id = v_relationship_id
      and state in ('PENDING', 'RETRY_PENDING', 'SENDING');
    return true;
end;
$$;

create or replace function public.claim_due_sms_notifications(p_limit integer default 10)
returns table (
    notification_id uuid,
    lease_token uuid,
    notification_kind text,
    destination_phone_e164 text,
    journey_id uuid,
    verification_case_id uuid,
    attempt_count integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare v_now timestamptz := now();
begin
    if p_limit is null or p_limit not between 1 and 25 then
        raise exception 'Claim limit must be between 1 and 25' using errcode = '22023';
    end if;

    update public.trusted_contact_notification_outbox as o
    set state = 'FAILED',
        failure_classification = case
            when not exists (
                select 1 from public.trusted_contact_relationships as r
                where r.id = o.relationship_id and r.status = 'ACCEPTED' and r.revoked_at is null
            ) then 'RELATIONSHIP_REVOKED'
            when not exists (
                select 1 from public.journey_trusted_contact_access as a
                where a.journey_id = o.journey_id
                  and a.relationship_id = o.relationship_id
                  and a.revoked_at is null
            ) then 'AUTHORIZATION_REVOKED'
            when not exists (
                select 1 from public.trusted_contact_sms_preferences as p
                where p.relationship_id = o.relationship_id
                  and p.contact_user_id = o.contact_user_id
                  and p.sms_enabled
            ) then 'SMS_DISABLED'
            else 'PREFERENCE_CHANGED'
        end,
        last_failure_code = case
            when not exists (
                select 1 from public.trusted_contact_relationships as r
                where r.id = o.relationship_id and r.status = 'ACCEPTED' and r.revoked_at is null
            ) then 'RELATIONSHIP_REVOKED'
            when not exists (
                select 1 from public.journey_trusted_contact_access as a
                where a.journey_id = o.journey_id
                  and a.relationship_id = o.relationship_id
                  and a.revoked_at is null
            ) then 'AUTHORIZATION_REVOKED'
            when not exists (
                select 1 from public.trusted_contact_sms_preferences as p
                where p.relationship_id = o.relationship_id
                  and p.contact_user_id = o.contact_user_id
                  and p.sms_enabled
            ) then 'SMS_DISABLED'
            else 'PREFERENCE_CHANGED'
        end,
        lease_token = null, leased_at = null, lease_expires_at = null,
        terminal_at = v_now, updated_at = v_now
    where o.state in ('PENDING', 'RETRY_PENDING', 'SENDING')
      and not exists (
          select 1
          from public.trusted_contact_relationships as r
          join public.journey_trusted_contact_access as a
            on a.journey_id = o.journey_id
           and a.relationship_id = r.id
           and a.revoked_at is null
          join public.trusted_contact_sms_preferences as p
            on p.relationship_id = r.id
           and p.contact_user_id = o.contact_user_id
           and p.sms_enabled
           and p.phone_e164 = o.destination_phone_e164
          where r.id = o.relationship_id
            and r.status = 'ACCEPTED'
            and r.revoked_at is null
      );

    update public.trusted_contact_notification_outbox as o
    set state = 'FAILED', failure_classification = 'MAX_ATTEMPTS',
        last_failure_code = 'MAX_ATTEMPTS',
        lease_token = null, leased_at = null, lease_expires_at = null,
        terminal_at = v_now, updated_at = v_now
    where o.state in ('PENDING', 'RETRY_PENDING', 'SENDING')
      and o.attempt_count >= public.sms_notification_max_attempts()
      and (o.state <> 'SENDING' or o.lease_expires_at <= v_now);

    return query
    with candidates as (
        select o.id
        from public.trusted_contact_notification_outbox as o
        where (
            (o.state in ('PENDING', 'RETRY_PENDING') and o.next_attempt_at <= v_now)
            or (o.state = 'SENDING' and o.lease_expires_at <= v_now)
        )
          and o.attempt_count < public.sms_notification_max_attempts()
        order by o.next_attempt_at, o.created_at, o.id
        for update skip locked
        limit p_limit
    ), claimed as (
        update public.trusted_contact_notification_outbox as o
        set state = 'SENDING', attempt_count = o.attempt_count + 1,
            lease_token = gen_random_uuid(), leased_at = v_now,
            lease_expires_at = v_now + public.sms_notification_lease_ttl(),
            updated_at = v_now
        from candidates as c
        where o.id = c.id
        returning o.id, o.lease_token, o.notification_kind, o.destination_phone_e164,
                  o.journey_id, o.verification_case_id, o.attempt_count
    )
    select c.id, c.lease_token, c.notification_kind, c.destination_phone_e164,
           c.journey_id, c.verification_case_id, c.attempt_count
    from claimed as c;
end;
$$;

create or replace function public.record_sms_send_success(
    p_notification_id uuid,
    p_lease_token uuid,
    p_provider_message_id text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_now timestamptz := now();
begin
    if p_provider_message_id is null or length(btrim(p_provider_message_id)) not between 1 and 256 then
        raise exception 'Provider message id is required' using errcode = '22023';
    end if;
    update public.trusted_contact_notification_outbox
    set state = 'PROVIDER_ACCEPTED', provider_message_id = btrim(p_provider_message_id),
        sent_at = v_now, terminal_at = v_now,
        failure_classification = null, last_failure_code = null,
        lease_token = null, leased_at = null, lease_expires_at = null,
        updated_at = v_now
    where id = p_notification_id and state = 'SENDING' and lease_token = p_lease_token;
    return found;
end;
$$;

create or replace function public.record_sms_send_failure(
    p_notification_id uuid,
    p_lease_token uuid,
    p_failure_classification text,
    p_failure_code text
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_now timestamptz := now();
    v_attempt_count integer;
    v_next_state text;
begin
    if p_failure_classification not in ('TRANSIENT', 'PERMANENT') then
        raise exception 'Failure classification must be TRANSIENT or PERMANENT' using errcode = '22023';
    end if;
    if p_failure_code is null or length(p_failure_code) not between 1 and 80
       or p_failure_code !~ '^[A-Z0-9_.-]+$' then
        raise exception 'Failure code is invalid' using errcode = '22023';
    end if;
    select o.attempt_count into v_attempt_count
    from public.trusted_contact_notification_outbox as o
    where o.id = p_notification_id and o.state = 'SENDING' and o.lease_token = p_lease_token
    for update;
    if not found then return null; end if;

    v_next_state := case
        when p_failure_classification = 'TRANSIENT'
         and v_attempt_count < public.sms_notification_max_attempts() then 'RETRY_PENDING'
        else 'FAILED'
    end;
    update public.trusted_contact_notification_outbox
    set state = v_next_state,
        failure_classification = case
            when v_next_state = 'FAILED' and p_failure_classification = 'TRANSIENT' then 'MAX_ATTEMPTS'
            else p_failure_classification
        end,
        last_failure_code = p_failure_code,
        next_attempt_at = case when v_next_state = 'RETRY_PENDING'
            then v_now + public.sms_notification_retry_base_delay() * power(2, greatest(v_attempt_count - 1, 0))
            else next_attempt_at end,
        lease_token = null, leased_at = null, lease_expires_at = null,
        terminal_at = case when v_next_state = 'FAILED' then v_now else null end,
        updated_at = v_now
    where id = p_notification_id;
    return v_next_state;
end;
$$;

revoke all on function public.sms_notification_max_attempts() from public, anon, authenticated, service_role;
revoke all on function public.sms_notification_lease_ttl() from public, anon, authenticated, service_role;
revoke all on function public.sms_notification_retry_base_delay() from public, anon, authenticated, service_role;
revoke all on function public.enqueue_verification_case_sms_notifications(uuid, text, timestamptz) from public, anon, authenticated, service_role;
revoke all on function public.list_trusted_contact_sms_preferences() from public, anon, authenticated, service_role;
revoke all on function public.set_trusted_contact_sms_preference(uuid, text, boolean) from public, anon, authenticated, service_role;
revoke all on function public.claim_due_sms_notifications(integer) from public, anon, authenticated, service_role;
revoke all on function public.record_sms_send_success(uuid, uuid, text) from public, anon, authenticated, service_role;
revoke all on function public.record_sms_send_failure(uuid, uuid, text, text) from public, anon, authenticated, service_role;

grant execute on function public.list_trusted_contact_sms_preferences() to authenticated;
grant execute on function public.set_trusted_contact_sms_preference(uuid, text, boolean) to authenticated;
grant execute on function public.claim_due_sms_notifications(integer) to service_role;
grant execute on function public.record_sms_send_success(uuid, uuid, text) to service_role;
grant execute on function public.record_sms_send_failure(uuid, uuid, text, text) to service_role;

commit;

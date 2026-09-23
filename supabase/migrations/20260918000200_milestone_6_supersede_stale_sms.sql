begin;

alter table public.trusted_contact_notification_outbox
drop constraint trusted_contact_notification_outbo_failure_classification_check;

alter table public.trusted_contact_notification_outbox
add constraint trusted_contact_notification_failure_classification_check check (
    failure_classification is null or failure_classification in (
        'TRANSIENT', 'PERMANENT', 'MAX_ATTEMPTS', 'SMS_DISABLED',
        'RELATIONSHIP_REVOKED', 'AUTHORIZATION_REVOKED', 'PREFERENCE_CHANGED',
        'SUPERSEDED'
    )
);

-- Resolution remains authoritative. Unsent started alerts become terminal before
-- the factual resolution notification is enqueued; provider-accepted history is retained.
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

    update public.trusted_contact_notification_outbox
    set state = 'FAILED',
        failure_classification = 'SUPERSEDED',
        last_failure_code = 'SUPERSEDED',
        lease_token = null,
        leased_at = null,
        lease_expires_at = null,
        terminal_at = p_resolved_at,
        updated_at = p_resolved_at
    where verification_case_id = v_case_id
      and notification_kind = 'VERIFICATION_STARTED'
      and state in ('PENDING', 'RETRY_PENDING', 'SENDING');

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

-- Claim-time case validation is independent defense in depth. Even if an older
-- row escaped resolution cleanup, a started alert for a resolved case is terminalized.
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
        failure_classification = 'SUPERSEDED',
        last_failure_code = 'SUPERSEDED',
        lease_token = null,
        leased_at = null,
        lease_expires_at = null,
        terminal_at = v_now,
        updated_at = v_now
    where o.notification_kind = 'VERIFICATION_STARTED'
      and o.state in ('PENDING', 'RETRY_PENDING', 'SENDING')
      and not exists (
          select 1
          from public.verification_cases as c
          where c.id = o.verification_case_id
            and c.status = 'OPEN'
      );

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
          and (
              o.notification_kind <> 'VERIFICATION_STARTED'
              or exists (
                  select 1
                  from public.verification_cases as c
                  where c.id = o.verification_case_id
                    and c.status = 'OPEN'
              )
          )
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

revoke all on function public.claim_due_sms_notifications(integer) from public, anon, authenticated, service_role;
grant execute on function public.claim_due_sms_notifications(integer) to service_role;

commit;

begin;

-- SENDING is a revocable claim. DISPATCHING is the irreversible boundary.
-- The sender must be quiesced during rollout: an old binary cannot implement
-- final dispatch authorization. Never reactivate a legacy ambiguous lease.
alter table public.trusted_contact_notification_outbox
    drop constraint trusted_contact_notification_outbox_state_check,
    drop constraint trusted_contact_notification_lease_consistent,
    drop constraint trusted_contact_notification_terminal_consistent,
    drop constraint trusted_contact_notification_failure_classification_check,
    add column dispatch_token uuid,
    add column dispatch_started_at timestamptz;

create table public.trusted_contact_notification_attempts (
    notification_id uuid not null references public.trusted_contact_notification_outbox(id) on delete restrict,
    attempt_number integer not null check (attempt_number between 1 and 5),
    dispatch_token uuid not null unique,
    dispatched_at timestamptz not null,
    claimed_at timestamptz,
    lease_expires_at timestamptz,
    outcome text not null check (outcome in ('DISPATCHING','PROVIDER_OUTCOME_UNKNOWN','PROVIDER_ACCEPTED','TRANSIENT','PERMANENT','LEGACY')),
    provider_message_id text,
    failure_code text,
    resolved_at timestamptz,
    legacy_state text,
    legacy_failure_classification text,
    primary key (notification_id, attempt_number)
);
alter table public.trusted_contact_notification_attempts enable row level security;
revoke all on public.trusted_contact_notification_attempts from public, anon, authenticated, service_role;

-- Preserve old counts and terminal history exactly; do not invent prior attempt
-- detail. Snapshot only the last known legacy attempt. Uncertain legacy work
-- is quarantined, including timeout/server-error retries from the old sender.
insert into public.trusted_contact_notification_attempts
select id, attempt_count, coalesce(lease_token, gen_random_uuid()),
       coalesce(leased_at, sent_at, updated_at), leased_at, lease_expires_at,
       case when state='PROVIDER_ACCEPTED' then 'PROVIDER_ACCEPTED'
            when state='SENDING' or (state='RETRY_PENDING' and last_failure_code is distinct from 'THROTTLINGEXCEPTION')
                then 'PROVIDER_OUTCOME_UNKNOWN' else 'LEGACY' end,
       provider_message_id, last_failure_code, terminal_at, state, failure_classification
from public.trusted_contact_notification_outbox where attempt_count > 0;
update public.trusted_contact_notification_outbox o
set state='PROVIDER_OUTCOME_UNKNOWN', dispatch_token=a.dispatch_token,
    dispatch_started_at=a.dispatched_at, next_attempt_at='infinity',
    lease_token=null, leased_at=null, lease_expires_at=null,
    failure_classification='OUTCOME_UNKNOWN', last_failure_code='LEGACY_DISPATCH_UNCERTAIN',
    terminal_at=null
from public.trusted_contact_notification_attempts a
where a.notification_id=o.id and a.outcome='PROVIDER_OUTCOME_UNKNOWN';

alter table public.trusted_contact_notification_outbox
    add constraint trusted_contact_notification_outbox_state_check check (
        state in ('PENDING','SENDING','DISPATCHING','PROVIDER_OUTCOME_UNKNOWN','PROVIDER_ACCEPTED','RETRY_PENDING','FAILED')),
    add constraint trusted_contact_notification_failure_classification_check check (
        failure_classification is null or failure_classification in ('TRANSIENT','PERMANENT','MAX_ATTEMPTS','SMS_DISABLED',
        'RELATIONSHIP_REVOKED','AUTHORIZATION_REVOKED','PREFERENCE_CHANGED','SUPERSEDED','AUTHORIZATION_EXPIRED','OUTCOME_UNKNOWN')),
    add constraint trusted_contact_notification_lease_consistent check (
        (state in ('SENDING','DISPATCHING') and lease_token is not null and leased_at is not null and lease_expires_at is not null)
        or (state not in ('SENDING','DISPATCHING') and lease_token is null and leased_at is null and lease_expires_at is null)),
    add constraint trusted_contact_notification_dispatch_consistent check (
        state not in ('DISPATCHING','PROVIDER_OUTCOME_UNKNOWN') or (dispatch_token is not null and dispatch_started_at is not null)),
    add constraint trusted_contact_notification_terminal_consistent check (
        (state='PROVIDER_ACCEPTED' and provider_message_id is not null and sent_at is not null and terminal_at is not null)
        or (state='FAILED' and failure_classification is not null and terminal_at is not null)
        or (state in ('PENDING','SENDING','DISPATCHING','PROVIDER_OUTCOME_UNKNOWN','RETRY_PENDING') and sent_at is null and terminal_at is null));

-- The grant's sensitive_access_expires_at is not a general Journey-access TTL:
-- trusted_contact_has_journey_access uses revocation. Resolved verification
-- evidence DOES expire in get_verification_case; honor that existing TTL here.
create function public.sms_notification_ineligibility(p_id uuid)
returns text language sql stable security definer set search_path='' as $$
    select case
      when r.id is null or r.status<>'ACCEPTED' or r.revoked_at is not null or r.contact_user_id<>o.contact_user_id then 'RELATIONSHIP_REVOKED'
      when a.journey_id is null or a.revoked_at is not null or a.contact_user_id<>o.contact_user_id then 'AUTHORIZATION_REVOKED'
      when p.relationship_id is null or not p.sms_enabled or p.consented_at is null or p.contact_user_id<>o.contact_user_id then 'SMS_DISABLED'
      when p.phone_e164 is distinct from o.destination_phone_e164 then 'PREFERENCE_CHANGED'
      when c.journey_id is distinct from o.journey_id then 'SUPERSEDED'
      when o.notification_kind='VERIFICATION_STARTED' and (c.status<>'OPEN' or j.status<>'ACTIVE') then 'SUPERSEDED'
      when o.notification_kind<>'VERIFICATION_STARTED' and (c.status<>'RESOLVED' or c.resolution_reason<>o.notification_kind) then 'SUPERSEDED'
      when c.status='RESOLVED' and (c.sensitive_access_expires_at is null or c.sensitive_access_expires_at<=clock_timestamp()) then 'AUTHORIZATION_EXPIRED'
      else null end
    from public.trusted_contact_notification_outbox o
    left join public.trusted_contact_relationships r on r.id=o.relationship_id
    left join public.journey_trusted_contact_access a on a.journey_id=o.journey_id and a.relationship_id=o.relationship_id
    left join public.trusted_contact_sms_preferences p on p.relationship_id=o.relationship_id
    left join public.verification_cases c on c.id=o.verification_case_id
    left join public.journeys j on j.id=o.journey_id
    where o.id=p_id
$$;

create or replace function public.claim_due_sms_notifications(p_limit integer default 10)
returns table(notification_id uuid,lease_token uuid,notification_kind text,destination_phone_e164 text,
              journey_id uuid,verification_case_id uuid,attempt_count integer)
language plpgsql security definer set search_path='' as $$
declare v_now timestamptz:=clock_timestamp(); v_row record; v_reason text;
begin
    if p_limit is null or p_limit not between 1 and 25 then
        raise exception 'Claim limit must be between 1 and 25' using errcode='22023';
    end if;
    -- SKIP LOCKED applies to cleanup too: no worker waits on another active claim.
    for v_row in select o.* from public.trusted_contact_notification_outbox o
        where o.state in ('PENDING','RETRY_PENDING','SENDING','DISPATCHING')
        order by o.id for update skip locked
    loop
        if v_row.state='DISPATCHING' then
            if v_row.lease_expires_at<=v_now then
                update public.trusted_contact_notification_outbox set state='PROVIDER_OUTCOME_UNKNOWN',
                    next_attempt_at='infinity',lease_token=null,leased_at=null,lease_expires_at=null,
                    failure_classification='OUTCOME_UNKNOWN',last_failure_code='DISPATCH_LEASE_EXPIRED',updated_at=v_now
                where id=v_row.id;
                update public.trusted_contact_notification_attempts set outcome='PROVIDER_OUTCOME_UNKNOWN'
                where dispatch_token=v_row.dispatch_token and outcome='DISPATCHING';
            end if;
            continue;
        end if;
        v_reason:=public.sms_notification_ineligibility(v_row.id);
        if v_reason is null and v_row.attempt_count>=public.sms_notification_max_attempts() then v_reason:='MAX_ATTEMPTS'; end if;
        if v_reason is not null then
            update public.trusted_contact_notification_outbox set state='FAILED',failure_classification=v_reason,
                last_failure_code=v_reason,lease_token=null,leased_at=null,lease_expires_at=null,terminal_at=v_now,updated_at=v_now
            where id=v_row.id;
        end if;
    end loop;
    return query with candidates as (
        select o.id from public.trusted_contact_notification_outbox o
        where ((o.state in ('PENDING','RETRY_PENDING') and o.next_attempt_at<=v_now)
            or (o.state='SENDING' and o.lease_expires_at<=v_now))
          and o.attempt_count<public.sms_notification_max_attempts()
        order by o.next_attempt_at,o.created_at,o.id for update skip locked limit p_limit
    ), claimed as (
        update public.trusted_contact_notification_outbox o
        set state='SENDING',lease_token=gen_random_uuid(),leased_at=v_now,
            lease_expires_at=v_now+public.sms_notification_lease_ttl(),updated_at=v_now
        from candidates c where o.id=c.id
        returning o.id,o.lease_token,o.notification_kind,o.destination_phone_e164,o.journey_id,o.verification_case_id,o.attempt_count
    ) select * from claimed;
end;
$$;

-- Lock authorization/lifecycle parents BEFORE the outbox, matching the normal
-- consent/revocation/resolution lock order. Their updates cannot interleave
-- with this final check. The committed state transition is the linearization
-- point; a lost RPC response must NOT be retried into permission to dispatch.
create function public.authorize_sms_dispatch(p_notification_id uuid,p_lease_token uuid)
returns boolean language plpgsql security definer set search_path='' as $$
declare v_row public.trusted_contact_notification_outbox%rowtype; v_reason text; v_now timestamptz;
begin
    select * into v_row from public.trusted_contact_notification_outbox where id=p_notification_id;
    if not found then return false; end if;
    perform 1 from public.journeys where id=v_row.journey_id for share;
    perform 1 from public.trusted_contact_relationships where id=v_row.relationship_id for share;
    perform 1 from public.journey_trusted_contact_access where journey_id=v_row.journey_id and relationship_id=v_row.relationship_id for share;
    perform 1 from public.trusted_contact_sms_preferences where relationship_id=v_row.relationship_id for share;
    perform 1 from public.verification_cases where id=v_row.verification_case_id for share;
    select * into v_row from public.trusted_contact_notification_outbox where id=p_notification_id for update;
    v_now:=clock_timestamp();
    if v_row.state<>'SENDING' or v_row.lease_token is distinct from p_lease_token or v_row.lease_expires_at<=v_now then return false; end if;
    v_reason:=public.sms_notification_ineligibility(p_notification_id);
    if v_reason is null and v_row.attempt_count>=public.sms_notification_max_attempts() then v_reason:='MAX_ATTEMPTS'; end if;
    if v_reason is not null then
        update public.trusted_contact_notification_outbox set state='FAILED',failure_classification=v_reason,
            last_failure_code=v_reason,lease_token=null,leased_at=null,lease_expires_at=null,terminal_at=v_now,updated_at=v_now
        where id=p_notification_id;
        return false;
    end if;
    insert into public.trusted_contact_notification_attempts(notification_id,attempt_number,dispatch_token,dispatched_at,claimed_at,lease_expires_at,outcome)
    values(p_notification_id,v_row.attempt_count+1,p_lease_token,v_now,v_row.leased_at,v_row.lease_expires_at,'DISPATCHING');
    update public.trusted_contact_notification_outbox set state='DISPATCHING',attempt_count=attempt_count+1,
        dispatch_token=p_lease_token,dispatch_started_at=v_now,updated_at=v_now,
        failure_classification=null,last_failure_code=null
    where id=p_notification_id;
    return true;
end;
$$;

create or replace function public.record_sms_send_success(p_notification_id uuid,p_lease_token uuid,p_provider_message_id text)
returns boolean language plpgsql security definer set search_path='' as $$
declare v_row public.trusted_contact_notification_outbox%rowtype; v_now timestamptz:=clock_timestamp();
begin
    if p_provider_message_id is null or length(btrim(p_provider_message_id)) not between 1 and 256 then
        raise exception 'Provider message id is required' using errcode='22023';
    end if;
    select * into v_row from public.trusted_contact_notification_outbox where id=p_notification_id for update;
    if not found or v_row.dispatch_token is distinct from p_lease_token then return false; end if;
    if v_row.state='PROVIDER_ACCEPTED' then return v_row.provider_message_id=btrim(p_provider_message_id); end if;
    if v_row.state not in ('DISPATCHING','PROVIDER_OUTCOME_UNKNOWN') then return false; end if;
    update public.trusted_contact_notification_attempts set outcome='PROVIDER_ACCEPTED',provider_message_id=btrim(p_provider_message_id),resolved_at=v_now
    where notification_id=p_notification_id and dispatch_token=p_lease_token;
    update public.trusted_contact_notification_outbox set state='PROVIDER_ACCEPTED',provider_message_id=btrim(p_provider_message_id),
        sent_at=v_now,terminal_at=v_now,failure_classification=null,last_failure_code=null,
        lease_token=null,leased_at=null,lease_expires_at=null,updated_at=v_now
    where id=p_notification_id;
    return true;
end;
$$;

create function public.record_sms_outcome_unknown(p_notification_id uuid,p_lease_token uuid)
returns boolean language plpgsql security definer set search_path='' as $$
begin
    update public.trusted_contact_notification_outbox set state='PROVIDER_OUTCOME_UNKNOWN',next_attempt_at='infinity',
        failure_classification='OUTCOME_UNKNOWN',last_failure_code='PROVIDER_OUTCOME_UNKNOWN',
        lease_token=null,leased_at=null,lease_expires_at=null,updated_at=clock_timestamp()
    where id=p_notification_id and dispatch_token=p_lease_token and state='DISPATCHING';
    if not found then return false; end if;
    update public.trusted_contact_notification_attempts set outcome='PROVIDER_OUTCOME_UNKNOWN'
    where notification_id=p_notification_id and dispatch_token=p_lease_token and outcome='DISPATCHING';
    return true;
end;
$$;

create or replace function public.record_sms_send_failure(p_notification_id uuid,p_lease_token uuid,p_failure_classification text,p_failure_code text)
returns text language plpgsql security definer set search_path='' as $$
declare v_row public.trusted_contact_notification_outbox%rowtype; v_state text; v_now timestamptz:=clock_timestamp();
begin
    if p_failure_classification is null or p_failure_classification not in ('TRANSIENT','PERMANENT')
       or p_failure_code is null or p_failure_code !~ '^[A-Z0-9_.-]{1,80}$' then
        raise exception 'Invalid confirmed provider failure' using errcode='22023';
    end if;
    -- Only an explicit throttling rejection is retryable. Timeouts, transport
    -- errors, missing MessageId and server errors are uncertain, not failures.
    if p_failure_classification='TRANSIENT' and p_failure_code<>'THROTTLINGEXCEPTION' then
        raise exception 'Unconfirmed retryable outcome' using errcode='22023';
    end if;
    select * into v_row from public.trusted_contact_notification_outbox where id=p_notification_id for update;
    if not found or v_row.dispatch_token is distinct from p_lease_token
        or v_row.state not in ('DISPATCHING','PROVIDER_OUTCOME_UNKNOWN') then return null; end if;
    v_state:=case when p_failure_classification='TRANSIENT' and v_row.attempt_count<public.sms_notification_max_attempts()
                  then 'RETRY_PENDING' else 'FAILED' end;
    update public.trusted_contact_notification_attempts set outcome=p_failure_classification,failure_code=p_failure_code,resolved_at=v_now
    where notification_id=p_notification_id and dispatch_token=p_lease_token;
    update public.trusted_contact_notification_outbox set state=v_state,
        failure_classification=case when v_state='FAILED' and p_failure_classification='TRANSIENT' then 'MAX_ATTEMPTS' else p_failure_classification end,
        last_failure_code=p_failure_code,
        next_attempt_at=case when v_state='RETRY_PENDING' then v_now+public.sms_notification_retry_base_delay()*power(2,v_row.attempt_count-1) else next_attempt_at end,
        lease_token=null,leased_at=null,lease_expires_at=null,terminal_at=case when v_state='FAILED' then v_now else null end,updated_at=v_now
    where id=p_notification_id;
    return v_state;
end;
$$;

-- Existing resolution, consent and revocation RPCs target only PENDING,
-- RETRY_PENDING and SENDING. They deliberately cannot cancel DISPATCHING or
-- UNKNOWN, so a matching acknowledgement remains valid after resolution.
revoke all on function public.sms_notification_ineligibility(uuid) from public,anon,authenticated,service_role;
revoke all on function public.authorize_sms_dispatch(uuid,uuid) from public,anon,authenticated,service_role;
revoke all on function public.record_sms_outcome_unknown(uuid,uuid) from public,anon,authenticated,service_role;
grant execute on function public.authorize_sms_dispatch(uuid,uuid) to service_role;
grant execute on function public.record_sms_outcome_unknown(uuid,uuid) to service_role;

commit;

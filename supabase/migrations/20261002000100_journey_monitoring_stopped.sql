begin;

-- Completion remains a separate historical fact. Existing rows are not rewritten.
alter table public.journeys add column ended_at timestamptz;
alter table public.journeys drop constraint journeys_status_check;
alter table public.journeys add constraint journeys_status_check
    check (status in ('ACTIVE', 'COMPLETED', 'CANCELLED'));
alter table public.journeys drop constraint journeys_completion_consistent;
alter table public.journeys add constraint journeys_terminal_consistent check (
    (status = 'ACTIVE' and completed_at is null and ended_at is null)
    or (status = 'COMPLETED' and completed_at is not null and ended_at is null)
    or (status = 'CANCELLED' and completed_at is null and ended_at is not null)
);

-- For a stop, the persisted terminal instant also bounds the Journey update.
-- Other statuses keep the original transaction-time behavior.
create or replace function public.set_journey_updated_at()
returns trigger language plpgsql set search_path = pg_catalog as $$
begin
    new.updated_at = case when new.status = 'CANCELLED'
        then greatest(now(), new.ended_at) else now() end;
    return new;
end;
$$;

-- Ordinary owner upserts cannot reopen or replace either terminal meaning.
create function public.prevent_journey_terminal_rewrite()
returns trigger language plpgsql set search_path = '' as $$
begin
    if old.status in ('COMPLETED', 'CANCELLED') and
       (new.status is distinct from old.status or
        new.completed_at is distinct from old.completed_at or
        new.ended_at is distinct from old.ended_at or
        new.owner_id is distinct from old.owner_id or
        new.destination is distinct from old.destination or
        new.expected_arrival_at is distinct from old.expected_arrival_at or
        new.started_at is distinct from old.started_at) then
        -- A dedicated code lets sync retry this exact race without treating an
        -- unrelated CHECK violation as either connectivity loss or recoverable.
        raise exception 'Terminal Journey state cannot be rewritten' using errcode = 'JT001';
    end if;
    return new;
end;
$$;
create trigger journeys_terminal_monotonic
before update on public.journeys
for each row execute function public.prevent_journey_terminal_rewrite();
revoke all on function public.prevent_journey_terminal_rewrite() from public, anon, authenticated, service_role;

alter table public.verification_cases
    drop constraint verification_cases_resolution_reason_check;
alter table public.verification_cases
    add constraint verification_cases_resolution_reason_check
    check (resolution_reason in ('DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED', 'MONITORING_STOPPED'));

-- The terminal stop resolves an open case and supersedes unsent alerts, but
-- intentionally does not enqueue either a completion or contact-restored SMS.
create or replace function public.resolve_open_verification_case(
    p_journey_id uuid, p_reason text, p_resolved_at timestamptz
)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_case_id uuid; v_notification_kind text;
begin
    if p_reason not in ('DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED', 'MONITORING_STOPPED') then
        raise exception 'Unsupported verification-case resolution reason' using errcode = '22023';
    end if;
    update public.verification_cases
    set status = 'RESOLVED', resolution_reason = p_reason, resolved_at = p_resolved_at,
        sensitive_access_expires_at = p_resolved_at + public.sensitive_case_access_ttl()
    where journey_id = p_journey_id and status = 'OPEN'
    returning id into v_case_id;
    if v_case_id is null then return false; end if;

    update public.trusted_contact_notification_outbox
    set state = 'FAILED', failure_classification = 'SUPERSEDED',
        last_failure_code = 'SUPERSEDED', lease_token = null, leased_at = null,
        lease_expires_at = null, terminal_at = p_resolved_at, updated_at = p_resolved_at
    where verification_case_id = v_case_id and notification_kind = 'VERIFICATION_STARTED'
      and state in ('PENDING', 'RETRY_PENDING', 'SENDING');

    if p_reason = 'MONITORING_STOPPED' then return true; end if;
    v_notification_kind := case p_reason
        when 'DEVICE_CONTACT_RESTORED' then 'DEVICE_CONTACT_RESTORED'
        else 'JOURNEY_COMPLETED' end;
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

-- The existing trigger name is retained for compatibility with applied migrations.
create or replace function public.close_journey_monitoring_on_completion()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
    v_now timestamptz := now(); v_previous_phase text; v_last_contact timestamptz;
    v_reason text;
begin
    if new.status not in ('COMPLETED', 'CANCELLED') then return new; end if;
    if new.status = 'CANCELLED' then v_now := new.ended_at; end if;
    v_reason := case new.status when 'CANCELLED' then 'MONITORING_STOPPED'
        else 'JOURNEY_COMPLETED' end;
    select s.phase, s.last_cloud_contact_at into v_previous_phase, v_last_contact
    from public.journey_monitoring_state as s where s.journey_id = new.id for update;
    if not found then
        insert into public.journey_monitoring_state (
            journey_id, owner_id, phase, latest_heartbeat_sequence, closed_at, updated_at
        ) values (new.id, new.owner_id, 'CLOSED', 0, v_now, v_now);
        v_previous_phase := null;
    elsif v_previous_phase = 'CLOSED' then
        perform public.resolve_open_verification_case(new.id, v_reason, v_now);
        return new;
    else
        update public.journey_monitoring_state
        set phase = 'CLOSED', verifying_started_at = null, closed_at = v_now, updated_at = v_now
        where journey_id = new.id;
    end if;
    insert into public.journey_monitoring_events (
        journey_id, event_type, occurred_at, previous_phase, new_phase,
        last_cloud_contact_at, reason_code, threshold_seconds
    ) values (new.id, 'MONITORING_CLOSED', v_now, v_previous_phase, 'CLOSED',
              v_last_contact, v_reason, null);
    perform public.resolve_open_verification_case(new.id, v_reason, v_now);
    update public.journey_trusted_contact_access
    set sensitive_access_expires_at = v_now + public.sensitive_case_access_ttl()
    where journey_id = new.id and revoked_at is null;
    return new;
end;
$$;

-- Preserve the current authenticated-device-evidence timeout rule.
create or replace function public.evaluate_due_journeys()
returns table (evaluated_at timestamptz, verifying_started integer, monitoring_closed integer)
language plpgsql security definer set search_path = '' as $$
declare
    v_now timestamptz := now(); v_threshold_seconds constant integer := 300;
    v_verifying_started integer := 0; v_monitoring_closed integer := 0;
    v_due record; v_event_id bigint; v_reason text; v_terminal_at timestamptz;
begin
    for v_due in
        select s.journey_id, s.owner_id, s.phase as previous_phase, s.last_cloud_contact_at
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

    for v_due in
        select s.journey_id, s.phase as previous_phase, s.last_cloud_contact_at,
               j.status, j.ended_at
        from public.journey_monitoring_state as s
        join public.journeys as j on j.id = s.journey_id
        where j.status in ('COMPLETED', 'CANCELLED') and s.phase <> 'CLOSED'
        for update of s
    loop
        v_reason := case v_due.status when 'CANCELLED' then 'MONITORING_STOPPED'
            else 'JOURNEY_COMPLETED' end;
        v_terminal_at := case when v_due.status = 'CANCELLED'
            then v_due.ended_at else v_now end;
        update public.journey_monitoring_state
        set phase = 'CLOSED', verifying_started_at = null,
            closed_at = v_terminal_at, updated_at = v_terminal_at
        where journey_id = v_due.journey_id and phase <> 'CLOSED';
        if found then
            insert into public.journey_monitoring_events (
                journey_id, event_type, occurred_at, previous_phase, new_phase,
                last_cloud_contact_at, reason_code, threshold_seconds
            ) values (v_due.journey_id, 'MONITORING_CLOSED', v_terminal_at, v_due.previous_phase,
                      'CLOSED', v_due.last_cloud_contact_at, v_reason, null);
            perform public.resolve_open_verification_case(v_due.journey_id, v_reason, v_terminal_at);
            if v_due.status = 'CANCELLED' then
                update public.journey_trusted_contact_access
                set sensitive_access_expires_at = v_terminal_at + public.sensitive_case_access_ttl()
                where journey_id = v_due.journey_id and revoked_at is null;
            end if;
            v_monitoring_closed := v_monitoring_closed + 1;
        end if;
    end loop;
    return query select v_now, v_verifying_started, v_monitoring_closed;
end;
$$;

-- Account's later online-only flow must receive this owner-authenticated
-- acknowledgement before it performs the local stop or signs out.
create function public.stop_journey_monitoring_v1(p_journey_id uuid)
returns table (journey_status text, ended_at timestamptz)
language plpgsql security definer set search_path = '' as $$
declare v_owner_id uuid := (select auth.uid()); v_journey public.journeys%rowtype;
begin
    if v_owner_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into v_journey from public.journeys as j
    where j.id = p_journey_id for update;
    if not found or v_journey.owner_id <> v_owner_id then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    if v_journey.status = 'ACTIVE' then
        update public.journeys as j
        -- Millisecond precision round-trips exactly through Android's Long timestamp.
        set status = 'CANCELLED', ended_at = date_trunc('milliseconds', clock_timestamp())
        where j.id = p_journey_id and j.status = 'ACTIVE'
        returning * into v_journey;
    elsif v_journey.status <> 'CANCELLED' then
        raise exception 'Journey is already terminal with a different outcome' using errcode = '22023';
    end if;
    return query select v_journey.status, v_journey.ended_at;
end;
$$;
revoke all on function public.stop_journey_monitoring_v1(uuid) from public, anon, authenticated, service_role;
grant execute on function public.stop_journey_monitoring_v1(uuid) to authenticated;

-- Keep v1's return type stable. v2 adds the intentional-stop timestamp.
create function public.list_trusted_journey_statuses_v2()
returns table (
    journey_id uuid, traveller_full_name text, traveller_handle text, destination text,
    started_at timestamptz, expected_arrival_at timestamptz, journey_status text,
    monitoring_phase text, completed_at timestamptz, ended_at timestamptz,
    last_cloud_contact_at timestamptz, last_authenticated_device_evidence_at timestamptz,
    last_authenticated_device_evidence_received_at timestamptz,
    last_authenticated_device_evidence_transport text
)
language sql stable security definer set search_path = '' as $$
    select s.journey_id, s.traveller_full_name, s.traveller_handle, s.destination,
           s.started_at, s.expected_arrival_at, s.journey_status, s.monitoring_phase,
           s.completed_at, j.ended_at, s.last_cloud_contact_at,
           s.last_authenticated_device_evidence_at,
           s.last_authenticated_device_evidence_received_at,
           s.last_authenticated_device_evidence_transport
    from public.list_trusted_journey_statuses_v1() as s
    join public.journeys as j on j.id = s.journey_id;
$$;

create function public.get_trusted_journey_status_v2(p_journey_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_result jsonb;
begin
    select to_jsonb(s) into v_result
    from public.list_trusted_journey_statuses_v2() as s
    where s.journey_id = p_journey_id;
    if v_result is null then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    return v_result;
end;
$$;

-- The existing Viewer JSON contract can add a nullable field without changing its signature.
create or replace function public.get_trusted_journey_snapshot(p_journey_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_contact_id uuid := (select auth.uid()); v_result jsonb;
begin
    if v_contact_id is null or not public.trusted_contact_has_journey_access(v_contact_id, p_journey_id) then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    select jsonb_build_object(
        'journey_id', j.id, 'destination', j.destination, 'started_at', j.started_at,
        'expected_arrival_at', j.expected_arrival_at, 'journey_status', j.status,
        'ended_at', j.ended_at, 'monitoring_phase', coalesce(s.phase, 'CLOSED'),
        'last_cloud_contact_at', s.last_cloud_contact_at,
        'whereabouts', case when s.phase = 'VERIFYING' then 'UNKNOWN' else 'NOT_DISCLOSED' end,
        'open_case_id', case when s.phase = 'VERIFYING' and c.status = 'OPEN' then c.id else null end,
        'available_case_id', c.id
    ) into v_result
    from public.journeys as j
    left join public.journey_monitoring_state as s on s.journey_id = j.id
    left join lateral (
        select vc.id, vc.status from public.verification_cases as vc
        where vc.journey_id = j.id
          and (vc.status = 'OPEN' or vc.sensitive_access_expires_at > now())
        order by vc.opened_at desc limit 1
    ) as c on true
    where j.id = p_journey_id;
    insert into public.trusted_contact_access_events (event_type, contact_user_id, journey_id)
    values ('JOURNEY_VIEWED', v_contact_id, p_journey_id) on conflict do nothing;
    return v_result;
end;
$$;

revoke all on function public.list_trusted_journey_statuses_v2() from public, anon, authenticated, service_role;
revoke all on function public.get_trusted_journey_status_v2(uuid) from public, anon, authenticated, service_role;
grant execute on function public.list_trusted_journey_statuses_v2() to authenticated;
grant execute on function public.get_trusted_journey_status_v2(uuid) to authenticated;

commit;

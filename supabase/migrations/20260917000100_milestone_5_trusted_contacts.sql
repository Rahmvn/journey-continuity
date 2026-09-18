begin;

create extension if not exists pgcrypto with schema extensions;

create or replace function public.trusted_contact_invitation_ttl()
returns interval
language sql
immutable
set search_path = ''
as $$ select interval '7 days' $$;

create or replace function public.sensitive_case_access_ttl()
returns interval
language sql
immutable
set search_path = ''
as $$ select interval '24 hours' $$;

comment on function public.trusted_contact_invitation_ttl() is
    'Provisional Milestone 5 development policy. Centralized for later review.';
comment on function public.sensitive_case_access_ttl() is
    'Provisional Milestone 5 development policy. Centralized for later review.';

create table public.trusted_contact_invitations (
    id uuid primary key default gen_random_uuid(),
    traveller_user_id uuid not null references auth.users(id) on delete restrict,
    display_name text not null check (length(btrim(display_name)) between 1 and 100),
    invited_email_normalized text not null check (
        invited_email_normalized = lower(btrim(invited_email_normalized))
        and length(invited_email_normalized) between 3 and 320
    ),
    token_hash bytea not null unique,
    status text not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    accepted_at timestamptz,
    revoked_at timestamptz,
    constraint trusted_contact_invitation_status_consistent check (
        (status = 'PENDING' and accepted_at is null and revoked_at is null)
        or (status = 'ACCEPTED' and accepted_at is not null and revoked_at is null)
        or (status = 'REVOKED' and revoked_at is not null)
        or (status = 'EXPIRED' and accepted_at is null and revoked_at is null)
    )
);

create index trusted_contact_invitations_owner_idx
on public.trusted_contact_invitations (traveller_user_id, created_at desc);

create table public.trusted_contact_relationships (
    id uuid primary key default gen_random_uuid(),
    traveller_user_id uuid not null references auth.users(id) on delete restrict,
    contact_user_id uuid not null references auth.users(id) on delete restrict,
    display_name text not null check (length(btrim(display_name)) between 1 and 100),
    contact_email_normalized text not null check (
        contact_email_normalized = lower(btrim(contact_email_normalized))
        and length(contact_email_normalized) between 3 and 320
    ),
    status text not null default 'ACCEPTED' check (status in ('ACCEPTED', 'REVOKED')),
    accepted_at timestamptz not null default now(),
    revoked_at timestamptz,
    created_at timestamptz not null default now(),
    constraint trusted_contact_relationship_status_consistent check (
        (status = 'ACCEPTED' and revoked_at is null)
        or (status = 'REVOKED' and revoked_at is not null)
    )
);

create unique index trusted_contact_relationship_active_identity_idx
on public.trusted_contact_relationships (traveller_user_id, contact_user_id)
where status = 'ACCEPTED';

create table public.journey_trusted_contact_access (
    journey_id uuid not null references public.journeys(id) on delete restrict,
    relationship_id uuid not null references public.trusted_contact_relationships(id) on delete restrict,
    traveller_user_id uuid not null references auth.users(id) on delete restrict,
    contact_user_id uuid not null references auth.users(id) on delete restrict,
    granted_at timestamptz not null default now(),
    revoked_at timestamptz,
    sensitive_access_expires_at timestamptz,
    primary key (journey_id, relationship_id),
    unique (journey_id, contact_user_id)
);

create index journey_trusted_contact_access_contact_idx
on public.journey_trusted_contact_access (contact_user_id, journey_id)
where revoked_at is null;

create table public.verification_cases (
    id uuid primary key default gen_random_uuid(),
    journey_id uuid not null references public.journeys(id) on delete restrict,
    owner_id uuid not null references auth.users(id) on delete restrict,
    opened_at timestamptz not null default now(),
    opened_from_monitoring_event_id bigint not null references public.journey_monitoring_events(id) on delete restrict,
    status text not null default 'OPEN' check (status in ('OPEN', 'RESOLVED')),
    resolution_reason text check (resolution_reason in ('DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED')),
    resolved_at timestamptz,
    sensitive_access_expires_at timestamptz,
    last_cloud_contact_at timestamptz,
    latest_known_telemetry_sequence bigint check (latest_known_telemetry_sequence is null or latest_known_telemetry_sequence > 0),
    last_location_event_time timestamptz,
    latitude double precision check (latitude is null or latitude between -90 and 90),
    longitude double precision check (longitude is null or longitude between -180 and 180),
    accuracy_meters double precision check (accuracy_meters is null or accuracy_meters >= 0),
    battery_percent integer check (battery_percent is null or battery_percent between 0 and 100),
    charging boolean,
    connectivity_state text check (
        connectivity_state is null or connectivity_state in ('NONE', 'CELLULAR', 'WIFI', 'OTHER', 'UNKNOWN')
    ),
    journey_destination text not null,
    journey_started_at timestamptz not null,
    journey_expected_arrival_at timestamptz not null,
    constraint verification_case_resolution_consistent check (
        (status = 'OPEN' and resolution_reason is null and resolved_at is null and sensitive_access_expires_at is null)
        or (status = 'RESOLVED' and resolution_reason is not null and resolved_at is not null and sensitive_access_expires_at is not null)
    ),
    constraint verification_case_location_consistent check (
        (last_location_event_time is null and latitude is null and longitude is null and accuracy_meters is null)
        or (last_location_event_time is not null and latitude is not null and longitude is not null and accuracy_meters is not null)
    )
);

create unique index verification_cases_one_open_per_journey_idx
on public.verification_cases (journey_id)
where status = 'OPEN';

create index verification_cases_journey_opened_idx
on public.verification_cases (journey_id, opened_at desc);

create table public.verification_case_location_points (
    case_id uuid not null references public.verification_cases(id) on delete restrict,
    position smallint not null check (position between 1 and 5),
    telemetry_sequence bigint not null check (telemetry_sequence > 0),
    event_time timestamptz not null,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    accuracy_meters double precision not null check (accuracy_meters >= 0),
    battery_percent integer check (battery_percent between 0 and 100),
    connectivity_state text not null check (connectivity_state in ('NONE', 'CELLULAR', 'WIFI', 'OTHER', 'UNKNOWN')),
    primary key (case_id, position),
    unique (case_id, telemetry_sequence)
);

create table public.verification_case_reports (
    id uuid primary key default gen_random_uuid(),
    case_id uuid not null references public.verification_cases(id) on delete restrict,
    reporter_user_id uuid not null references auth.users(id) on delete restrict,
    report_type text not null check (
        report_type in ('SPOKE_WITH_TRAVELLER', 'RECEIVED_MESSAGE_FROM_TRAVELLER', 'UPDATE_FROM_OTHER_PERSON', 'OTHER')
    ),
    contact_time timestamptz,
    note text not null check (length(btrim(note)) between 1 and 1000),
    created_at timestamptz not null default now(),
    provenance text not null default 'trusted_contact_reported' check (provenance = 'trusted_contact_reported')
);

create table public.trusted_contact_access_events (
    id bigint generated always as identity primary key,
    event_type text not null check (
        event_type in ('JOURNEY_VIEWED', 'VERIFICATION_CASE_VIEWED', 'PRECISE_LOCATION_REVEALED', 'REPORT_SUBMITTED')
    ),
    contact_user_id uuid not null references auth.users(id) on delete restrict,
    journey_id uuid not null references public.journeys(id) on delete restrict,
    case_id uuid references public.verification_cases(id) on delete restrict,
    report_id uuid references public.verification_case_reports(id) on delete restrict,
    created_at timestamptz not null default now(),
    constraint trusted_contact_access_event_shape check (
        (event_type = 'JOURNEY_VIEWED' and case_id is null and report_id is null)
        or (event_type in ('VERIFICATION_CASE_VIEWED', 'PRECISE_LOCATION_REVEALED') and case_id is not null and report_id is null)
        or (event_type = 'REPORT_SUBMITTED' and case_id is not null and report_id is not null)
    )
);

create unique index trusted_contact_access_journey_view_once_idx
on public.trusted_contact_access_events (contact_user_id, journey_id, event_type)
where event_type = 'JOURNEY_VIEWED';
create unique index trusted_contact_access_case_view_once_idx
on public.trusted_contact_access_events (contact_user_id, case_id, event_type)
where event_type in ('VERIFICATION_CASE_VIEWED', 'PRECISE_LOCATION_REVEALED');

alter table public.trusted_contact_invitations enable row level security;
alter table public.trusted_contact_relationships enable row level security;
alter table public.journey_trusted_contact_access enable row level security;
alter table public.verification_cases enable row level security;
alter table public.verification_case_location_points enable row level security;
alter table public.verification_case_reports enable row level security;
alter table public.trusted_contact_access_events enable row level security;

revoke all on table public.trusted_contact_invitations from public, anon, authenticated;
revoke all on table public.trusted_contact_relationships from public, anon, authenticated;
revoke all on table public.journey_trusted_contact_access from public, anon, authenticated;
revoke all on table public.verification_cases from public, anon, authenticated;
revoke all on table public.verification_case_location_points from public, anon, authenticated;
revoke all on table public.verification_case_reports from public, anon, authenticated;
revoke all on table public.trusted_contact_access_events from public, anon, authenticated;
revoke all on sequence public.trusted_contact_access_events_id_seq from public, anon, authenticated;

create or replace function public.provision_trusted_contacts_for_journey()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.journey_trusted_contact_access (
        journey_id, relationship_id, traveller_user_id, contact_user_id
    )
    select new.id, r.id, new.owner_id, r.contact_user_id
    from public.trusted_contact_relationships as r
    where r.traveller_user_id = new.owner_id and r.status = 'ACCEPTED'
    on conflict (journey_id, relationship_id) do nothing;
    return new;
end;
$$;

create trigger journeys_provision_trusted_contacts
after insert on public.journeys
for each row execute function public.provision_trusted_contacts_for_journey();

create or replace function public.create_trusted_contact_invitation(
    p_display_name text,
    p_email text
)
returns table (
    invitation_id uuid,
    display_name text,
    invited_email_normalized text,
    status text,
    expires_at timestamptz,
    invitation_token text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_display_name text := btrim(p_display_name);
    v_email text := lower(btrim(p_email));
    v_token text := encode(extensions.gen_random_bytes(32), 'hex');
    v_id uuid;
    v_expires_at timestamptz := now() + public.trusted_contact_invitation_ttl();
begin
    if v_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if length(v_display_name) not between 1 and 100 then
        raise exception 'Display name must be between 1 and 100 characters' using errcode = '22023';
    end if;
    if length(v_email) not between 3 and 320 or position('@' in v_email) <= 1 then
        raise exception 'A valid invited email is required' using errcode = '22023';
    end if;
    if exists (
        select 1 from public.trusted_contact_relationships as r
        where r.traveller_user_id = v_user_id
          and r.contact_email_normalized = v_email
          and r.status = 'ACCEPTED'
    ) then
        raise exception 'This contact already has an accepted relationship' using errcode = '23505';
    end if;

    update public.trusted_contact_invitations as i
    set status = 'EXPIRED'
    where i.traveller_user_id = v_user_id and i.status = 'PENDING' and i.expires_at <= now();

    insert into public.trusted_contact_invitations (
        traveller_user_id, display_name, invited_email_normalized, token_hash, expires_at
    ) values (
        v_user_id, v_display_name, v_email,
        extensions.digest(convert_to(v_token, 'UTF8'), 'sha256'), v_expires_at
    ) returning id into v_id;

    return query select v_id, v_display_name, v_email, 'PENDING'::text, v_expires_at, v_token;
end;
$$;

create or replace function public.list_trusted_contact_management()
returns table (
    subject_id uuid,
    subject_kind text,
    display_name text,
    contact_email_normalized text,
    status text,
    expires_at timestamptz,
    accepted_at timestamptz,
    created_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
begin
    if v_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    update public.trusted_contact_invitations as i
    set status = 'EXPIRED'
    where i.traveller_user_id = v_user_id and i.status = 'PENDING' and i.expires_at <= now();
    return query
    select i.id, 'INVITATION'::text, i.display_name, i.invited_email_normalized,
           i.status, i.expires_at, i.accepted_at, i.created_at
    from public.trusted_contact_invitations as i
    where i.traveller_user_id = v_user_id and i.status <> 'ACCEPTED'
    union all
    select r.id, 'RELATIONSHIP'::text, r.display_name, r.contact_email_normalized,
           r.status, null::timestamptz, r.accepted_at, r.created_at
    from public.trusted_contact_relationships as r
    where r.traveller_user_id = v_user_id
    order by created_at desc;
end;
$$;

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
    return true;
end;
$$;

create or replace function public.accept_trusted_contact_invitation(p_token text)
returns table (
    accepted_relationship_id uuid,
    accepted_traveller_user_id uuid,
    accepted_display_name text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_contact_id uuid := (select auth.uid());
    v_verified_email text;
    v_invitation public.trusted_contact_invitations%rowtype;
    v_relationship_id uuid;
    v_now timestamptz := now();
begin
    if v_contact_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_token is null or length(p_token) <> 64 then
        raise exception 'Invitation is invalid' using errcode = '22023';
    end if;
    select lower(btrim(u.email)) into v_verified_email
    from auth.users as u
    where u.id = v_contact_id and u.email_confirmed_at is not null;
    if v_verified_email is null then
        raise exception 'A verified email identity is required' using errcode = '42501';
    end if;

    select * into v_invitation
    from public.trusted_contact_invitations as i
    where i.token_hash = extensions.digest(convert_to(p_token, 'UTF8'), 'sha256')
    for update;
    if not found then
        raise exception 'Invitation is invalid' using errcode = '22023';
    end if;
    if v_invitation.status <> 'PENDING' then
        raise exception 'Invitation is not pending' using errcode = '22023';
    end if;
    if v_invitation.expires_at <= v_now then
        update public.trusted_contact_invitations set status = 'EXPIRED' where id = v_invitation.id;
        raise exception 'Invitation has expired' using errcode = '22023';
    end if;
    if v_verified_email <> v_invitation.invited_email_normalized then
        raise exception 'Authenticated email does not match this invitation' using errcode = '42501';
    end if;

    insert into public.trusted_contact_relationships (
        traveller_user_id, contact_user_id, display_name, contact_email_normalized,
        accepted_at, created_at
    ) values (
        v_invitation.traveller_user_id, v_contact_id, v_invitation.display_name,
        v_verified_email, v_now, v_now
    )
    on conflict (traveller_user_id, contact_user_id) where status = 'ACCEPTED'
    do update set display_name = excluded.display_name
    returning id into v_relationship_id;

    update public.trusted_contact_invitations
    set status = 'ACCEPTED', accepted_at = v_now
    where id = v_invitation.id and status = 'PENDING';

    insert into public.journey_trusted_contact_access (
        journey_id, relationship_id, traveller_user_id, contact_user_id
    )
    select j.id, v_relationship_id, j.owner_id, v_contact_id
    from public.journeys as j
    where j.owner_id = v_invitation.traveller_user_id and j.status = 'ACTIVE'
    on conflict (journey_id, relationship_id) do nothing;

    return query select v_relationship_id, v_invitation.traveller_user_id, v_invitation.display_name;
end;
$$;

create or replace function public.trusted_contact_has_journey_access(
    p_contact_user_id uuid,
    p_journey_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.journey_trusted_contact_access as a
        join public.trusted_contact_relationships as r on r.id = a.relationship_id
        where a.journey_id = p_journey_id
          and a.contact_user_id = p_contact_user_id
          and a.revoked_at is null
          and r.status = 'ACCEPTED'
          and r.contact_user_id = p_contact_user_id
    )
$$;

create or replace function public.list_trusted_journeys()
returns table (
    journey_id uuid,
    destination text,
    started_at timestamptz,
    expected_arrival_at timestamptz,
    journey_status text,
    monitoring_phase text,
    last_cloud_contact_at timestamptz
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
    select j.id, j.destination, j.started_at, j.expected_arrival_at, j.status,
           coalesce(s.phase, 'CLOSED'), s.last_cloud_contact_at
    from public.journey_trusted_contact_access as a
    join public.trusted_contact_relationships as r on r.id = a.relationship_id
    join public.journeys as j on j.id = a.journey_id
    left join public.journey_monitoring_state as s on s.journey_id = j.id
    where a.contact_user_id = v_contact_id and a.revoked_at is null and r.status = 'ACCEPTED'
    order by j.started_at desc;
end;
$$;

create or replace function public.get_trusted_journey_snapshot(p_journey_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_contact_id uuid := (select auth.uid());
    v_result jsonb;
begin
    if v_contact_id is null or not public.trusted_contact_has_journey_access(v_contact_id, p_journey_id) then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    select jsonb_build_object(
        'journey_id', j.id,
        'destination', j.destination,
        'started_at', j.started_at,
        'expected_arrival_at', j.expected_arrival_at,
        'journey_status', j.status,
        'monitoring_phase', coalesce(s.phase, 'CLOSED'),
        'last_cloud_contact_at', s.last_cloud_contact_at,
        'whereabouts', case when s.phase = 'VERIFYING' then 'UNKNOWN' else 'NOT_DISCLOSED' end,
        'open_case_id', case when s.phase = 'VERIFYING' and c.status = 'OPEN' then c.id else null end,
        'available_case_id', c.id
    ) into v_result
    from public.journeys as j
    left join public.journey_monitoring_state as s on s.journey_id = j.id
    left join lateral (
        select vc.id, vc.status
        from public.verification_cases as vc
        where vc.journey_id = j.id
          and (vc.status = 'OPEN' or vc.sensitive_access_expires_at > now())
        order by vc.opened_at desc
        limit 1
    ) as c on true
    where j.id = p_journey_id;

    insert into public.trusted_contact_access_events (event_type, contact_user_id, journey_id)
    values ('JOURNEY_VIEWED', v_contact_id, p_journey_id)
    on conflict do nothing;
    return v_result;
end;
$$;

create or replace function public.get_verification_case(p_case_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_contact_id uuid := (select auth.uid());
    v_case public.verification_cases%rowtype;
    v_result jsonb;
begin
    if v_contact_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into v_case from public.verification_cases where id = p_case_id;
    if not found or not public.trusted_contact_has_journey_access(v_contact_id, v_case.journey_id) then
        raise exception 'Verification case is not available to this caller' using errcode = '42501';
    end if;
    if v_case.status = 'RESOLVED' and v_case.sensitive_access_expires_at <= now() then
        raise exception 'Sensitive verification-case access has expired' using errcode = '42501';
    end if;

    select jsonb_build_object(
        'id', v_case.id,
        'journey_id', v_case.journey_id,
        'status', v_case.status,
        'opened_at', v_case.opened_at,
        'resolution_reason', v_case.resolution_reason,
        'resolved_at', v_case.resolved_at,
        'sensitive_access_expires_at', v_case.sensitive_access_expires_at,
        'last_cloud_contact_at', v_case.last_cloud_contact_at,
        'latest_known_telemetry_sequence', v_case.latest_known_telemetry_sequence,
        'last_verified_device_location', case when v_case.latitude is null then null else jsonb_build_object(
            'event_time', v_case.last_location_event_time,
            'latitude', v_case.latitude,
            'longitude', v_case.longitude,
            'accuracy_meters', v_case.accuracy_meters,
            'battery_percent', v_case.battery_percent,
            'charging', v_case.charging,
            'connectivity_state', v_case.connectivity_state,
            'provenance', 'device_verified'
        ) end,
        'journey', jsonb_build_object(
            'destination', v_case.journey_destination,
            'started_at', v_case.journey_started_at,
            'expected_arrival_at', v_case.journey_expected_arrival_at
        ),
        'recent_location_snapshot', coalesce((
            select jsonb_agg(jsonb_build_object(
                'position', p.position,
                'telemetry_sequence', p.telemetry_sequence,
                'event_time', p.event_time,
                'latitude', p.latitude,
                'longitude', p.longitude,
                'accuracy_meters', p.accuracy_meters,
                'battery_percent', p.battery_percent,
                'connectivity_state', p.connectivity_state,
                'provenance', 'device_verified'
            ) order by p.position)
            from public.verification_case_location_points as p where p.case_id = v_case.id
        ), '[]'::jsonb),
        'verification_history', coalesce((
            select jsonb_agg(jsonb_build_object(
                'event_type', e.event_type,
                'occurred_at', e.occurred_at,
                'reason_code', e.reason_code,
                'provenance', 'system_derived'
            ) order by e.occurred_at)
            from public.journey_monitoring_events as e
            where e.journey_id = v_case.journey_id and e.occurred_at >= v_case.opened_at
        ), '[]'::jsonb),
        'trusted_contact_reports', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', r.id,
                'report_type', r.report_type,
                'contact_time', r.contact_time,
                'note', r.note,
                'created_at', r.created_at,
                'provenance', r.provenance
            ) order by r.created_at)
            from public.verification_case_reports as r where r.case_id = v_case.id
        ), '[]'::jsonb)
    ) into v_result;

    insert into public.trusted_contact_access_events (event_type, contact_user_id, journey_id, case_id)
    values ('VERIFICATION_CASE_VIEWED', v_contact_id, v_case.journey_id, v_case.id)
    on conflict do nothing;
    if v_case.latitude is not null then
        insert into public.trusted_contact_access_events (event_type, contact_user_id, journey_id, case_id)
        values ('PRECISE_LOCATION_REVEALED', v_contact_id, v_case.journey_id, v_case.id)
        on conflict do nothing;
    end if;
    return v_result;
end;
$$;

create or replace function public.submit_verification_case_report(
    p_case_id uuid,
    p_report_type text,
    p_contact_time timestamptz,
    p_note text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_contact_id uuid := (select auth.uid());
    v_journey_id uuid;
    v_case_status text;
    v_sensitive_access_expires_at timestamptz;
    v_report_id uuid;
    v_note text := btrim(p_note);
begin
    select c.journey_id, c.status, c.sensitive_access_expires_at
    into v_journey_id, v_case_status, v_sensitive_access_expires_at
    from public.verification_cases as c where c.id = p_case_id;
    if v_contact_id is null or v_journey_id is null
       or not public.trusted_contact_has_journey_access(v_contact_id, v_journey_id) then
        raise exception 'Verification case is not available to this caller' using errcode = '42501';
    end if;
    if v_case_status = 'RESOLVED' and v_sensitive_access_expires_at <= now() then
        raise exception 'Sensitive verification-case access has expired' using errcode = '42501';
    end if;
    if p_report_type not in ('SPOKE_WITH_TRAVELLER', 'RECEIVED_MESSAGE_FROM_TRAVELLER', 'UPDATE_FROM_OTHER_PERSON', 'OTHER') then
        raise exception 'Unsupported report type' using errcode = '22023';
    end if;
    if length(v_note) not between 1 and 1000 then
        raise exception 'Report note must be between 1 and 1000 characters' using errcode = '22023';
    end if;
    insert into public.verification_case_reports (
        case_id, reporter_user_id, report_type, contact_time, note
    ) values (p_case_id, v_contact_id, p_report_type, p_contact_time, v_note)
    returning id into v_report_id;
    insert into public.trusted_contact_access_events (
        event_type, contact_user_id, journey_id, case_id, report_id
    ) values ('REPORT_SUBMITTED', v_contact_id, v_journey_id, p_case_id, v_report_id);
    return v_report_id;
end;
$$;

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
    return v_case_id;
end;
$$;

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
begin
    if p_reason not in ('DEVICE_CONTACT_RESTORED', 'JOURNEY_COMPLETED') then
        raise exception 'Unsupported verification-case resolution reason' using errcode = '22023';
    end if;
    update public.verification_cases
    set status = 'RESOLVED', resolution_reason = p_reason, resolved_at = p_resolved_at,
        sensitive_access_expires_at = p_resolved_at + public.sensitive_case_access_ttl()
    where journey_id = p_journey_id and status = 'OPEN';
    return found;
end;
$$;

-- Replace the Milestone 4 transitions so case changes occur in the same database transaction.
create or replace function public.record_journey_heartbeat(
    p_journey_id uuid,
    p_sequence bigint,
    p_client_sent_at timestamptz,
    p_battery_percent integer,
    p_charging boolean,
    p_connectivity_state text,
    p_latest_telemetry_sequence bigint
)
returns table (phase text, last_cloud_contact_at timestamptz, latest_heartbeat_sequence bigint, contact_restored boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner_id uuid; v_status text; v_received_at timestamptz; v_previous_phase text;
    v_inserted boolean := false; v_contact_restored boolean := false;
begin
    if (select auth.uid()) is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    select j.owner_id, j.status into v_owner_id, v_status
    from public.journeys as j where j.id = p_journey_id for update;
    if not found or v_owner_id <> (select auth.uid()) then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    if v_status <> 'ACTIVE' then raise exception 'Heartbeat requires an ACTIVE Journey' using errcode = '22023'; end if;

    insert into public.journey_heartbeats (
        journey_id, sequence, client_sent_at, battery_percent, charging, connectivity_state, latest_telemetry_sequence
    ) values (
        p_journey_id, p_sequence, p_client_sent_at, p_battery_percent, p_charging,
        p_connectivity_state, p_latest_telemetry_sequence
    ) on conflict (journey_id, sequence) do nothing returning received_at into v_received_at;
    if v_received_at is null then
        return query select s.phase, s.last_cloud_contact_at, s.latest_heartbeat_sequence, false
        from public.journey_monitoring_state as s where s.journey_id = p_journey_id;
        return;
    end if;

    insert into public.journey_monitoring_state (
        journey_id, owner_id, phase, last_cloud_contact_at, latest_heartbeat_sequence, updated_at
    ) values (p_journey_id, v_owner_id, 'EVIDENCE_FRESH', v_received_at, p_sequence, v_received_at)
    on conflict (journey_id) do nothing;
    v_inserted := found;
    if v_inserted then
        insert into public.journey_monitoring_events (
            journey_id, event_type, occurred_at, previous_phase, new_phase,
            last_cloud_contact_at, reason_code, threshold_seconds
        ) values (p_journey_id, 'MONITORING_STARTED', v_received_at, null, 'EVIDENCE_FRESH',
                  v_received_at, 'FIRST_FRESH_HEARTBEAT', null);
    else
        select s.phase into v_previous_phase from public.journey_monitoring_state as s
        where s.journey_id = p_journey_id for update;
        if p_sequence > (select s.latest_heartbeat_sequence from public.journey_monitoring_state as s where s.journey_id = p_journey_id) then
            v_contact_restored := v_previous_phase = 'VERIFYING';
            update public.journey_monitoring_state as s
            set phase = case when s.phase = 'VERIFYING' then 'EVIDENCE_FRESH' else s.phase end,
                last_cloud_contact_at = v_received_at, latest_heartbeat_sequence = p_sequence,
                verifying_started_at = case when s.phase = 'VERIFYING' then null else s.verifying_started_at end,
                updated_at = v_received_at
            where s.journey_id = p_journey_id and s.phase <> 'CLOSED';
            if v_contact_restored then
                insert into public.journey_monitoring_events (
                    journey_id, event_type, occurred_at, previous_phase, new_phase,
                    last_cloud_contact_at, reason_code, threshold_seconds
                ) values (p_journey_id, 'CONTACT_RESTORED', v_received_at, 'VERIFYING', 'EVIDENCE_FRESH',
                          v_received_at, 'FRESH_HEARTBEAT', null);
                perform public.resolve_open_verification_case(p_journey_id, 'DEVICE_CONTACT_RESTORED', v_received_at);
            end if;
        end if;
    end if;
    return query select s.phase, s.last_cloud_contact_at, s.latest_heartbeat_sequence, v_contact_restored
    from public.journey_monitoring_state as s where s.journey_id = p_journey_id;
end;
$$;

create or replace function public.close_journey_monitoring_on_completion()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare v_now timestamptz := now(); v_previous_phase text; v_last_contact timestamptz;
begin
    if new.status <> 'COMPLETED' then return new; end if;
    select s.phase, s.last_cloud_contact_at into v_previous_phase, v_last_contact
    from public.journey_monitoring_state as s where s.journey_id = new.id for update;
    if not found then
        insert into public.journey_monitoring_state (
            journey_id, owner_id, phase, latest_heartbeat_sequence, closed_at, updated_at
        ) values (new.id, new.owner_id, 'CLOSED', 0, v_now, v_now);
        v_previous_phase := null;
    elsif v_previous_phase = 'CLOSED' then
        perform public.resolve_open_verification_case(new.id, 'JOURNEY_COMPLETED', v_now);
        return new;
    else
        update public.journey_monitoring_state set phase = 'CLOSED', verifying_started_at = null,
            closed_at = v_now, updated_at = v_now where journey_id = new.id;
    end if;
    insert into public.journey_monitoring_events (
        journey_id, event_type, occurred_at, previous_phase, new_phase,
        last_cloud_contact_at, reason_code, threshold_seconds
    ) values (new.id, 'MONITORING_CLOSED', v_now, v_previous_phase, 'CLOSED',
              v_last_contact, 'JOURNEY_COMPLETED', null);
    perform public.resolve_open_verification_case(new.id, 'JOURNEY_COMPLETED', v_now);
    update public.journey_trusted_contact_access
    set sensitive_access_expires_at = v_now + public.sensitive_case_access_ttl()
    where journey_id = new.id and revoked_at is null;
    return new;
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
        select s.journey_id, s.owner_id, s.phase as previous_phase, s.last_cloud_contact_at
        from public.journey_monitoring_state as s
        join public.journeys as j on j.id = s.journey_id
        where j.status = 'ACTIVE' and s.phase = 'EVIDENCE_FRESH'
          and s.last_cloud_contact_at <= v_now - make_interval(secs => v_threshold_seconds)
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
                      v_due.last_cloud_contact_at, 'HEARTBEAT_TIMEOUT', v_threshold_seconds)
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

revoke all on function public.trusted_contact_invitation_ttl() from public, anon, authenticated, service_role;
revoke all on function public.sensitive_case_access_ttl() from public, anon, authenticated, service_role;
revoke all on function public.provision_trusted_contacts_for_journey() from public, anon, authenticated, service_role;
revoke all on function public.trusted_contact_has_journey_access(uuid, uuid) from public, anon, authenticated, service_role;
revoke all on function public.open_verification_case(uuid, uuid, bigint, timestamptz, timestamptz) from public, anon, authenticated, service_role;
revoke all on function public.resolve_open_verification_case(uuid, text, timestamptz) from public, anon, authenticated, service_role;
revoke all on function public.create_trusted_contact_invitation(text, text) from public, anon, authenticated, service_role;
revoke all on function public.list_trusted_contact_management() from public, anon, authenticated, service_role;
revoke all on function public.revoke_trusted_contact(uuid) from public, anon, authenticated, service_role;
revoke all on function public.accept_trusted_contact_invitation(text) from public, anon, authenticated, service_role;
revoke all on function public.list_trusted_journeys() from public, anon, authenticated, service_role;
revoke all on function public.get_trusted_journey_snapshot(uuid) from public, anon, authenticated, service_role;
revoke all on function public.get_verification_case(uuid) from public, anon, authenticated, service_role;
revoke all on function public.submit_verification_case_report(uuid, text, timestamptz, text) from public, anon, authenticated, service_role;

grant execute on function public.create_trusted_contact_invitation(text, text) to authenticated;
grant execute on function public.list_trusted_contact_management() to authenticated;
grant execute on function public.revoke_trusted_contact(uuid) to authenticated;
grant execute on function public.accept_trusted_contact_invitation(text) to authenticated;
grant execute on function public.list_trusted_journeys() to authenticated;
grant execute on function public.get_trusted_journey_snapshot(uuid) to authenticated;
grant execute on function public.get_verification_case(uuid) to authenticated;
grant execute on function public.submit_verification_case_report(uuid, text, timestamptz, text) to authenticated;

commit;

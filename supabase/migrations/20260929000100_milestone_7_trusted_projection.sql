begin;

-- Identity is set by the authenticated Journey owner, never inferred from
-- email or from the relationship display_name (which names the contact).
-- Store the handle without @. Accept one optional leading @ on input, then
-- lowercase it. Valid handles are 3-24 ASCII characters: a-z first,
-- a-z/0-9/underscore inside, and a-z/0-9 last. The unique handle is for
-- recognition only; relationship and Journey grants remain authorization.
create table public.traveller_profiles (
    owner_id uuid primary key references auth.users(id) on delete restrict,
    full_name text not null check (
        full_name = btrim(full_name)
        and length(full_name) between 1 and 100
        and full_name ~ '[^[:space:]]'
        and full_name !~ '[[:cntrl:]]'
    ),
    handle text not null unique check (
        handle = lower(handle)
        and handle collate "C" ~ '^[a-z][a-z0-9_]{1,22}[a-z0-9]$'
    ),
    updated_at timestamptz not null default now()
);

alter table public.traveller_profiles enable row level security;
revoke all on table public.traveller_profiles from public, anon, authenticated, service_role;

create function public.set_traveller_profile_identity(p_full_name text, p_handle text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner_id uuid := (select auth.uid());
    v_full_name text := btrim(p_full_name);
    v_handle text := regexp_replace(lower(btrim(p_handle)), '^@', '');
begin
    if v_owner_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    -- Supabase anonymous users also have the authenticated database role.
    -- Check the Auth system of record before a handle can be claimed.
    if not exists (
        select 1 from auth.users as u
        where u.id = v_owner_id
          and u.email is not null and btrim(u.email) <> ''
          and u.email_confirmed_at is not null
          and u.is_anonymous is false
    ) then
        raise exception 'A verified email identity is required' using errcode = '42501';
    end if;
    if v_full_name is null or length(v_full_name) not between 1 and 100
       or v_full_name !~ '[^[:space:]]' or v_full_name ~ '[[:cntrl:]]' then
        raise exception 'Traveller full name must be 1 to 100 visible characters without control characters'
            using errcode = '22023';
    end if;
    if v_handle is null or v_handle collate "C" !~ '^[a-z][a-z0-9_]{1,22}[a-z0-9]$' then
        raise exception 'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit'
            using errcode = '22023';
    end if;

    insert into public.traveller_profiles (owner_id, full_name, handle)
    values (v_owner_id, v_full_name, v_handle)
    on conflict (owner_id) do update
    set full_name = excluded.full_name, handle = excluded.handle, updated_at = now();
    return jsonb_build_object('full_name', v_full_name, 'handle', v_handle);
exception when unique_violation then
    raise exception 'JOURNEY handle is unavailable' using errcode = '23505';
end;
$$;

-- A provisional, single-handle availability check. It reveals only one
-- boolean to an authenticated Supabase user (including an anonymous user).
-- The unique constraint above remains authoritative when the owner saves.
create function public.check_traveller_handle_availability(p_handle text)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_caller_id uuid := (select auth.uid());
    v_handle text := regexp_replace(lower(btrim(p_handle)), '^@', '');
begin
    if v_caller_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if v_handle is null or v_handle collate "C" !~ '^[a-z][a-z0-9_]{1,22}[a-z0-9]$' then
        raise exception 'JOURNEY handle must be 3 to 24 ASCII characters, start with a letter, and end with a letter or digit'
            using errcode = '22023';
    end if;

    return not exists (
        select 1 from public.traveller_profiles as p
        where p.handle = v_handle and p.owner_id <> v_caller_id
    );
end;
$$;

-- Internal trusted-person API. Journey UUIDs are internal identifiers here;
-- the later MCP boundary must mint its own caller-bound opaque references.
create function public.list_trusted_journey_statuses_v1()
returns table (
    journey_id uuid,
    traveller_full_name text,
    traveller_handle text,
    destination text,
    started_at timestamptz,
    expected_arrival_at timestamptz,
    journey_status text,
    monitoring_phase text,
    completed_at timestamptz,
    last_cloud_contact_at timestamptz,
    last_authenticated_device_evidence_at timestamptz,
    last_authenticated_device_evidence_received_at timestamptz,
    last_authenticated_device_evidence_transport text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare v_contact_id uuid := (select auth.uid());
begin
    if v_contact_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    return query
    select j.id, p.full_name, p.handle, j.destination, j.started_at, j.expected_arrival_at,
           j.status, s.phase, j.completed_at,
           s.last_cloud_contact_at, s.last_authenticated_device_evidence_at,
           s.last_authenticated_device_evidence_received_at,
           s.last_authenticated_device_evidence_transport
    from public.journey_trusted_contact_access as a
    join public.trusted_contact_relationships as r
      on r.id = a.relationship_id
     and r.traveller_user_id = a.traveller_user_id
     and r.contact_user_id = a.contact_user_id
    join public.journeys as j
      on j.id = a.journey_id and j.owner_id = a.traveller_user_id
    left join public.traveller_profiles as p on p.owner_id = j.owner_id
    left join public.journey_monitoring_state as s on s.journey_id = j.id
    where a.contact_user_id = v_contact_id
      and a.revoked_at is null
      and r.status = 'ACCEPTED'
      and r.revoked_at is null
    order by j.started_at desc, j.id;
end;
$$;

create function public.get_trusted_journey_status_v1(p_journey_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare v_result jsonb;
begin
    -- The list rechecks the accepted relationship and Journey grant under
    -- auth.uid() on every call. A raw/guessed UUID supplies no authority.
    select to_jsonb(status_row) into v_result
    from public.list_trusted_journey_statuses_v1() as status_row
    where status_row.journey_id = p_journey_id;
    if v_result is null then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    return v_result;
end;
$$;

revoke all on function public.set_traveller_profile_identity(text, text) from public, anon, authenticated, service_role;
revoke all on function public.check_traveller_handle_availability(text) from public, anon, authenticated, service_role;
revoke all on function public.list_trusted_journey_statuses_v1() from public, anon, authenticated, service_role;
revoke all on function public.get_trusted_journey_status_v1(uuid) from public, anon, authenticated, service_role;
grant execute on function public.set_traveller_profile_identity(text, text) to authenticated;
grant execute on function public.check_traveller_handle_availability(text) to authenticated;
grant execute on function public.list_trusted_journey_statuses_v1() to authenticated;
grant execute on function public.get_trusted_journey_status_v1(uuid) to authenticated;

commit;

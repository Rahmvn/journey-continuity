begin;

-- First profile completion is insert-only. The existing setter remains the
-- authenticated Traveller's intentional Edit identity contract.
create function public.complete_traveller_profile_identity_v1(p_full_name text, p_handle text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner_id uuid := (select auth.uid());
    v_full_name text := btrim(p_full_name);
    v_handle text := regexp_replace(lower(btrim(p_handle)), '^@', '');
    v_inserted uuid;
    v_existing_full_name text;
    v_existing_handle text;
begin
    if v_owner_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
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

    -- ON CONFLICT waits for a concurrent unique-key winner, then does no
    -- update. The next SQL statement gets a fresh READ COMMITTED snapshot, so
    -- it can classify the committed winner by this caller's own owner key.
    insert into public.traveller_profiles (owner_id, full_name, handle)
    values (v_owner_id, v_full_name, v_handle)
    on conflict do nothing
    returning owner_id into v_inserted;

    if v_inserted is not null then
        return jsonb_build_object('status', 'CREATED');
    end if;

    select p.full_name, p.handle
    into v_existing_full_name, v_existing_handle
    from public.traveller_profiles as p
    where p.owner_id = v_owner_id;
    if found then
        return jsonb_build_object(
            'status', 'ALREADY_COMPLETED',
            'matches_request', v_existing_full_name = v_full_name and v_existing_handle = v_handle
        );
    end if;

    -- The only remaining uniqueness key is the requested handle. Return no
    -- owner or profile details about the Traveller who holds it.
    return jsonb_build_object('status', 'HANDLE_UNAVAILABLE');
end;
$$;

revoke all on function public.complete_traveller_profile_identity_v1(text, text)
    from public, anon, authenticated, service_role;
grant execute on function public.complete_traveller_profile_identity_v1(text, text) to authenticated;

commit;

begin;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

alter table public.journeys
    add constraint journeys_id_owner_unique unique (id, owner_id);

create table private.fallback_installations (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete restrict,
    installation_identifier uuid not null unique,
    created_at timestamptz not null default now(),
    unique (id, owner_id)
);

create table private.fallback_installation_keys (
    id uuid primary key default gen_random_uuid(),
    installation_id uuid not null,
    owner_id uuid not null,
    key_id bigint not null unique check (key_id between 0 and 4294967295),
    encrypted_master_key bytea not null check (octet_length(encrypted_master_key) = 48),
    encryption_iv bytea not null check (octet_length(encryption_iv) = 12),
    encryption_version integer not null check (encryption_version > 0),
    lifecycle_status text not null check (lifecycle_status in ('ACTIVE', 'RETIRED', 'REVOKED')),
    created_at timestamptz not null default now(),
    retired_at timestamptz,
    revoked_at timestamptz,
    foreign key (installation_id, owner_id)
        references private.fallback_installations(id, owner_id) on delete restrict,
    constraint fallback_key_lifecycle_timestamps check (
        (lifecycle_status = 'ACTIVE' and retired_at is null and revoked_at is null)
        or (lifecycle_status = 'RETIRED' and retired_at is not null and revoked_at is null)
        or (lifecycle_status = 'REVOKED' and revoked_at is not null)
    ),
    unique (id, installation_id, owner_id, key_id)
);

create unique index fallback_installation_keys_one_active
on private.fallback_installation_keys (installation_id)
where lifecycle_status = 'ACTIVE';

create table private.journey_fallback_bindings (
    id uuid primary key default gen_random_uuid(),
    journey_id uuid not null,
    owner_id uuid not null,
    installation_id uuid not null,
    installation_key_id uuid not null,
    key_id bigint not null check (key_id between 0 and 4294967295),
    journey_handle bytea not null unique check (octet_length(journey_handle) = 12),
    binding_version integer not null default 1 check (binding_version > 0),
    binding_status text not null check (binding_status in ('ACTIVE', 'REVOKED')),
    created_at timestamptz not null default now(),
    revoked_at timestamptz,
    foreign key (journey_id, owner_id)
        references public.journeys(id, owner_id) on delete restrict,
    foreign key (installation_key_id, installation_id, owner_id, key_id)
        references private.fallback_installation_keys(id, installation_id, owner_id, key_id)
        on delete restrict,
    constraint fallback_binding_revocation_consistent check (
        (binding_status = 'ACTIVE' and revoked_at is null)
        or (binding_status = 'REVOKED' and revoked_at is not null)
    )
);

create unique index journey_fallback_bindings_one_active_journey
on private.journey_fallback_bindings (journey_id)
where binding_status = 'ACTIVE';

create index journey_fallback_bindings_ingestion_lookup
on private.journey_fallback_bindings (key_id, journey_handle);

revoke all on all tables in schema private from public, anon, authenticated;

create or replace function public.provision_fallback_material_backend(
    p_owner_id uuid,
    p_installation_identifier uuid,
    p_journey_id uuid,
    p_candidate_key_id bigint,
    p_candidate_encrypted_master_key bytea,
    p_candidate_encryption_iv bytea,
    p_encryption_version integer,
    p_candidate_journey_handle bytea
)
returns table (
    key_id bigint,
    encrypted_master_key bytea,
    encryption_iv bytea,
    encryption_version integer,
    journey_handle bytea,
    binding_status text,
    binding_version integer,
    key_lifecycle_status text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_installation private.fallback_installations%rowtype;
    v_key private.fallback_installation_keys%rowtype;
    v_binding private.journey_fallback_bindings%rowtype;
    v_journey_owner uuid;
    v_journey_status text;
begin
    if p_owner_id is null or p_installation_identifier is null or p_journey_id is null then
        raise exception 'Owner, installation, and Journey are required' using errcode = '22023';
    end if;
    if p_candidate_key_id < 0 or p_candidate_key_id > 4294967295
       or octet_length(p_candidate_encrypted_master_key) <> 48
       or octet_length(p_candidate_encryption_iv) <> 12
       or p_encryption_version <= 0
       or octet_length(p_candidate_journey_handle) <> 12 then
        raise exception 'Invalid fallback provisioning material' using errcode = '22023';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('fallback-installation:' || p_installation_identifier::text, 0)
    );
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('fallback-journey:' || p_journey_id::text, 0)
    );

    select j.owner_id, j.status into v_journey_owner, v_journey_status
    from public.journeys as j where j.id = p_journey_id for update;
    if not found or v_journey_owner <> p_owner_id then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    if v_journey_status <> 'ACTIVE' then
        raise exception 'Fallback provisioning requires an ACTIVE Journey' using errcode = '22023';
    end if;

    select i.* into v_installation
    from private.fallback_installations as i
    where i.installation_identifier = p_installation_identifier
    for update;
    if found and v_installation.owner_id <> p_owner_id then
        raise exception 'Installation is not available to this caller' using errcode = '42501';
    elsif not found then
        insert into private.fallback_installations (owner_id, installation_identifier)
        values (p_owner_id, p_installation_identifier)
        returning * into v_installation;
    end if;

    select b.* into v_binding
    from private.journey_fallback_bindings as b
    where b.journey_id = p_journey_id and b.binding_status = 'ACTIVE'
    for update;
    if found and v_binding.installation_id <> v_installation.id then
        raise exception 'Journey is already bound to another installation' using errcode = '42501';
    end if;

    if found then
        select k.* into v_key
        from private.fallback_installation_keys as k
        where k.id = v_binding.installation_key_id;
        if v_key.lifecycle_status = 'REVOKED' then
            raise exception 'Journey fallback key is revoked' using errcode = '42501';
        end if;
    else
        select k.* into v_key
        from private.fallback_installation_keys as k
        where k.installation_id = v_installation.id and k.lifecycle_status = 'ACTIVE'
        for update;
        if not found then
            insert into private.fallback_installation_keys (
                installation_id, owner_id, key_id, encrypted_master_key,
                encryption_iv, encryption_version, lifecycle_status
            ) values (
                v_installation.id, p_owner_id, p_candidate_key_id,
                p_candidate_encrypted_master_key, p_candidate_encryption_iv,
                p_encryption_version, 'ACTIVE'
            ) returning * into v_key;
        end if;

        insert into private.journey_fallback_bindings (
            journey_id, owner_id, installation_id, installation_key_id,
            key_id, journey_handle, binding_status
        ) values (
            p_journey_id, p_owner_id, v_installation.id, v_key.id,
            v_key.key_id, p_candidate_journey_handle, 'ACTIVE'
        ) returning * into v_binding;
    end if;

    return query select
        v_key.key_id,
        v_key.encrypted_master_key,
        v_key.encryption_iv,
        v_key.encryption_version,
        v_binding.journey_handle,
        v_binding.binding_status,
        v_binding.binding_version,
        v_key.lifecycle_status;
end;
$$;

create or replace function public.rotate_fallback_installation_key_backend(
    p_owner_id uuid,
    p_installation_identifier uuid,
    p_candidate_key_id bigint,
    p_candidate_encrypted_master_key bytea,
    p_candidate_encryption_iv bytea,
    p_encryption_version integer
)
returns table (
    key_id bigint,
    encrypted_master_key bytea,
    encryption_iv bytea,
    encryption_version integer,
    lifecycle_status text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_installation private.fallback_installations%rowtype;
    v_key private.fallback_installation_keys%rowtype;
begin
    if p_candidate_key_id < 0 or p_candidate_key_id > 4294967295
       or octet_length(p_candidate_encrypted_master_key) <> 48
       or octet_length(p_candidate_encryption_iv) <> 12
       or p_encryption_version <= 0 then
        raise exception 'Invalid fallback rotation material' using errcode = '22023';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('fallback-installation:' || p_installation_identifier::text, 0)
    );
    select i.* into v_installation
    from private.fallback_installations as i
    where i.installation_identifier = p_installation_identifier
    for update;
    if not found or v_installation.owner_id <> p_owner_id then
        raise exception 'Installation is not available to this caller' using errcode = '42501';
    end if;
    update private.fallback_installation_keys
    set lifecycle_status = 'RETIRED', retired_at = now()
    where installation_id = v_installation.id and lifecycle_status = 'ACTIVE';
    insert into private.fallback_installation_keys (
        installation_id, owner_id, key_id, encrypted_master_key,
        encryption_iv, encryption_version, lifecycle_status
    ) values (
        v_installation.id, p_owner_id, p_candidate_key_id,
        p_candidate_encrypted_master_key, p_candidate_encryption_iv,
        p_encryption_version, 'ACTIVE'
    ) returning * into v_key;
    return query select v_key.key_id, v_key.encrypted_master_key,
        v_key.encryption_iv, v_key.encryption_version, v_key.lifecycle_status;
end;
$$;

create or replace function public.revoke_fallback_installation_key_backend(
    p_owner_id uuid,
    p_installation_identifier uuid,
    p_key_id bigint
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_installation_id uuid;
begin
    select i.id into v_installation_id
    from private.fallback_installations as i
    where i.installation_identifier = p_installation_identifier
      and i.owner_id = p_owner_id
    for update;
    if not found then
        raise exception 'Installation is not available to this caller' using errcode = '42501';
    end if;
    update private.fallback_installation_keys
    set lifecycle_status = 'REVOKED', revoked_at = now()
    where installation_id = v_installation_id and key_id = p_key_id
      and lifecycle_status in ('ACTIVE', 'RETIRED');
    return found;
end;
$$;

revoke all on function public.provision_fallback_material_backend(
    uuid, uuid, uuid, bigint, bytea, bytea, integer, bytea
) from public, anon, authenticated;
revoke all on function public.rotate_fallback_installation_key_backend(
    uuid, uuid, bigint, bytea, bytea, integer
) from public, anon, authenticated;
revoke all on function public.revoke_fallback_installation_key_backend(
    uuid, uuid, bigint
) from public, anon, authenticated;

grant execute on function public.provision_fallback_material_backend(
    uuid, uuid, uuid, bigint, bytea, bytea, integer, bytea
) to service_role;
grant execute on function public.rotate_fallback_installation_key_backend(
    uuid, uuid, bigint, bytea, bytea, integer
) to service_role;
grant execute on function public.revoke_fallback_installation_key_backend(
    uuid, uuid, bigint
) to service_role;

commit;

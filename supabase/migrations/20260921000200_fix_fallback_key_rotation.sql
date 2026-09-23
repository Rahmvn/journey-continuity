begin;

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
    update private.fallback_installation_keys as k
    set lifecycle_status = 'RETIRED', retired_at = now()
    where k.installation_id = v_installation.id and k.lifecycle_status = 'ACTIVE';
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

revoke all on function public.rotate_fallback_installation_key_backend(
    uuid, uuid, bigint, bytea, bytea, integer
) from public, anon, authenticated;
grant execute on function public.rotate_fallback_installation_key_backend(
    uuid, uuid, bigint, bytea, bytea, integer
) to service_role;

commit;

begin;

-- Camera initialization only. These bounds are never destination coordinates
-- or containment evidence. Raw operational geometry remains private.
create function public.get_destination_local_unit_viewport_v1(
    p_region_id text, p_local_unit_id text
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
    v_owner_id uuid := (select auth.uid());
    v_admin_version text;
    v_dataset private.boundary_dataset_versions%rowtype;
    v_geom gis.geometry;
    v_west double precision;
    v_south double precision;
    v_east double precision;
    v_north double precision;
begin
    -- Keep the same verified, non-anonymous Traveller boundary as the verifier.
    if v_owner_id is null or not exists (
        select 1 from auth.users u join public.traveller_profiles p on p.owner_id = u.id
        where u.id = v_owner_id and u.is_anonymous is false
          and u.email is not null and btrim(u.email) <> ''
          and u.email_confirmed_at is not null
    ) then
        raise exception 'Authenticated Traveller profile required' using errcode = '42501';
    end if;

    if p_region_id is null or p_local_unit_id is null
       or length(p_region_id) > 120 or length(p_local_unit_id) > 120
       or p_region_id !~ '^ng-[a-z0-9]+(-[a-z0-9]+)*$'
       or p_local_unit_id !~ '^ng-[a-z0-9]+(-[a-z0-9]+)*$' then
        return jsonb_build_object('status','INVALID_CONTEXT');
    end if;
    select version into v_admin_version from public.admin_catalogue_versions
      where dataset_id = 'NG_ADMIN' order by snapshot_date desc, version desc limit 1;
    if v_admin_version is null or not exists (
        select 1 from public.admin_regions r join public.admin_local_units u
          on u.dataset_id = r.dataset_id and u.version = r.version and u.parent_region_id = r.id
         where r.dataset_id = 'NG_ADMIN' and r.version = v_admin_version
           and r.id = p_region_id and u.id = p_local_unit_id
    ) then
        return jsonb_build_object('status','INVALID_CONTEXT');
    end if;

    select * into v_dataset from private.boundary_dataset_versions
      where dataset_id = 'NG_OPERATIONAL_BOUNDARIES'
      order by snapshot_date desc, version desc limit 1;
    if not found then
        return jsonb_build_object('status','UNAVAILABLE');
    end if;
    -- Do not silently fall back to an older snapshot when the current one is incomplete.
    if (select count(*) from private.boundary_regions
        where dataset_id = v_dataset.dataset_id and version = v_dataset.version) <> v_dataset.expected_region_count
       or (select count(*) from private.boundary_local_units
        where dataset_id = v_dataset.dataset_id and version = v_dataset.version) <> v_dataset.expected_local_unit_count then
        return jsonb_build_object('status','UNAVAILABLE');
    end if;
    select u.geom into v_geom from private.boundary_local_units u
      join private.boundary_regions r
        on r.dataset_id = u.dataset_id and r.version = u.version and r.region_id = u.region_id
     where u.dataset_id = v_dataset.dataset_id and u.version = v_dataset.version
       and u.region_id = p_region_id and u.local_unit_id = p_local_unit_id;
    if not found or v_geom is null or gis.ST_IsEmpty(v_geom)
       or not gis.ST_IsValid(v_geom) or gis.ST_SRID(v_geom) <> 4326 then
        return jsonb_build_object('status','UNAVAILABLE');
    end if;

    -- Box3D encloses every component of a MultiPolygon, including detached parts.
    v_west := gis.ST_XMin(gis.Box3D(v_geom));
    v_south := gis.ST_YMin(gis.Box3D(v_geom));
    v_east := gis.ST_XMax(gis.Box3D(v_geom));
    v_north := gis.ST_YMax(gis.Box3D(v_geom));
    -- Reject non-finite/out-of-range/degenerate bounds. A longitude span over
    -- 180 degrees may cross the antimeridian; v1 deliberately does not unwrap it.
    if v_west is null or v_south is null or v_east is null or v_north is null
       or not (v_west between -180 and 180 and v_east between -180 and 180
           and v_south between -90 and 90 and v_north between -90 and 90
           and v_west < v_east and v_south < v_north and v_east - v_west <= 180) then
        return jsonb_build_object('status','UNAVAILABLE');
    end if;
    return jsonb_build_object('status','AVAILABLE',
        'west',v_west,'south',v_south,'east',v_east,'north',v_north,
        'boundary_dataset_id',v_dataset.dataset_id,'boundary_dataset_version',v_dataset.version);
end;
$$;

revoke all on function public.get_destination_local_unit_viewport_v1(text,text)
    from public, anon, authenticated, service_role;
grant execute on function public.get_destination_local_unit_viewport_v1(text,text) to authenticated;
comment on function public.get_destination_local_unit_viewport_v1(text,text) is
    'Authenticated Traveller camera bounds only; never a selected destination or point verification.';

commit;

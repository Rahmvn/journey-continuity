-- Destination search can classify a candidate without exposing raw boundaries
-- or the internal classifier to ordinary clients.
create function public.classify_destination_coordinate_service_v1(
    p_region_id text, p_local_unit_id text, p_latitude double precision,
    p_longitude double precision
) returns jsonb language sql stable security definer set search_path = '' as $$
    select private.classify_destination_coordinate_v1(
        p_region_id, p_local_unit_id, p_latitude, p_longitude, null
    );
$$;

revoke all on function public.classify_destination_coordinate_service_v1(
    text, text, double precision, double precision
) from public, anon, authenticated, service_role;
grant execute on function public.classify_destination_coordinate_service_v1(
    text, text, double precision, double precision
) to service_role;

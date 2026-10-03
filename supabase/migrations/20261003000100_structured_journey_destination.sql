begin;

-- The existing destination label remains the only required destination fact.
alter table public.journeys
    add column destination_country_code text,
    add column destination_state_id text,
    add column destination_state_name text,
    add column destination_lga_id text,
    add column destination_lga_name text,
    add column destination_kind text,
    add column destination_latitude double precision,
    add column destination_longitude double precision,
    add column destination_selection_source text,
    add column destination_provider text,
    add column destination_provider_place_id text,
    add column destination_admin_dataset_version text,
    add column destination_custom_place_name text;

alter table public.journeys add constraint journeys_structured_destination_consistent check (
    (
        destination_country_code is null and destination_state_id is null and
        destination_state_name is null and destination_lga_id is null and
        destination_lga_name is null and destination_kind is null and
        destination_latitude is null and destination_longitude is null and
        destination_selection_source is null and destination_provider is null and
        destination_provider_place_id is null and destination_admin_dataset_version is null and
        destination_custom_place_name is null
    ) or coalesce((
        destination_country_code = 'NG' and
        destination_state_id is not null and destination_state_id = btrim(destination_state_id) and
        length(destination_state_id) > 0 and
        destination_state_name is not null and destination_state_name = btrim(destination_state_name) and
        length(destination_state_name) > 0 and
        destination_lga_id is not null and destination_lga_id = btrim(destination_lga_id) and
        length(destination_lga_id) > 0 and
        destination_lga_name is not null and destination_lga_name = btrim(destination_lga_name) and
        length(destination_lga_name) > 0 and
        destination_kind in ('POINT', 'LOCALITY') and
        destination_selection_source in ('PLACE_SEARCH', 'MAP_PIN', 'LOCALITY_SELECTION') and
        ((destination_latitude is null and destination_longitude is null) or
         (destination_latitude is not null and destination_longitude is not null and
          destination_latitude between -90 and 90 and destination_longitude between -180 and 180)) and
        ((destination_kind = 'POINT' and destination_latitude is not null and
          destination_selection_source in ('PLACE_SEARCH', 'MAP_PIN')) or
         (destination_kind = 'LOCALITY' and destination_latitude is null and
          destination_longitude is null and destination_selection_source = 'LOCALITY_SELECTION')) and
        (destination_provider is null or (destination_provider = btrim(destination_provider) and
            length(destination_provider) > 0)) and
        (destination_provider_place_id is null or (destination_provider is not null and
            destination_provider_place_id = btrim(destination_provider_place_id) and
            length(destination_provider_place_id) > 0)) and
        (destination_admin_dataset_version is null or
            (destination_admin_dataset_version = btrim(destination_admin_dataset_version) and
             length(destination_admin_dataset_version) > 0)) and
        (destination_custom_place_name is null or
            (destination_selection_source = 'MAP_PIN' and
             destination_custom_place_name = btrim(destination_custom_place_name) and
             length(destination_custom_place_name) > 0))
    ), false)
);

-- PostgREST merge upserts from old clients can present omitted nullable columns as NULL.
-- Preserve each missing existing fact before rejecting actual destination changes.
-- This covers both full-row and subset-column conflict updates from older clients.
create function public.preserve_immutable_journey_destination()
returns trigger language plpgsql set search_path = '' as $$
begin
    if old.destination_kind is not null then
        new.destination_country_code := coalesce(new.destination_country_code, old.destination_country_code);
        new.destination_state_id := coalesce(new.destination_state_id, old.destination_state_id);
        new.destination_state_name := coalesce(new.destination_state_name, old.destination_state_name);
        new.destination_lga_id := coalesce(new.destination_lga_id, old.destination_lga_id);
        new.destination_lga_name := coalesce(new.destination_lga_name, old.destination_lga_name);
        new.destination_kind := coalesce(new.destination_kind, old.destination_kind);
        new.destination_latitude := coalesce(new.destination_latitude, old.destination_latitude);
        new.destination_longitude := coalesce(new.destination_longitude, old.destination_longitude);
        new.destination_selection_source := coalesce(new.destination_selection_source, old.destination_selection_source);
        new.destination_provider := coalesce(new.destination_provider, old.destination_provider);
        new.destination_provider_place_id := coalesce(new.destination_provider_place_id, old.destination_provider_place_id);
        new.destination_admin_dataset_version := coalesce(new.destination_admin_dataset_version, old.destination_admin_dataset_version);
        new.destination_custom_place_name := coalesce(new.destination_custom_place_name, old.destination_custom_place_name);
    end if;

    if new.destination is distinct from old.destination or
       row(new.destination_country_code, new.destination_state_id, new.destination_state_name,
           new.destination_lga_id, new.destination_lga_name, new.destination_kind,
           new.destination_latitude, new.destination_longitude, new.destination_selection_source,
           new.destination_provider, new.destination_provider_place_id,
           new.destination_admin_dataset_version, new.destination_custom_place_name)
       is distinct from
       row(old.destination_country_code, old.destination_state_id, old.destination_state_name,
           old.destination_lga_id, old.destination_lga_name, old.destination_kind,
           old.destination_latitude, old.destination_longitude, old.destination_selection_source,
           old.destination_provider, old.destination_provider_place_id,
           old.destination_admin_dataset_version, old.destination_custom_place_name) then
        raise exception 'Journey destination is immutable' using errcode = 'JD001';
    end if;
    return new;
end;
$$;

create trigger journeys_destination_immutable
before update on public.journeys
for each row execute function public.preserve_immutable_journey_destination();
revoke all on function public.preserve_immutable_journey_destination() from public, anon, authenticated, service_role;

-- No trusted function is widened: current projections continue selecting destination text only.
commit;

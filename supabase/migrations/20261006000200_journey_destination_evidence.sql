begin;

-- Additive evidence only. Historical text-only Journeys remain entirely unknown.
alter table public.journeys
    add column destination_local_unit_type text,
    add column destination_boundary_dataset_id text,
    add column destination_boundary_dataset_version text,
    add column destination_provider_attribution jsonb;

create function private.valid_destination_evidence_text(p_value text, p_max integer)
returns boolean language sql immutable set search_path = '' as $$
    select coalesce(p_value is not null and length(p_value) between 1 and p_max
        and p_value = btrim(p_value)
        and p_value !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]', false);
$$;

-- Exactly the four minimized fields returned by place/reverse labeling.
-- This checks retained provenance shape, not provider authenticity.
create function private.valid_destination_provider_attribution(p_value jsonb)
returns boolean language sql immutable security definer set search_path = '' as $$
    select case when jsonb_typeof(p_value) = 'object' then coalesce(
        p_value ?& array['sourceName', 'attribution', 'license', 'sourceUrl']
        and p_value - array['sourceName', 'attribution', 'license', 'sourceUrl'] = '{}'::jsonb
        and jsonb_typeof(p_value->'sourceName') = 'string'
        and jsonb_typeof(p_value->'attribution') = 'string'
        and jsonb_typeof(p_value->'license') = 'string'
        and jsonb_typeof(p_value->'sourceUrl') = 'string'
        and private.valid_destination_evidence_text(p_value->>'sourceName', 80)
        and private.valid_destination_evidence_text(p_value->>'attribution', 160)
        and private.valid_destination_evidence_text(p_value->>'license', 80)
        and private.valid_destination_evidence_text(p_value->>'sourceUrl', 300)
        and p_value->>'sourceUrl' ~ '^https://([A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:]+\])(:[0-9]+)?([/?#][^[:space:]]*)?$',
        false) else false end;
$$;

create function private.valid_destination_subtype_evidence(
    p_kind text, p_selection_source text, p_latitude double precision, p_longitude double precision,
    p_provider text, p_provider_place_id text, p_provider_attribution jsonb, p_custom_place_name text,
    p_boundary_dataset_id text, p_boundary_dataset_version text)
returns boolean language sql immutable security definer set search_path = '' as $$
    select coalesce(
        (p_provider is null or private.valid_destination_evidence_text(p_provider, 80))
        and (p_provider_place_id is null or private.valid_destination_evidence_text(p_provider_place_id, 512))
        and (p_custom_place_name is null or private.valid_destination_evidence_text(p_custom_place_name, 160))
        and (p_provider_attribution is null or private.valid_destination_provider_attribution(p_provider_attribution))
        and (
            (p_kind = 'LOCALITY' and p_selection_source = 'LOCALITY_SELECTION'
             and p_latitude is null and p_longitude is null
             and p_boundary_dataset_id is null and p_boundary_dataset_version is null
             and p_provider is null and p_provider_place_id is null
             and p_provider_attribution is null and p_custom_place_name is null)
            or
            (p_kind = 'POINT' and p_latitude is not null and p_longitude is not null
             and p_latitude between -90 and 90 and p_longitude between -180 and 180
             and p_boundary_dataset_id = 'NG_OPERATIONAL_BOUNDARIES'
             and private.valid_destination_evidence_text(p_boundary_dataset_version, 120)
             and (
                (p_selection_source = 'PLACE_SEARCH' and p_provider is not null
                 and p_provider_place_id is not null and p_provider_attribution is not null
                 and p_custom_place_name is null)
                or
                (p_selection_source = 'MAP_PIN' and (
                    (p_provider is null and p_provider_place_id is null and p_provider_attribution is null)
                    or (p_provider = 'GEOAPIFY' and p_provider_attribution is not null)
                ))
             ))), false);
$$;

alter table public.journeys drop constraint journeys_structured_destination_consistent;
alter table public.journeys add constraint journeys_structured_destination_consistent check (
    (destination_country_code is null and destination_state_id is null and
     destination_state_name is null and destination_lga_id is null and
     destination_lga_name is null and destination_kind is null and
     destination_latitude is null and destination_longitude is null and
     destination_selection_source is null and destination_provider is null and
     destination_provider_place_id is null and destination_admin_dataset_version is null and
     destination_custom_place_name is null and destination_local_unit_type is null and
     destination_boundary_dataset_id is null and destination_boundary_dataset_version is null and
     destination_provider_attribution is null)
    or coalesce(
        destination_country_code = 'NG'
        and private.valid_destination_evidence_text(destination_state_id, 160)
        and private.valid_destination_evidence_text(destination_state_name, 160)
        and private.valid_destination_evidence_text(destination_lga_id, 160)
        and private.valid_destination_evidence_text(destination_lga_name, 160)
        and private.valid_destination_evidence_text(destination_admin_dataset_version, 120)
        and destination_local_unit_type in ('LGA', 'AREA_COUNCIL')
        and private.valid_destination_subtype_evidence(
            destination_kind, destination_selection_source, destination_latitude, destination_longitude,
            destination_provider, destination_provider_place_id, destination_provider_attribution,
            destination_custom_place_name, destination_boundary_dataset_id, destination_boundary_dataset_version), false)
);

create or replace function public.validate_journey_catalogue_destination_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
    v_classification jsonb;
begin
    -- The CHECK still rejects partial evidence masquerading as legacy text-only.
    if new.destination_kind is null then return new; end if;
    if new.destination_admin_dataset_version is null or not exists (
        select 1 from public.admin_catalogue_versions v
        where v.dataset_id = 'NG_ADMIN' and v.version = new.destination_admin_dataset_version
    ) then
        raise exception 'Unknown or missing destination catalogue version' using errcode = '22023';
    end if;
    if new.destination_country_code is distinct from 'NG' or not exists (
        select 1 from public.admin_regions r
        join public.admin_local_units u
          on u.dataset_id = r.dataset_id and u.version = r.version
         and u.parent_region_id = r.id and u.parent_region_type = r.region_type
        where r.dataset_id = 'NG_ADMIN' and r.version = new.destination_admin_dataset_version
          and r.id = new.destination_state_id and r.canonical_name = new.destination_state_name
          and u.id = new.destination_lga_id and u.canonical_name = new.destination_lga_name
    ) then
        raise exception 'Journey destination does not match catalogue snapshot' using errcode = '22023';
    end if;
    if not exists (
        select 1 from public.admin_local_units u
        where u.dataset_id = 'NG_ADMIN' and u.version = new.destination_admin_dataset_version
          and u.id = new.destination_lga_id and u.local_unit_type = new.destination_local_unit_type
    ) then
        raise exception 'Journey destination local-unit type does not match catalogue' using errcode = '22023';
    end if;
    if not private.valid_destination_subtype_evidence(
        new.destination_kind, new.destination_selection_source, new.destination_latitude, new.destination_longitude,
        new.destination_provider, new.destination_provider_place_id, new.destination_provider_attribution,
        new.destination_custom_place_name, new.destination_boundary_dataset_id, new.destination_boundary_dataset_version) then
        raise exception 'Invalid Journey destination subtype evidence' using errcode = '23514';
    end if;
    if new.destination_kind = 'POINT' then
        -- Never let NULL select today's boundary version. Require the claimed identity
        -- and explicitly classify using the frozen submitted version, without provider calls.
        v_classification := private.classify_destination_coordinate_v1(
            new.destination_state_id, new.destination_lga_id,
            new.destination_latitude, new.destination_longitude,
            new.destination_boundary_dataset_version);
        if v_classification->>'outcome' is distinct from 'INSIDE'
           or v_classification->>'boundary_dataset_id' is distinct from new.destination_boundary_dataset_id
           or v_classification->>'boundary_dataset_version' is distinct from new.destination_boundary_dataset_version then
            raise exception 'Journey point is not INSIDE the submitted boundary context' using errcode = '22023';
        end if;
    end if;
    return new;
end;
$$;

create or replace function public.preserve_immutable_journey_destination()
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
        new.destination_local_unit_type := coalesce(new.destination_local_unit_type, old.destination_local_unit_type);
        new.destination_boundary_dataset_id := coalesce(new.destination_boundary_dataset_id, old.destination_boundary_dataset_id);
        new.destination_boundary_dataset_version := coalesce(new.destination_boundary_dataset_version, old.destination_boundary_dataset_version);
        new.destination_provider_attribution := coalesce(new.destination_provider_attribution, old.destination_provider_attribution);
    end if;
    if new.destination is distinct from old.destination or
       row(new.destination_country_code, new.destination_state_id, new.destination_state_name,
           new.destination_lga_id, new.destination_lga_name, new.destination_kind,
           new.destination_latitude, new.destination_longitude, new.destination_selection_source,
           new.destination_provider, new.destination_provider_place_id, new.destination_admin_dataset_version,
           new.destination_custom_place_name, new.destination_local_unit_type, new.destination_boundary_dataset_id,
           new.destination_boundary_dataset_version, new.destination_provider_attribution)
       is distinct from
       row(old.destination_country_code, old.destination_state_id, old.destination_state_name,
           old.destination_lga_id, old.destination_lga_name, old.destination_kind,
           old.destination_latitude, old.destination_longitude, old.destination_selection_source,
           old.destination_provider, old.destination_provider_place_id, old.destination_admin_dataset_version,
           old.destination_custom_place_name, old.destination_local_unit_type, old.destination_boundary_dataset_id,
           old.destination_boundary_dataset_version, old.destination_provider_attribution) then
        raise exception 'Journey destination is immutable' using errcode = 'JD001';
    end if;
    return new;
end;
$$;

-- CHECK helpers are pure and must be executable for ordinary owner writes;
-- private schema/table access and the geographic classifier remain inaccessible.
revoke all on function private.valid_destination_evidence_text(text, integer),
    private.valid_destination_provider_attribution(jsonb),
    private.valid_destination_subtype_evidence(text,text,double precision,double precision,text,text,jsonb,text,text,text)
    from public, anon;
grant execute on function private.valid_destination_evidence_text(text, integer),
    private.valid_destination_provider_attribution(jsonb),
    private.valid_destination_subtype_evidence(text,text,double precision,double precision,text,text,jsonb,text,text,text)
    to authenticated, service_role;
revoke all on function public.validate_journey_catalogue_destination_insert(),
    public.preserve_immutable_journey_destination() from public, anon, authenticated, service_role;

commit;

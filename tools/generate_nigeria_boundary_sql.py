#!/usr/bin/env python3
"""Generate/check the PostGIS migration from the retained GRID3 snapshot.

The committed GeoJSON bytes and mapping.json are the only geometry/identity
inputs. Geometry objects are copied as source text, preserving coordinate
lexemes. ST_Multi changes type only; no simplification, buffer or repair.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from validate_nigeria_boundaries import SNAPSHOT, validate

MIGRATION = Path(__file__).resolve().parents[1] / "supabase/migrations/20261005000200_nigeria_operational_boundaries.sql"
DATASET = "NG_OPERATIONAL_BOUNDARIES"


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def geometry_texts(name, features):
    raw = (SNAPSHOT / name).read_bytes()
    geometries = re.findall(rb'"geometry"\s*:\s*(\{[^{}]*\})', raw)
    if len(geometries) != len(features):
        raise ValueError(f"{name}: geometry count does not match reviewed feature count")
    decoded = [blob.decode("utf-8", errors="strict") for blob in geometries]
    for feature, geometry in zip(features, decoded):
        if json.loads(geometry) != feature["geometry"]:
            raise ValueError(f"{name}: geometry order or bytes do not match parsed feature")
        if feature["geometry"]["type"] not in ("Polygon", "MultiPolygon"):
            raise ValueError(f"{name}: unexpected geometry type")
    return decoded


SCHEMA = """begin;
create schema if not exists gis;
create extension if not exists postgis with schema gis;
do $$ begin
    if (select n.nspname from pg_extension e join pg_namespace n on n.oid = e.extnamespace
        where e.extname = 'postgis') is distinct from 'gis' then
        raise exception 'PostGIS must be installed in the dedicated gis schema';
    end if;
end $$;
revoke all on schema gis from public, anon, authenticated, service_role;

-- Migration-managed operational geometry. No Data API role can read or write it.
create table private.boundary_dataset_versions (
    dataset_id text not null,
    version text not null,
    snapshot_date date not null,
    source_attribution text not null,
    source_license text not null,
    source_license_url text not null,
    source_lga_sha256 text not null check (source_lga_sha256 ~ '^[a-f0-9]{64}$'),
    source_state_sha256 text not null check (source_state_sha256 ~ '^[a-f0-9]{64}$'),
    mapping_sha256 text not null check (mapping_sha256 ~ '^[a-f0-9]{64}$'),
    expected_region_count integer not null check (expected_region_count > 0),
    expected_local_unit_count integer not null check (expected_local_unit_count > 0),
    primary key (dataset_id, version)
);
create table private.boundary_regions (
    dataset_id text not null,
    version text not null,
    region_id text not null,
    grid3_global_id uuid not null,
    grid3_unique_id bigint not null,
    grid3_state_code text not null,
    grid3_name text not null,
    geom gis.geometry(MultiPolygon,4326) not null,
    primary key (dataset_id, version, region_id),
    unique (dataset_id, version, grid3_global_id),
    foreign key (dataset_id, version) references private.boundary_dataset_versions(dataset_id, version) on delete restrict,
    check (not gis.ST_IsEmpty(geom) and gis.ST_IsValid(geom) and gis.ST_SRID(geom) = 4326)
);
create table private.boundary_local_units (
    dataset_id text not null,
    version text not null,
    local_unit_id text not null,
    region_id text not null,
    grid3_global_id uuid not null,
    grid3_unique_id bigint not null,
    grid3_lga_code text not null,
    grid3_name text not null,
    geom gis.geometry(MultiPolygon,4326) not null,
    primary key (dataset_id, version, local_unit_id),
    unique (dataset_id, version, grid3_global_id),
    foreign key (dataset_id, version, region_id) references private.boundary_regions(dataset_id, version, region_id) on delete restrict,
    check (not gis.ST_IsEmpty(geom) and gis.ST_IsValid(geom) and gis.ST_SRID(geom) = 4326)
);
create index boundary_regions_geom_gist on private.boundary_regions using gist (geom);
create index boundary_local_units_geom_gist on private.boundary_local_units using gist (geom);
alter table private.boundary_dataset_versions enable row level security;
alter table private.boundary_regions enable row level security;
alter table private.boundary_local_units enable row level security;
revoke all on private.boundary_dataset_versions, private.boundary_regions, private.boundary_local_units
    from public, anon, authenticated, service_role;

"""


CLASSIFIER = """
-- This is the sole geographic classifier. Direct client execution is revoked;
-- future server-side Journey admission can reuse it without a client session.
create function private.classify_destination_coordinate_v1(
    p_region_id text, p_local_unit_id text, p_latitude double precision,
    p_longitude double precision, p_boundary_version text default null
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
    v_admin_version text;
    v_version text;
    v_dataset private.boundary_dataset_versions%rowtype;
    v_region private.boundary_regions%rowtype;
    v_unit private.boundary_local_units%rowtype;
    v_point gis.geometry(Point,4326);
    v_outcome text;
begin
    if p_region_id is null or p_local_unit_id is null
       or p_latitude is null or p_longitude is null
       or p_latitude not between -90 and 90 or p_longitude not between -180 and 180 then
        return jsonb_build_object('outcome','INVALID_CONTEXT',
            'boundary_dataset_id',null,'boundary_dataset_version',null);
    end if;
    select version into v_admin_version from public.admin_catalogue_versions
      where dataset_id = 'NG_ADMIN' order by snapshot_date desc, version desc limit 1;
    if v_admin_version is null or not exists (
        select 1 from public.admin_regions r join public.admin_local_units u
          on u.dataset_id = r.dataset_id and u.version = r.version and u.parent_region_id = r.id
         where r.dataset_id = 'NG_ADMIN' and r.version = v_admin_version
           and r.id = p_region_id and u.id = p_local_unit_id
    ) then
        return jsonb_build_object('outcome','INVALID_CONTEXT',
            'boundary_dataset_id',null,'boundary_dataset_version',null);
    end if;
    select * into v_dataset from private.boundary_dataset_versions
      where dataset_id = 'NG_OPERATIONAL_BOUNDARIES'
        and version = coalesce(p_boundary_version, (
          select version from private.boundary_dataset_versions
          where dataset_id = 'NG_OPERATIONAL_BOUNDARIES'
          order by snapshot_date desc, version desc limit 1));
    if not found then
        return jsonb_build_object('outcome','UNAVAILABLE',
            'boundary_dataset_id',null,'boundary_dataset_version',null);
    end if;
    v_version := v_dataset.version;
    if (select count(*) from private.boundary_regions
        where dataset_id = v_dataset.dataset_id and version = v_version) <> v_dataset.expected_region_count
       or (select count(*) from private.boundary_local_units
        where dataset_id = v_dataset.dataset_id and version = v_version) <> v_dataset.expected_local_unit_count then
        return jsonb_build_object('outcome','UNAVAILABLE',
            'boundary_dataset_id',null,'boundary_dataset_version',null);
    end if;
    select * into v_region from private.boundary_regions
      where dataset_id = v_dataset.dataset_id and version = v_version and region_id = p_region_id;
    if not found then
        return jsonb_build_object('outcome','UNAVAILABLE',
            'boundary_dataset_id',null,'boundary_dataset_version',null);
    end if;
    select * into v_unit from private.boundary_local_units
      where dataset_id = v_dataset.dataset_id and version = v_version
        and region_id = p_region_id and local_unit_id = p_local_unit_id;
    if not found then
        return jsonb_build_object('outcome','UNAVAILABLE',
            'boundary_dataset_id',null,'boundary_dataset_version',null);
    end if;
    -- PostGIS points are longitude then latitude. Strict interior admits POINT.
    v_point := gis.ST_SetSRID(gis.ST_MakePoint(p_longitude,p_latitude),4326);
    if gis.ST_Contains(v_unit.geom,v_point) then
        v_outcome := 'INSIDE';
    elsif gis.ST_Covers(v_unit.geom,v_point) then
        v_outcome := 'BOUNDARY_AMBIGUOUS';
    elsif exists (
        select 1 from private.boundary_local_units u
         where u.dataset_id = v_dataset.dataset_id and u.version = v_version
           and u.region_id = p_region_id and u.local_unit_id <> p_local_unit_id
           and gis.ST_Covers(u.geom,v_point)
    ) then
        -- Another unit's interior OR boundary cannot establish the selected unit.
        v_outcome := 'OUTSIDE_SELECTED_UNIT';
    elsif gis.ST_Contains(v_region.geom,v_point) then
        -- Includes audited gaps such as developed land around Lagos Lagoon.
        v_outcome := 'COVERAGE_UNRESOLVED';
    elsif gis.ST_Covers(v_region.geom,v_point) then
        v_outcome := 'BOUNDARY_AMBIGUOUS';
    else
        v_outcome := 'OUTSIDE_SELECTED_UNIT';
    end if;
    return jsonb_build_object('outcome',v_outcome,
        'boundary_dataset_id',v_dataset.dataset_id,'boundary_dataset_version',v_version);
end;
$$;
revoke all on function private.classify_destination_coordinate_v1(text,text,double precision,double precision,text)
    from public, anon, authenticated, service_role;

-- Narrow Data API entrypoint; caller must be a verified Traveller with a profile.
create function public.verify_destination_coordinate_v1(
    p_region_id text, p_local_unit_id text, p_latitude double precision,
    p_longitude double precision
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner_id uuid := (select auth.uid());
begin
    if v_owner_id is null or not exists (
        select 1 from auth.users u join public.traveller_profiles p on p.owner_id = u.id
        where u.id = v_owner_id and u.is_anonymous is false
          and u.email is not null and btrim(u.email) <> ''
          and u.email_confirmed_at is not null
    ) then
        raise exception 'Authenticated Traveller profile required' using errcode = '42501';
    end if;
    return private.classify_destination_coordinate_v1(
        p_region_id,p_local_unit_id,p_latitude,p_longitude,null);
end;
$$;
revoke all on function public.verify_destination_coordinate_v1(text,text,double precision,double precision)
    from public, anon, authenticated, service_role;
grant execute on function public.verify_destination_coordinate_v1(text,text,double precision,double precision)
    to authenticated;
commit;
"""


def generate():
    sources, mapping, _ = validate()  # Verifies every committed source/mapping hash and ID.
    metadata = json.loads((SNAPSHOT / "metadata.json").read_bytes())
    version = metadata["version"]
    regions = {row["grid3GlobalId"]: row for row in mapping["regionMappings"]}
    units = {row["grid3GlobalId"]: row for row in mapping["localUnitMappings"]}
    state_features = sources["grid3_state.geojson"]
    unit_features = sources["grid3_lga.geojson"]
    state_geometry = geometry_texts("grid3_state.geojson", state_features)
    unit_geometry = geometry_texts("grid3_lga.geojson", unit_features)
    if len(regions) != 37 or len(units) != 774:
        raise ValueError("Mapping identity is not one-to-one")
    lines = [
        "-- GENERATED from data/boundaries/ng_operational_2026-10-03-v1/metadata.json,",
        "-- the exact committed GRID3 GeoJSON bytes, and mapping.json.",
        "-- No geometry simplification, buffer, assignment, or ST_MakeValid.",
        f"-- LGA SHA-256: {metadata['sourceLayers'][0]['sha256']}",
        f"-- State SHA-256: {metadata['sourceLayers'][1]['sha256']}",
        f"-- Mapping SHA-256: {metadata['mapping']['sha256']}",
        f"-- Boundary version: {version}; 37 regions, 774 local units.",
        SCHEMA.rstrip(),
        "insert into private.boundary_dataset_versions",
        " (dataset_id,version,snapshot_date,source_attribution,source_license,source_license_url,",
        "  source_lga_sha256,source_state_sha256,mapping_sha256,expected_region_count,expected_local_unit_count)",
        " values (" + ",".join(map(literal, [DATASET,version,metadata["snapshotDate"],
            metadata["attribution"],metadata["license"],metadata["licenseUrl"],
            metadata["sourceLayers"][0]["sha256"],metadata["sourceLayers"][1]["sha256"],
            metadata["mapping"]["sha256"]])) + ",37,774);",
    ]
    region_rows = []
    for feature, geometry in zip(state_features, state_geometry):
        p = feature["properties"]
        m = regions[p["globalid"]]
        vals = [DATASET, version, m["alabarinRegionId"], p["globalid"],
                p["uniq_id"], p["statecode"], p["statename"]]
        region_rows.append("(" + ",".join(map(literal, vals)) +
                           ",gis.ST_Multi(gis.ST_GeomFromGeoJSON(" + literal(geometry) +
                           "))::gis.geometry(MultiPolygon,4326))")
    lines += ["insert into private.boundary_regions",
              " (dataset_id,version,region_id,grid3_global_id,grid3_unique_id,grid3_state_code,grid3_name,geom)",
              " values\n " + ",\n ".join(region_rows) + ";"]
    unit_rows = []
    for feature, geometry in zip(unit_features, unit_geometry):
        p = feature["properties"]
        m = units[p["globalid"]]
        vals = [DATASET, version, m["alabarinLocalUnitId"], m["alabarinRegionId"],
                p["globalid"], p["uniq_id"], p["lgacode"], p["lganame"]]
        unit_rows.append("(" + ",".join(map(literal, vals)) +
                         ",gis.ST_Multi(gis.ST_GeomFromGeoJSON(" + literal(geometry) +
                         "))::gis.geometry(MultiPolygon,4326))")
    lines += ["insert into private.boundary_local_units",
              " (dataset_id,version,local_unit_id,region_id,grid3_global_id,grid3_unique_id,grid3_lga_code,grid3_name,geom)",
              " values\n " + ",\n ".join(unit_rows) + ";"]
    lines += ["""
do $$ begin
    if (select count(*) from private.boundary_regions where dataset_id = 'NG_OPERATIONAL_BOUNDARIES') <> 37
       or (select count(*) from private.boundary_local_units where dataset_id = 'NG_OPERATIONAL_BOUNDARIES') <> 774
       or exists (select 1 from private.boundary_local_units u join private.boundary_regions r
         on r.dataset_id=u.dataset_id and r.version=u.version and r.region_id=u.region_id
         where not gis.ST_CoveredBy(u.geom,r.geom)) then
        raise exception 'Reviewed boundary import failed count or State nesting validation';
    end if;
end $$;""", CLASSIFIER.strip()]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--validate", action="store_true")
    group.add_argument("--output", type=Path)
    group.add_argument("--check", type=Path)
    args = parser.parse_args()
    output = generate()
    if args.output:
        args.output.write_text(output, encoding="utf-8", newline="\n")
    if args.check and args.check.read_bytes() != output.encode("utf-8"):
        raise SystemExit("Generated boundary migration differs from committed SQL")
    print(f"PASS: {DATASET} alabarin-2026-10-03-v1; 37/774; {len(output.encode('utf-8'))} SQL bytes")


if __name__ == "__main__":
    main()

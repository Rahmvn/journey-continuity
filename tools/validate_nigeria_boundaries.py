"""Validate the reviewed GRID3 snapshot; optionally emit a local PostGIS audit script.

The source GeoJSON and mapping are immutable review inputs. This tool never
repairs, simplifies, downloads, or rewrites geometry.
"""

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "data/boundaries/ng_operational_2026-10-03-v1"
CATALOGUE = ROOT / "app/src/main/assets/catalogue/ng_admin_v1.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def exact_file(path, expected):
    data = path.read_bytes()
    require(len(data) == expected["bytes"], f"{path.name}: byte count changed")
    require(hashlib.sha256(data).hexdigest() == expected["sha256"], f"{path.name}: SHA-256 changed")
    return json.loads(data.decode("utf-8"))


def normalized_name(value):
    return " ".join(value.strip().casefold().split())


def check_coordinates(geometry, identity):
    require(geometry and geometry.get("type") in {"Polygon", "MultiPolygon"}, f"{identity}: wrong geometry type")
    polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
    require(bool(polygons), f"{identity}: empty geometry")
    for polygon in polygons:
        require(bool(polygon), f"{identity}: empty polygon")
        for ring in polygon:
            require(len(ring) >= 4 and ring[0] == ring[-1], f"{identity}: malformed ring")
            for position in ring:
                require(len(position) == 2, f"{identity}: unexpected coordinate dimension")
                lon, lat = position
                require(
                    isinstance(lon, (int, float)) and isinstance(lat, (int, float))
                    and math.isfinite(lon) and math.isfinite(lat)
                    and -180 <= lon <= 180 and -90 <= lat <= 90,
                    f"{identity}: invalid coordinate",
                )


def validate():
    metadata = json.loads((SNAPSHOT / "metadata.json").read_text(encoding="utf-8"))
    require(metadata["datasetId"] == "NG_OPERATIONAL_BOUNDARIES", "wrong boundary dataset")
    require(metadata["version"] == "alabarin-2026-10-03-v1", "wrong boundary version")
    require(hashlib.sha256(CATALOGUE.read_bytes()).hexdigest() == metadata["adminCatalogueSha256"], "admin catalogue changed")
    catalogue = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    require(catalogue["version"] == metadata["adminCatalogueVersion"], "admin catalogue version changed")
    mapping = exact_file(SNAPSHOT / metadata["mapping"]["file"], metadata["mapping"])
    require(mapping["boundaryVersion"] == metadata["version"], "mapping version changed")
    require(mapping["adminCatalogueVersion"] == catalogue["version"], "mapping catalogue version changed")
    sources = {}
    for layer in metadata["sourceLayers"]:
        source = exact_file(SNAPSHOT / layer["file"], layer)
        require(source["type"] == "FeatureCollection", f"{layer['file']}: not FeatureCollection")
        require(source["crs"]["properties"]["name"] == "EPSG:4326", f"{layer['file']}: wrong CRS")
        require(len(source["features"]) == layer["features"], f"{layer['file']}: feature count changed")
        for feature in source["features"]:
            check_coordinates(feature["geometry"], feature["properties"].get("globalid"))
        sources[layer["file"]] = source["features"]

    source_states = {f["properties"]["globalid"]: f for f in sources["grid3_state.geojson"]}
    source_lgas = {f["properties"]["globalid"]: f for f in sources["grid3_lga.geojson"]}
    require(len(source_states) == 37 and len(source_lgas) == 774, "duplicate or missing GRID3 globalid")
    regions = {r["id"]: r for r in catalogue["regions"]}
    units = {u["id"]: u for u in catalogue["localUnits"]}
    require(len(regions) == 37 and len(units) == 774, "admin catalogue count changed")

    region_rows = mapping["regionMappings"]
    require(len(region_rows) == 37, "region mapping count changed")
    require({r["grid3GlobalId"] for r in region_rows} == set(source_states), "region feature mapping incomplete")
    require({r["alabarinRegionId"] for r in region_rows} == set(regions), "region ID mapping incomplete")
    require(len({r["grid3StateCode"] for r in region_rows}) == 37, "duplicate GRID3 state code")
    state_code_to_region = {}
    for row in region_rows:
        props = source_states[row["grid3GlobalId"]]["properties"]
        for key, source_key in (("grid3UniqueId", "uniq_id"), ("grid3StateCode", "statecode"), ("grid3StateName", "statename")):
            require(row[key] == props[source_key], f"{row['alabarinRegionId']}: state source identity changed")
        canonical_region_name = regions[row["alabarinRegionId"]]["canonicalName"]
        require(
            normalized_name(props["statename"]) == normalized_name(canonical_region_name)
            or (row["alabarinRegionId"] == "ng-fct" and normalized_name(props["statename"]) == "fct"),
            f"{row['alabarinRegionId']}: State/FCT identity mismatch",
        )
        state_code_to_region[row["grid3StateCode"]] = row["alabarinRegionId"]

    rows = mapping["localUnitMappings"]
    require(len(rows) == 774, "local-unit mapping count changed")
    require({r["grid3GlobalId"] for r in rows} == set(source_lgas), "LGA feature mapping incomplete")
    require({r["alabarinLocalUnitId"] for r in rows} == set(units), "Alabarin local-unit mapping incomplete")
    require(len({r["grid3LgaCode"] for r in rows}) == 774, "duplicate GRID3 LGA code")
    counts = Counter()
    for row in rows:
        props = source_lgas[row["grid3GlobalId"]]["properties"]
        for key, source_key in (("grid3UniqueId", "uniq_id"), ("grid3LgaCode", "lgacode"), ("grid3StateCode", "statecode"), ("grid3StateName", "statename"), ("grid3LocalUnitName", "lganame")):
            require(row[key] == props[source_key], f"{row['alabarinLocalUnitId']}: LGA source identity changed")
        unit = units[row["alabarinLocalUnitId"]]
        require(row["alabarinRegionId"] == unit["parentRegionId"], f"{unit['id']}: wrong Alabarin parent")
        require(state_code_to_region[row["grid3StateCode"]] == unit["parentRegionId"], f"{unit['id']}: wrong GRID3 parent")
        if normalized_name(props["lganame"]) == normalized_name(unit["canonicalName"]):
            kind = "EXACT"
        elif normalized_name(props["lganame"]) in {normalized_name(a) for a in unit.get("aliases", [])}:
            kind = "CATALOGUE_ALIAS"
        else:
            kind = "NAME_DIFFERENCE"
        counts[kind] += 1
        if kind == "EXACT":
            require("review" not in row, f"{unit['id']}: unexpected review")
        else:
            review = row.get("review")
            require(review and review["matchKind"] == kind and review["alabarinCanonicalName"] == unit["canonicalName"] and review["category"], f"{unit['id']}: name difference lacks review")
        if unit["parentRegionId"] == "ng-fct":
            require(unit["type"] == "AREA_COUNCIL", f"{unit['id']}: FCT type incorrect")
        else:
            require(unit["type"] == "LGA", f"{unit['id']}: State type incorrect")
    require(counts == {"EXACT": 679, "CATALOGUE_ALIAS": 1, "NAME_DIFFERENCE": 94}, f"name review counts changed: {counts}")
    require(sum(u["parentRegionId"] == "ng-fct" for u in units.values()) == 6, "FCT count changed")
    require(sum(u["parentRegionId"] == "ng-kwara" for u in units.values()) == 16, "Kwara count changed")
    points = exact_file(SNAPSHOT / metadata["acceptancePoints"]["file"], metadata["acceptancePoints"])["points"]
    exact_file(SNAPSHOT / metadata["geometryAudit"]["file"], metadata["geometryAudit"])
    require(len(points) >= 6 and all(p["expectedLocalUnitId"] is None or p["expectedLocalUnitId"] in units for p in points), "invalid acceptance fixtures")
    print(f"PASS: {len(region_rows)} regions, {len(rows)} local units, 679 exact + 1 alias + 94 reviewed names")
    for layer in metadata["sourceLayers"]:
        print(f"{layer['file']}: {layer['sha256']} ({layer['bytes']} bytes)")
    print(f"mapping.json: {metadata['mapping']['sha256']} ({metadata['mapping']['bytes']} bytes)")
    return sources, mapping, points


def emit_postgis_sql(output, sources, mapping, points):
    """Emit a psql script for an isolated database with PostGIS already installed."""
    lga_by_globalid = {r["grid3GlobalId"]: r for r in mapping["localUnitMappings"]}
    state_by_globalid = {r["grid3GlobalId"]: r for r in mapping["regionMappings"]}
    lines = [
        "\\set ON_ERROR_STOP on", "BEGIN;",
        "CREATE TEMP TABLE boundary_lga_raw(fid integer, unit_id text, region_id text, state_code text, geom_json text);",
        "COPY boundary_lga_raw FROM STDIN;",
    ]
    for f in sources["grid3_lga.geojson"]:
        p = f["properties"]
        m = lga_by_globalid[p["globalid"]]
        lines.append("\t".join((str(p["FID"]), m["alabarinLocalUnitId"], m["alabarinRegionId"], p["statecode"], json.dumps(f["geometry"], separators=(",", ":")))))
    lines += ["\\.", "CREATE TEMP TABLE boundary_state_raw(fid integer, region_id text, state_code text, geom_json text);", "COPY boundary_state_raw FROM STDIN;"]
    for f in sources["grid3_state.geojson"]:
        p = f["properties"]
        m = state_by_globalid[p["globalid"]]
        lines.append("\t".join((str(p["FID"]), m["alabarinRegionId"], p["statecode"], json.dumps(f["geometry"], separators=(",", ":")))))
    lines += [
        "\\.",
        "CREATE TEMP TABLE boundary_lga AS SELECT fid,unit_id,region_id,state_code,ST_Multi(ST_GeomFromGeoJSON(geom_json))::geometry(MultiPolygon,4326) geom FROM boundary_lga_raw;",
        "CREATE TEMP TABLE boundary_state AS SELECT fid,region_id,state_code,ST_Multi(ST_GeomFromGeoJSON(geom_json))::geometry(MultiPolygon,4326) geom FROM boundary_state_raw;",
        "CREATE INDEX ON boundary_lga USING gist(geom);",
        "CREATE INDEX ON boundary_state USING gist(geom);",
        "SELECT 'invalid_lga',unit_id,ST_IsValidReason(geom) FROM boundary_lga WHERE NOT ST_IsValid(geom) OR ST_IsEmpty(geom);",
        "SELECT 'invalid_state',region_id,ST_IsValidReason(geom) FROM boundary_state WHERE NOT ST_IsValid(geom) OR ST_IsEmpty(geom);",
        "SELECT 'geometry_summary',count(*),count(*) FILTER(WHERE ST_NumGeometries(geom)>1),sum(ST_NRings(geom)-ST_NumGeometries(geom)) FROM boundary_lga;",
        "SELECT 'parent_outside',l.unit_id,ST_Area(ST_Difference(l.geom,s.geom)::geography)/1000000 FROM boundary_lga l JOIN boundary_state s ON l.region_id=s.region_id WHERE NOT ST_CoveredBy(l.geom,s.geom);",
        "SELECT 'sibling_overlap',a.unit_id,b.unit_id,ST_Area(ST_Intersection(a.geom,b.geom)::geography)/1000000 FROM boundary_lga a JOIN boundary_lga b ON a.fid<b.fid AND a.region_id=b.region_id AND a.geom && b.geom WHERE ST_Intersects(a.geom,b.geom) AND ST_Area(ST_Intersection(a.geom,b.geom)::geography)>0;",
        "WITH u AS (SELECT region_id,ST_UnaryUnion(ST_Collect(geom)) geom FROM boundary_lga GROUP BY region_id) SELECT 'state_gap',s.region_id,ST_Area(ST_Difference(s.geom,u.geom)::geography)/1000000 FROM boundary_state s JOIN u USING(region_id) WHERE NOT ST_CoveredBy(s.geom,u.geom);",
        "SELECT 'duplicate_geometry',a.unit_id,b.unit_id FROM boundary_lga a JOIN boundary_lga b ON a.fid<b.fid AND a.geom && b.geom WHERE ST_Equals(a.geom,b.geom);",
        "DO $$ BEGIN",
        "IF EXISTS (SELECT 1 FROM boundary_lga WHERE NOT ST_IsValid(geom) OR ST_IsEmpty(geom)) OR EXISTS (SELECT 1 FROM boundary_state WHERE NOT ST_IsValid(geom) OR ST_IsEmpty(geom)) THEN RAISE EXCEPTION 'invalid or empty GRID3 geometry'; END IF;",
        "IF EXISTS (SELECT 1 FROM boundary_lga l JOIN boundary_state s ON l.region_id=s.region_id WHERE NOT ST_CoveredBy(l.geom,s.geom)) THEN RAISE EXCEPTION 'local unit extends beyond mapped state'; END IF;",
        "IF EXISTS (SELECT 1 FROM boundary_lga a JOIN boundary_lga b ON a.fid<b.fid AND a.region_id=b.region_id AND a.geom && b.geom WHERE ST_Intersects(a.geom,b.geom) AND ST_Area(ST_Intersection(a.geom,b.geom)::geography)>0) THEN RAISE EXCEPTION 'positive-area sibling overlap'; END IF;",
        "IF EXISTS (SELECT 1 FROM boundary_lga a JOIN boundary_lga b ON a.fid<b.fid AND a.geom && b.geom WHERE ST_Equals(a.geom,b.geom)) THEN RAISE EXCEPTION 'duplicate source geometry'; END IF;",
        "END $$;",
    ]
    for point in points:
        lon, lat = point["longitude"], point["latitude"]
        label = point["id"].replace("'", "''")
        coordinate = f"ST_SetSRID(ST_MakePoint({lon},{lat}),4326)"
        lines.append(f"SELECT 'acceptance:{label}',unit_id,ST_Contains(geom,{coordinate}),ST_Covers(geom,{coordinate}) FROM boundary_lga WHERE ST_Covers(geom,{coordinate});")
        if point["expectedLocalUnitId"]:
            unit = point["expectedLocalUnitId"].replace("'", "''")
            lines.append(f"DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM boundary_lga WHERE unit_id='{unit}' AND ST_Contains(geom,{coordinate})) THEN RAISE EXCEPTION 'acceptance point {label} is not strictly inside {unit}'; END IF; END $$;")
        else:
            expected = point["boundaryUnitIds"]
            require(len(expected) == 2, f"{point['id']}: expected two boundary units")
            unit_a, unit_b = (unit.replace("'", "''") for unit in expected)
            lines.append(f"DO $$ BEGIN IF (SELECT count(*) FROM boundary_lga WHERE ST_Covers(geom,{coordinate}))<>2 OR EXISTS (SELECT 1 FROM boundary_lga WHERE ST_Contains(geom,{coordinate})) OR NOT EXISTS (SELECT 1 FROM boundary_lga WHERE unit_id='{unit_a}' AND ST_Covers(geom,{coordinate})) OR NOT EXISTS (SELECT 1 FROM boundary_lga WHERE unit_id='{unit_b}' AND ST_Covers(geom,{coordinate})) THEN RAISE EXCEPTION 'acceptance point {label} is not an ambiguous shared border'; END IF; END $$;")
    lines.append("ROLLBACK;")
    output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"PostGIS audit script: {output} ({output.stat().st_size} bytes)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emit-postgis-sql", type=Path, metavar="PATH", help="write a temporary, transaction-scoped psql audit script")
    args = parser.parse_args()
    source_data, identity_mapping, acceptance = validate()
    if args.emit_postgis_sql:
        emit_postgis_sql(args.emit_postgis_sql, source_data, identity_mapping, acceptance)

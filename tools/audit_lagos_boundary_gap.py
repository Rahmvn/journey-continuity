#!/usr/bin/env python3
"""Reproduce the Lagos review using the retained GRID3 bytes and isolated PostGIS.

Requires Docker and a disposable database with PostGIS already enabled. No shared
database reset, geometry repair or production admission is performed. Optional
--worldcover reads ESA's immutable 2021 COG via rasterio (tested with 1.5.2), a
development-only dependency; it is not an Android dependency. Pixel statistics
are corroborating land-cover evidence, not legal boundaries or surveyed areas.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from validate_nigeria_boundaries import validate, emit_postgis_sql, SNAPSHOT

WORLD_COVER = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_N06E003_Map.tif"
SAMPLES = [
    ("lagoon_centroid", 3.481434384, 6.517528177),
    ("built_up_shore_1", 3.396625, 6.463625),
    ("built_up_shore_2", 3.3954583333333335, 6.487208333333334),
    ("built_up_shore_3", 3.490875, 6.449541666666668),
]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8", newline="\n")


def worldcover(geometry):
    import numpy as np
    import rasterio
    from rasterio.features import geometry_mask
    from rasterio.windows import from_bounds
    with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR",
                      CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif"):
        with rasterio.open(WORLD_COVER) as src:
            window = from_bounds(3.379, 6.447, 3.633, 6.608, src.transform).round_offsets().round_lengths()
            pixels = src.read(1, window=window)
            transform = src.window_transform(window)
            mask = geometry_mask([geometry], pixels.shape, transform, invert=True)
            values, counts = np.unique(pixels[mask], return_counts=True)
            labels = {10: "tree_cover", 20: "shrubland", 30: "grassland", 40: "cropland",
                      50: "built_up", 60: "bare_sparse_vegetation", 80: "permanent_water",
                      90: "herbaceous_wetland", 95: "mangroves"}
            total = int(counts.sum())
            return {
                "sourceUrl": WORLD_COVER,
                "documentationUrl": "https://esa-worldcover.org/en/data-access",
                "citation": "ESA WorldCover 2021 v200, doi:10.5281/zenodo.7254221; CC BY 4.0",
                "method": "Native 10m raster window; rasterio geometry_mask default pixel-centre selection; no resampling",
                "caveat": "Classification is not surveyed land truth; pixel proportions are not geodesic area fractions. Published global overall accuracy: 76.7%.",
                "window": list(window.flatten()), "transform": list(transform), "crs": str(src.crs),
                "pixelCount": total,
                "classes": [{"code": int(v), "name": labels.get(int(v), "other"),
                             "pixelCount": int(c), "pixelFraction": int(c) / total}
                            for v, c in zip(values, counts)],
                "samples": [{"id": label, "longitude": x, "latitude": y,
                             "classCode": int(next(src.sample([(x, y)]))[0])}
                            for label, x, y in SAMPLES],
            }


def overview_svg(geometry):
    # Original geometry coordinates; fixed viewport, no basemap redistribution.
    x0, y0, x1, y1 = 3.36, 6.43, 3.65, 6.625
    def xy(x, y):
        return (30 + (x - x0) / (x1 - x0) * 940, 45 + (y1 - y) / (y1 - y0) * 640)
    paths = []
    for ring in geometry["coordinates"]:
        paths.append("M " + " L ".join(f"{x:.4f},{y:.4f}" for x, y in (xy(*p) for p in ring)) + " Z")
    circles = []
    for label, lon, lat in SAMPLES:
        x, y = xy(lon, lat)
        circles.append(f'<circle cx="{x:.4f}" cy="{y:.4f}" r="4" fill="#111"/><text x="{x+7:.4f}" y="{y:.4f}" font-size="12">{label}</text>')
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="730" viewBox="0 0 1000 730">'
            '<rect width="1000" height="730" fill="white"/>'
            '<text x="30" y="25" font-size="18">Lagos State minus mapped local-unit union: 209.526282 km²</text>'
            f'<path d="{" ".join(paths)}" fill="#fbe6e6" fill-rule="evenodd" stroke="#bb2222"/>'
            + "".join(circles) +
            '<text x="30" y="715" font-size="12">EPSG:4326; GRID3 / eHealth Africa / Proxy Logics, CC BY 4.0. Alabarin derived review; not legal boundaries.</text></svg>\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="supabase_db_journey-continuity-m5-local")
    parser.add_argument("--database", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--worldcover", action="store_true")
    args = parser.parse_args()
    if not args.database.startswith("alabarin_") or args.database in ("postgres", "template0", "template1"):
        raise ValueError("Use a separately created alabarin_* audit database")
    if args.output.resolve() == SNAPSHOT.resolve() or SNAPSHOT.resolve() in args.output.resolve().parents:
        raise ValueError("Do not overwrite the retained boundary snapshot")
    sources, mapping, points = validate()
    with tempfile.TemporaryDirectory(prefix="alabarin-lagos-") as directory:
        script = Path(directory) / "audit.sql"
        emit_postgis_sql(script, sources, mapping, points)
        sql = script.read_text(encoding="utf-8")
    extra = """
CREATE TEMP TABLE lagos_gap AS
 SELECT ST_Difference(s.geom,u.geom) geom FROM boundary_state s CROSS JOIN
 (SELECT ST_UnaryUnion(ST_Collect(geom)) geom FROM boundary_lga WHERE region_id='ng-lagos') u
 WHERE s.region_id='ng-lagos';
SELECT 'DETAIL',json_build_object('geometryType',ST_GeometryType(geom),'componentCount',ST_NumGeometries(geom),
 'areaKm2',ST_Area(geom::geography)/1e6,'bbox',ST_Extent(geom)::text,
 'centroid',ST_AsGeoJSON(ST_Centroid(geom))::json,'surfacePoint',ST_AsGeoJSON(ST_PointOnSurface(geom))::json,
 'holes',ST_NRings(geom)-ST_NumGeometries(geom)) FROM lagos_gap GROUP BY geom;
SELECT 'BORDER',json_build_object('localUnitId',unit_id,'sharedBorderKm',
 ST_Length(ST_Intersection(ST_Boundary(l.geom),ST_Boundary(g.geom))::geography)/1000)
 FROM boundary_lga l,lagos_gap g WHERE l.region_id='ng-lagos' AND ST_Intersects(l.geom,g.geom) ORDER BY unit_id;
SELECT 'GEOMETRY',ST_AsGeoJSON(geom,15) FROM lagos_gap;
"""
    for label, lon, lat in SAMPLES:
        extra += f"""SELECT 'SAMPLE',json_build_object('id','{label}','longitude',{lon},'latitude',{lat},
 'strictlyInsideDifference',ST_Contains(g.geom,p.geom),'coveringLocalUnits',
 (SELECT coalesce(json_agg(unit_id ORDER BY unit_id),'[]'::json) FROM boundary_lga WHERE ST_Covers(geom,p.geom)))
 FROM lagos_gap g CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint({lon},{lat}),4326) geom) p;\n"""
    sql = sql.replace("ROLLBACK;", extra + "ROLLBACK;")
    result = subprocess.run(["docker", "exec", "-i", args.container, "psql", "-U", "postgres",
                             "-d", args.database, "-At", "-v", "ON_ERROR_STOP=1"],
                            input=sql.encode("utf-8"), capture_output=True, check=True)
    rows = {}
    for line in result.stdout.decode("utf-8").splitlines():
        tag, _, value = line.partition("|")
        if tag in ("DETAIL", "BORDER", "GEOMETRY", "SAMPLE"):
            rows.setdefault(tag, []).append(json.loads(value))
    geometry = rows["GEOMETRY"][0]
    document = {
        "reviewDate": "2026-10-05", "boundaryVersion": "alabarin-2026-10-03-v1",
        "sourceHashes": {name: hashlib.sha256((SNAPSHOT / name).read_bytes()).hexdigest()
                         for name in ("grid3_lga.geojson", "grid3_state.geojson", "mapping.json")},
        "derivation": "ST_Difference(Lagos State, ST_UnaryUnion(ST_Collect(mapped Lagos local-unit geometries))); no repair, simplification or assignment",
        "postgis": rows["DETAIL"][0], "borderingLocalUnits": rows["BORDER"], "samples": rows["SAMPLE"],
        "classification": "MATERIAL_LAND_GAP",
        "futureVerifierState": "COVERAGE_UNRESOLVED",
        "interpretation": "Mostly Lagos Lagoon water; independent land-cover and imagery review also identifies material built-up/shore/reclaimed land within the uncovered polygon. Not a water-only discrepancy. Cause/administrative assignment is not established.",
        "sourceMetadataFinding": "State abstract: September 2020; LGA abstract: March 2021. Both operational. No inspected source metadata explicitly explains the Lagos omission or guarantees State/LGA tessellation.",
        "snapshotAssessment": "This coverage finding does not invalidate the reviewed GRID3 operational snapshot.",
        "admissionEffect": "Blocked pending reviewed boundary-source resolution or explicit product scope decision. A coordinate inside the State/FCT but inside no mapped local-unit polygon remains COVERAGE_UNRESOLVED. Never infer local unit from State-only containment; no gap filling, assignment, extension, repair or permissive buffer.",
        "imageryReview": {
            "serviceUrl": "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer",
            "attribution": "Esri, Vantor, Earthstar Geographics, GIS User Community",
            "method": "Export f=json then use returned extent for overlay; inspect named sample coordinates. Imagery is reviewed externally, not redistributed here.",
            "overviewBbox": [3.36, 6.43, 3.65, 6.625],
            "landSampleBbox": [3.39, 6.458, 3.405, 6.472],
            "observations": "built_up_shore_1 visibly contains roads, structures and developed shoreline on the gap side of the GRID3 border. Lagoon islands/reclamation also visible; does not establish legal LGA ownership.",
        },
        "referenceUrls": [
            "https://grid3.org/geospatial-data-nigeria",
            "https://grid3.org/content/uploads/2020/09/GRID3_BoundariesWhitePaper_Finalized.pdf",
            "https://lasbca.lagosstate.gov.ng/wp-content/uploads/2021/07/LCMP-UTM_Minna_31N-Geo-2-Overall-Landuse-A0-Sc-25.000-2.pdf",
            "https://esa-worldcover.org/en/data-access",
        ],
    }
    if args.worldcover:
        document["worldcover"] = worldcover(geometry)
    args.output.mkdir(parents=True, exist_ok=True)
    # A single line has no line endings for Git to convert on Windows; its
    # recorded exact-byte hash remains stable after checkout.
    (args.output / "lagos_gap.geojson").write_text(
        json.dumps(geometry, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8", newline="\n",
    )
    document["derivedGeometrySha256"] = hashlib.sha256((args.output / "lagos_gap.geojson").read_bytes()).hexdigest()
    write_json(args.output / "lagos_gap_review.json", document)
    (args.output / "lagos_gap_overview.svg").write_text(overview_svg(geometry), encoding="utf-8", newline="\n")
    print(json.dumps(document["postgis"]))
    print("MATERIAL_LAND_GAP: admission remains blocked; no source geometry changed")


if __name__ == "__main__":
    main()

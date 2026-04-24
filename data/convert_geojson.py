"""
Convert inpa_reserves.geojson (from ~/proj/inpa-boundaries) to the compact
reserves.json asset bundled with the Android app.

Input GeoJSON is already WGS84 (CRS84), so no reprojection is needed.
Handles both Polygon and MultiPolygon geometries by flattening all rings;
point-in-polygon uses odd-even ring count (same as ReserveIndex.kt).
"""
import json
import os
import sys

SRC = os.path.expanduser("~/proj/inpa-boundaries/data/inpa_reserves.geojson")
OUT = "reserves.json"

IN_FORCE_STATUSES = {
    "מוכרז",
    "מאושר",
    'מוכרז יו"ש',
    'מוכרז יו"ש עם צו אלוף',
    'מאושר יו"ש',
    'מאושר יו"ש עם צו אלוף',
}

with open(SRC, encoding="utf-8") as f:
    data = json.load(f)

features_out = []
kept = 0
dropped = 0

for feat in data["features"]:
    props = feat["properties"]
    status = props.get("status", "")
    if status not in IN_FORCE_STATUSES:
        dropped += 1
        continue

    geom = feat["geometry"]
    geom_type = geom["type"]
    coords = geom["coordinates"]

    # Collect all rings regardless of Polygon vs MultiPolygon
    if geom_type == "Polygon":
        all_ring_coords = coords           # [[pt, pt, ...], ...]
    elif geom_type == "MultiPolygon":
        all_ring_coords = [ring for polygon in coords for ring in polygon]
    else:
        dropped += 1
        continue

    rings = []
    min_lon = min_lat = float("inf")
    max_lon = max_lat = float("-inf")

    for ring_pts in all_ring_coords:
        if len(ring_pts) < 4:
            continue
        out_ring = []
        for lon, lat in ring_pts:
            lon_r = round(lon, 5)
            lat_r = round(lat, 5)
            out_ring.append([lon_r, lat_r])
            if lon < min_lon: min_lon = lon
            if lat < min_lat: min_lat = lat
            if lon > max_lon: max_lon = lon
            if lat > max_lat: max_lat = lat
        rings.append(out_ring)

    if not rings:
        dropped += 1
        continue

    features_out.append({
        "name": (props.get("name") or "").strip(),
        "nameEn": (props.get("nameEn") or "").strip(),
        "type": props.get("type", "reserve"),
        "bbox": [round(min_lon, 5), round(min_lat, 5), round(max_lon, 5), round(max_lat, 5)],
        "rings": rings,
    })
    kept += 1

out = {"features": features_out}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

size = os.path.getsize(OUT)
print(f"kept={kept} dropped={dropped} features; output {OUT} ({size / 1024:.0f} KB)")
for feat in features_out[:3]:
    print(f"  - {feat['nameEn']:35s} type={feat['type']:7s} rings={len(feat['rings'])} bbox={feat['bbox']}")

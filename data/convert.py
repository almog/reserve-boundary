"""
Convert the INPA shapefile to a compact GeoJSON-ish asset bundled with the app.

- Filters to in-force statuses (declared / approved, plus West Bank equivalents with a
  commander's order). Excludes proposed (מוצע) and deposited-for-review (מופקד).
- Reprojects from Israel TM Grid (EPSG:2039-ish, per the .prj) to WGS84 (EPSG:4326).
- Emits a slim JSON: {"features": [{"name","nameEn","type","bbox","rings":[[[lon,lat],...]]}]}
  where rings are flattened (outer + holes) and point-in-polygon uses odd-even count.
- Coordinates rounded to 5 decimals (~1m), which is well within phone GPS accuracy.
"""
import json
import shapefile
from pyproj import CRS, Transformer

SRC = "extracted/respark_meforat_14-10-2020"
OUT = "reserves.json"

IN_FORCE_STATUSES = {
    "מוכרז",
    "מאושר",
    "מוכרז יו\"ש",
    "מוכרז יו\"ש עם צו אלוף",
    "מאושר יו\"ש",
    "מאושר יו\"ש עם צו אלוף",
}

# Read projection from .prj
with open(SRC + ".prj") as f:
    prj_wkt = f.read()
src_crs = CRS.from_wkt(prj_wkt)
dst_crs = CRS.from_epsg(4326)
transformer = Transformer.from_crs(src_crs, dst_crs, always_xy=True)

sf = shapefile.Reader(SRC + ".shp", encoding="utf-8")

features = []
kept = 0
dropped = 0
for sr in sf.shapeRecords():
    rec = sr.record
    status = rec["STATUS_DES"]
    if status not in IN_FORCE_STATUSES:
        dropped += 1
        continue
    shp = sr.shape
    if not shp.points:
        dropped += 1
        continue

    # pyshp gives a flat points list + parts indices for ring starts
    parts = list(shp.parts) + [len(shp.points)]
    rings = []
    min_lon = min_lat = float("inf")
    max_lon = max_lat = float("-inf")
    for i in range(len(parts) - 1):
        ring_pts = shp.points[parts[i] : parts[i + 1]]
        if len(ring_pts) < 4:
            continue
        out_ring = []
        for x, y in ring_pts:
            lon, lat = transformer.transform(x, y)
            out_ring.append([round(lon, 5), round(lat, 5)])
            if lon < min_lon: min_lon = lon
            if lat < min_lat: min_lat = lat
            if lon > max_lon: max_lon = lon
            if lat > max_lat: max_lat = lat
        rings.append(out_ring)

    if not rings:
        dropped += 1
        continue

    park_type = rec["PARK_TYPE_"]  # "שמורה" or "גן"
    type_code = "reserve" if park_type == "שמורה" else "park"

    name_he = (rec["PARK_HEB_N"] or "").strip()
    name_en = (rec["PARK_ENG_N"] or "").strip()

    features.append({
        "name": name_he,
        "nameEn": name_en,
        "type": type_code,
        "bbox": [round(min_lon, 5), round(min_lat, 5), round(max_lon, 5), round(max_lat, 5)],
        "rings": rings,
    })
    kept += 1

out = {"features": features}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

import os
size = os.path.getsize(OUT)
print(f"kept={kept} dropped={dropped} polygons; output {OUT} ({size/1024:.0f} KB)")

# Quick sanity: print a few features
for feat in features[:3]:
    print(f"  - {feat['nameEn']:30s} type={feat['type']:7s} rings={len(feat['rings'])} bbox={feat['bbox']}")

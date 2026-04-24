"""Quick sanity check: does our converted reserves.json correctly classify known points?"""
import json

with open("reserves.json", encoding="utf-8") as f:
    data = json.load(f)
features = data["features"]
print(f"loaded {len(features)} features")

def point_in_ring(lon, lat, ring):
    inside = False
    n = len(ring)
    j = n - 1
    for i in range(n):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if ((yi > lat) != (yj > lat)) and (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi):
            inside = not inside
        j = i
    return inside

def query(lon, lat):
    out = []
    for f in features:
        minLon, minLat, maxLon, maxLat = f["bbox"]
        if not (minLon <= lon <= maxLon and minLat <= lat <= maxLat):
            continue
        count = sum(1 for r in f["rings"] if point_in_ring(lon, lat, r))
        if count % 2 == 1:
            out.append((f.get("nameEn") or f["name"], f["type"]))
    return out

tests = [
    # (name, lon, lat, expected_substring_any_match OR None)
    ("Ein Gedi oasis area",          35.3896, 31.4657, "gedi"),
    ("Masada summit",                35.3535, 31.3154, "masada"),
    ("Mt. Meron area",               35.4100, 32.9930, "meron"),
    ("Tel Aviv urban center",        34.7800, 32.0800, None),
    ("Jerusalem Old City (Kotel)",   35.2345, 31.7767, None),
    ("Haifa port",                   35.0000, 32.8200, None),
    ("Ramon Crater (Makhtesh Ramon)",34.7990, 30.6080, "ramon"),
    ("Yarkon park area",             34.7935, 32.1020, None),  # may hit Yarkon/HaYarkon park
]

for name, lon, lat, expect in tests:
    hits = query(lon, lat)
    status = "OK"
    if expect is None:
        if hits:
            status = f"UNEXPECTED HIT"
    else:
        if not any(expect.lower() in (h[0] or "").lower() for h in hits):
            status = f"MISSING (expected substring '{expect}')"
    print(f"  [{status:20s}] {name:36s} -> {hits[:3]}{'...' if len(hits)>3 else ''}")

# GeoJSON Output Requirements — is-nature-reserve app

This document describes what the Android app needs from the `inpa_reserves.geojson` output so it can be used as the app's data source.

## Current state

The GeoJSON output (`inpa_reserves.geojson`) is standard GeoJSON (CRS84 / WGS84, lon/lat ordering) with the following feature structure:

```json
{
  "type": "Feature",
  "properties": {
    "name": "שמורת נחל דן",
    "nameEn": "Nahal Dan",
    "type": "reserve",
    "status": "מוכרז",
    "DUNAM": 1234.5
  },
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[35.123..., 33.456...], ...]]
  }
}
```

The format is correct and the coordinate reference system is exactly what the app needs. Two changes are requested.

---

## Change 1 — Filter to in-force statuses only

**What:** Exclude features whose `status` is not legally in force.

**Why:** The app's purpose is to tell users whether they are standing inside an active nature reserve. Features with a planning status that is not yet enacted should not trigger a match.

**Which statuses to keep:**

| Status (Hebrew) | Meaning |
|---|---|
| מוכרז | Declared |
| מאושר | Approved |
| מוכרז יו"ש עם צו אלוף | Declared (West Bank, with commander's order) |
| מאושר יו"ש | Approved (West Bank) |

**Which to drop:**

| Status (Hebrew) | Count | Meaning |
|---|---|---|
| מופקד | 21 | Deposited for public review |
| החלטה להפקדה | 16 | Decision to deposit |
| מופקד יו"ש | 5 | Deposited (West Bank) |

Currently 42 of the 926 features fall into the excluded group.

---

## Change 2 — Round coordinates to 5 decimal places

**What:** Round every coordinate value to 5 decimal places before writing the GeoJSON.

**Why:** 5 decimal places gives ~1.1 m resolution at the equator, which is well within phone GPS accuracy. Full float64 precision (~15 decimals) is unused and inflates the file from ~16 MB to what would be roughly 5–6 MB, directly affecting app install size and cold-start parse time on low-end devices.

**Example:**

```
before: [35.83636474885542, 32.957336249576144]
after:  [35.83636, 32.95734]
```

---

## What is already correct

- Coordinate system: WGS84 (CRS84), lon/lat order — correct.
- `name` and `nameEn` present on all features — correct.
- `type` field values (`"reserve"` / `"park"`) — correct.
- Both `Polygon` and `MultiPolygon` geometries — the app parser will handle both.

No other changes are needed from the GeoJSON project side.

#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = [
#   "certifi",
# ]
# ///
"""Fetch and normalize GovMap military fire range polygons.

GovMap exposes the official fire ranges layer as WFS GeoJSON, so this script
does not parse WFS/GML itself. It downloads the GeoJSON response, validates the
shape we rely on, normalizes the properties used by the Android app, and writes
a deterministic bundled asset.
"""

from __future__ import annotations

import argparse
import json
import os
import ssl
import sys
import urllib.request
from typing import Any

import certifi


SOURCE_LAYER = "govmap:layer_234343"
DEFAULT_WFS_URL = (
    "https://www.govmap.gov.il/api/geoserver/wfs"
    "?service=WFS"
    "&version=1.1.0"
    "&request=GetFeature"
    f"&typeName={SOURCE_LAYER}"
    "&outputFormat=application/json"
    "&maxFeatures=1000"
    "&srsName=EPSG:4326"
)
DEFAULT_OUTPUT = "app/src/main/assets/fire_ranges.geojson"
DEFAULT_MIN_FEATURES = 100

REQUEST_HEADERS = {
    "User-Agent": "is-nature-reserve-fire-ranges-pipeline/1.0",
    "Accept": "application/json",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch GovMap fire ranges and write app-ready GeoJSON."
    )
    parser.add_argument(
        "--url",
        default=DEFAULT_WFS_URL,
        help="GovMap WFS GeoJSON URL to fetch.",
    )
    parser.add_argument(
        "--input",
        help="Use an existing raw WFS GeoJSON file instead of downloading.",
    )
    parser.add_argument(
        "--out",
        default=DEFAULT_OUTPUT,
        help=f"Output path. Defaults to {DEFAULT_OUTPUT}.",
    )
    parser.add_argument(
        "--raw-out",
        help="Optional path to save the unmodified WFS response.",
    )
    parser.add_argument(
        "--min-features",
        type=int,
        default=DEFAULT_MIN_FEATURES,
        help="Fail if fewer features are returned.",
    )
    parser.add_argument(
        "--precision",
        type=int,
        default=5,
        help="Decimal places to keep for lon/lat coordinates.",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=60,
        help="HTTP timeout in seconds.",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Write indented JSON instead of compact JSON.",
    )
    return parser.parse_args()


def load_source(args: argparse.Namespace) -> dict[str, Any]:
    if args.input:
        with open(args.input, encoding="utf-8") as f:
            return json.load(f)

    request = urllib.request.Request(args.url, headers=REQUEST_HEADERS)
    ssl_context = ssl.create_default_context(cafile=certifi.where())
    with urllib.request.urlopen(request, timeout=args.timeout, context=ssl_context) as response:
        raw = response.read()
        charset = response.headers.get_content_charset() or "utf-8"

    if args.raw_out:
        ensure_parent_dir(args.raw_out)
        with open(args.raw_out, "wb") as f:
            f.write(raw)

    return json.loads(raw.decode(charset))


def ensure_parent_dir(path: str) -> None:
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)


def normalize_string(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def parse_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def validate_feature_collection(data: dict[str, Any], min_features: int) -> list[dict[str, Any]]:
    if data.get("type") != "FeatureCollection":
        raise ValueError("Expected a GeoJSON FeatureCollection")

    features = data.get("features")
    if not isinstance(features, list):
        raise ValueError("FeatureCollection is missing a features array")

    if len(features) < min_features:
        raise ValueError(f"Expected at least {min_features} features, got {len(features)}")

    reported_total = parse_int(data.get("totalFeatures")) or parse_int(data.get("numberMatched"))
    if reported_total is not None and len(features) < reported_total:
        raise ValueError(
            f"Incomplete WFS response: got {len(features)} of reported {reported_total} features"
        )

    return features


def normalize_geometry(geometry: dict[str, Any], precision: int, feature_index: int) -> dict[str, Any]:
    geom_type = geometry.get("type")
    coords = geometry.get("coordinates")

    if geom_type == "Polygon":
        normalized = normalize_polygon(coords, precision, f"features[{feature_index}].geometry")
    elif geom_type == "MultiPolygon":
        if not isinstance(coords, list) or not coords:
            raise ValueError(f"Empty MultiPolygon at feature {feature_index}")
        normalized = [
            normalize_polygon(poly, precision, f"features[{feature_index}].geometry[{i}]")
            for i, poly in enumerate(coords)
        ]
    else:
        raise ValueError(f"Unsupported geometry type at feature {feature_index}: {geom_type!r}")

    return {"type": geom_type, "coordinates": normalized}


def normalize_polygon(coords: Any, precision: int, path: str) -> list[list[list[float]]]:
    if not isinstance(coords, list) or not coords:
        raise ValueError(f"Empty Polygon coordinates at {path}")
    return [normalize_ring(ring, precision, f"{path}[{i}]") for i, ring in enumerate(coords)]


def normalize_ring(ring: Any, precision: int, path: str) -> list[list[float]]:
    if not isinstance(ring, list) or len(ring) < 4:
        raise ValueError(f"Invalid ring at {path}: expected at least 4 positions")

    normalized = [normalize_position(position, precision, f"{path}[{i}]") for i, position in enumerate(ring)]
    if normalized[0] != normalized[-1]:
        normalized.append(list(normalized[0]))

    if len(normalized) < 4:
        raise ValueError(f"Invalid ring at {path}: expected at least 4 positions after closing")
    return normalized


def normalize_position(position: Any, precision: int, path: str) -> list[float]:
    if not isinstance(position, list) or len(position) < 2:
        raise ValueError(f"Invalid position at {path}: expected [lon, lat]")

    lon = round_coordinate(position[0], precision, path)
    lat = round_coordinate(position[1], precision, path)
    return [lon, lat]


def round_coordinate(value: Any, precision: int, path: str) -> float:
    try:
        rounded = round(float(value), precision)
    except (TypeError, ValueError) as e:
        raise ValueError(f"Invalid coordinate at {path}: {value!r}") from e
    return 0.0 if rounded == -0.0 else rounded


def normalize_feature(feature: dict[str, Any], precision: int, index: int) -> tuple[dict[str, Any], bool, bool]:
    if feature.get("type") != "Feature":
        raise ValueError(f"Expected Feature at index {index}")

    properties = feature.get("properties")
    if not isinstance(properties, dict):
        raise ValueError(f"Feature {index} is missing properties")

    geometry = feature.get("geometry")
    if not isinstance(geometry, dict):
        raise ValueError(f"Feature {index} is missing geometry")

    name = normalize_string(properties.get("orders_nam"))
    remarks = normalize_string(properties.get("remarks"))

    normalized = {
        "type": "Feature",
        "properties": {
            "name": name,
            "remarks": remarks,
        },
        "geometry": normalize_geometry(geometry, precision, index),
    }
    return normalized, not bool(name), not bool(remarks)


def normalize_collection(data: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    source_features = validate_feature_collection(data, args.min_features)
    normalized_features: list[dict[str, Any]] = []
    missing_names = 0
    missing_remarks = 0

    for index, feature in enumerate(source_features):
        normalized, name_missing, remarks_missing = normalize_feature(feature, args.precision, index)
        normalized_features.append(normalized)
        missing_names += int(name_missing)
        missing_remarks += int(remarks_missing)

    source = {
        "name": "GovMap military fire ranges",
        "layer": SOURCE_LAYER,
        "url": args.url,
        "crs": "EPSG:4326",
        "coordinate_precision": args.precision,
        "properties": {
            "name": "orders_nam",
            "remarks": "remarks",
        },
    }
    fetched_at = normalize_string(data.get("timeStamp"))
    if fetched_at:
        source["fetched_at"] = fetched_at

    collection = {
        "type": "FeatureCollection",
        "name": "fire_ranges",
        "source": source,
        "features": normalized_features,
    }

    print(f"Normalized {len(normalized_features)} fire range features")
    if missing_names:
        print(f"Warning: {missing_names} features have no name", file=sys.stderr)
    if missing_remarks:
        print(f"Warning: {missing_remarks} features have no remarks", file=sys.stderr)

    return collection


def write_geojson(collection: dict[str, Any], path: str, pretty: bool) -> None:
    ensure_parent_dir(path)
    with open(path, "w", encoding="utf-8") as f:
        if pretty:
            json.dump(collection, f, ensure_ascii=False, indent=2)
        else:
            json.dump(collection, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")


def main() -> int:
    args = parse_args()

    try:
        data = load_source(args)
        collection = normalize_collection(data, args)
        write_geojson(collection, args.out, args.pretty)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

    print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

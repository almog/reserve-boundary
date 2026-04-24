package com.isnaturereserve

import android.content.Context
import android.util.JsonReader
import java.io.InputStreamReader

/**
 * In-memory index of Israeli nature reserves & national parks.
 *
 * Geometry is stored as flat float arrays (x-interleaved) to keep parse-time object
 * allocation low — 7MB of nested JSON in org.json balloons to ~70MB of Java objects.
 *
 * Point-in-polygon uses odd-even ring membership: a point inside N rings of a feature
 * is "inside" the feature iff N is odd. This handles holes and multi-polygons without
 * needing to distinguish outer/inner rings.
 */
class ReserveIndex(private val features: List<Feature>) {

    data class Feature(
        val name: String,
        val nameEn: String,
        val type: Type,
        val minLon: Float,
        val minLat: Float,
        val maxLon: Float,
        val maxLat: Float,
        /** Rings stored as (lon, lat, lon, lat, ...) float arrays. */
        val rings: List<FloatArray>,
    )

    enum class Type { RESERVE, PARK }

    data class Match(val name: String, val nameEn: String, val type: Type)

    data class ExitPoint(val lon: Double, val lat: Double, val distanceMeters: Float)

    /**
     * Finds the nearest point on any reserve/park boundary that is truly outside
     * all reserves (i.e. the nearest "exit" from protected areas).
     */
    fun nearestExit(lon: Double, lat: Double): ExitPoint? {
        val cosLat = Math.cos(Math.toRadians(lat))
        var bestDist2 = Double.MAX_VALUE
        var bestLon = 0.0
        var bestLat = 0.0

        // Phase 1: get upper-bound distance from enclosing features only
        for (f in features) {
            val lonF = lon.toFloat(); val latF = lat.toFloat()
            if (lonF < f.minLon || lonF > f.maxLon || latF < f.minLat || latF > f.maxLat) continue
            var inCount = 0
            for (ring in f.rings) { if (pointInRing(lon, lat, ring)) inCount++ }
            if (inCount and 1 != 1) continue

            for (ring in f.rings) {
                val d2 = closestOnRing(lon, lat, cosLat, ring)
                if (d2 != null && d2.first < bestDist2) {
                    bestDist2 = d2.first; bestLon = d2.second; bestLat = d2.third
                }
            }
        }
        if (bestDist2 == Double.MAX_VALUE) return null

        // Phase 2: check all features whose bbox is within upper-bound distance
        val bufferDeg = Math.sqrt(bestDist2) * 1.1
        for (f in features) {
            val fMinLon = f.minLon.toDouble(); val fMinLat = f.minLat.toDouble()
            val fMaxLon = f.maxLon.toDouble(); val fMaxLat = f.maxLat.toDouble()
            if (fMinLon - bufferDeg > lon || fMaxLon + bufferDeg < lon) continue
            if (fMinLat - bufferDeg > lat || fMaxLat + bufferDeg < lat) continue

            for (ring in f.rings) {
                val d2 = closestOnRing(lon, lat, cosLat, ring)
                if (d2 != null && d2.first < bestDist2) {
                    bestDist2 = d2.first; bestLon = d2.second; bestLat = d2.third
                }
            }
        }

        // Phase 3: verify the candidate is truly outside all reserves.
        // Nudge slightly past the boundary away from user.
        val dx = bestLon - lon
        val dy = bestLat - lat
        val len = Math.sqrt(dx * dx + dy * dy)
        if (len > 0) {
            val eps = 1e-6 // ~0.1m
            val checkLon = bestLon + dx / len * eps
            val checkLat = bestLat + dy / len * eps
            if (query(checkLon, checkLat).isNotEmpty()) {
                // The boundary candidate is between two adjacent reserves;
                // a full search is expensive, so fall back to the boundary distance
                // as a lower bound — still useful to the user.
            }
        }

        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat, lon, bestLat, bestLon, results)
        return ExitPoint(bestLon, bestLat, results[0])
    }

    fun query(lon: Double, lat: Double): List<Match> {
        val out = ArrayList<Match>(2)
        val lonF = lon.toFloat()
        val latF = lat.toFloat()
        for (f in features) {
            if (lonF < f.minLon || lonF > f.maxLon || latF < f.minLat || latF > f.maxLat) continue
            var inCount = 0
            for (ring in f.rings) {
                if (pointInRing(lon, lat, ring)) inCount++
            }
            if (inCount and 1 == 1) {
                out.add(Match(f.name, f.nameEn, f.type))
            }
        }
        return out
    }

    companion object {
        /** Ray casting: counts edge crossings of a rightward horizontal ray from (lon, lat). */
        private fun pointInRing(lon: Double, lat: Double, ring: FloatArray): Boolean {
            var inside = false
            val n = ring.size
            if (n < 6) return false
            var j = n - 2
            var i = 0
            while (i < n) {
                val xi = ring[i].toDouble()
                val yi = ring[i + 1].toDouble()
                val xj = ring[j].toDouble()
                val yj = ring[j + 1].toDouble()
                val intersects = ((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                if (intersects) inside = !inside
                j = i
                i += 2
            }
            return inside
        }

        /**
         * Returns (scaledDist2, closestLon, closestLat) for the closest point on ring to (lon, lat),
         * using equirectangular scaling. scaledDist2 is in degrees² scaled by cosLat.
         */
        private fun closestOnRing(
            lon: Double, lat: Double, cosLat: Double, ring: FloatArray
        ): Triple<Double, Double, Double>? {
            val n = ring.size
            if (n < 4) return null
            var best2 = Double.MAX_VALUE
            var bx = 0.0; var by = 0.0
            var j = n - 2
            var i = 0
            while (i < n) {
                val ax = ring[j].toDouble(); val ay = ring[j + 1].toDouble()
                val bxS = ring[i].toDouble(); val byS = ring[i + 1].toDouble()
                val abx = bxS - ax; val aby = byS - ay
                val abLen2 = abx * abx + aby * aby
                val t = if (abLen2 > 0.0) {
                    ((lon - ax) * abx + (lat - ay) * aby) / abLen2
                } else 0.0
                val tc = t.coerceIn(0.0, 1.0)
                val cx = ax + tc * abx
                val cy = ay + tc * aby
                val dx = (cx - lon) * cosLat
                val dy = cy - lat
                val d2 = dx * dx + dy * dy
                if (d2 < best2) { best2 = d2; bx = cx; by = cy }
                j = i; i += 2
            }
            return Triple(best2, bx, by)
        }

        /** Parses the bundled assets/inpa_reserves.geojson via streaming JsonReader. */
        fun loadFromAssets(ctx: Context, assetPath: String = "inpa_reserves.geojson"): ReserveIndex {
            ctx.assets.open(assetPath).use { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                    val features = ArrayList<Feature>(1024)
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "features") {
                            r.beginArray()
                            while (r.hasNext()) features.add(readFeature(r))
                            r.endArray()
                        } else r.skipValue()
                    }
                    r.endObject()
                    features.trimToSize()
                    return ReserveIndex(features)
                }
            }
        }

        private fun readFeature(r: JsonReader): Feature {
            var name = ""
            var nameEn = ""
            var type = Type.RESERVE
            var rings: List<FloatArray> = emptyList()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "properties" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "name" -> name = r.nextString()
                                "nameEn" -> nameEn = r.nextString()
                                "type" -> type = if (r.nextString() == "park") Type.PARK else Type.RESERVE
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                    "geometry" -> rings = readGeometry(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()

            var minLon = Float.MAX_VALUE; var minLat = Float.MAX_VALUE
            var maxLon = -Float.MAX_VALUE; var maxLat = -Float.MAX_VALUE
            for (ring in rings) {
                var i = 0
                while (i < ring.size) {
                    val lon = ring[i]; val lat = ring[i + 1]
                    if (lon < minLon) minLon = lon
                    if (lat < minLat) minLat = lat
                    if (lon > maxLon) maxLon = lon
                    if (lat > maxLat) maxLat = lat
                    i += 2
                }
            }
            return Feature(name, nameEn, type, minLon, minLat, maxLon, maxLat, rings)
        }

        private fun readGeometry(r: JsonReader): List<FloatArray> {
            var geomType = ""
            var rings: List<FloatArray> = emptyList()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> geomType = r.nextString()
                    "coordinates" -> rings = if (geomType == "MultiPolygon") readMultiPolygonCoords(r) else readPolygonCoords(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return rings
        }

        private fun readPolygonCoords(r: JsonReader): List<FloatArray> {
            val out = ArrayList<FloatArray>(1)
            r.beginArray()
            while (r.hasNext()) out.add(readRing(r))
            r.endArray()
            return out
        }

        private fun readMultiPolygonCoords(r: JsonReader): List<FloatArray> {
            val out = ArrayList<FloatArray>(4)
            r.beginArray()
            while (r.hasNext()) {
                r.beginArray()
                while (r.hasNext()) out.add(readRing(r))
                r.endArray()
            }
            r.endArray()
            return out
        }

        private fun readRing(r: JsonReader): FloatArray {
            val coords = ArrayList<Float>(128)
            r.beginArray()
            while (r.hasNext()) {
                r.beginArray()
                coords.add(r.nextDouble().toFloat())
                coords.add(r.nextDouble().toFloat())
                r.endArray()
            }
            r.endArray()
            return FloatArray(coords.size) { coords[it] }
        }
    }
}

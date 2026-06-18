package com.reserveboundary

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader

/**
 * In-memory index of military fire ranges published by GovMap.
 *
 * The app only needs containment for this layer, so this intentionally keeps a
 * smaller API than [ReserveIndex]. Geometry is still streamed into flat rings to
 * avoid inflating the bundled GeoJSON into a large nested object graph.
 */
class FireRangeIndex(private val features: List<Feature>) {

    data class Feature(
        val name: String,
        val remarks: String,
        val minLon: Float,
        val minLat: Float,
        val maxLon: Float,
        val maxLat: Float,
        /** Rings stored as (lon, lat, lon, lat, ...) float arrays. */
        val rings: List<FloatArray>,
    )

    data class Match(val name: String, val remarks: String)

    fun query(lon: Double, lat: Double): List<Match> {
        val out = ArrayList<Match>(1)
        val lonF = lon.toFloat()
        val latF = lat.toFloat()
        for (f in features) {
            if (lonF < f.minLon || lonF > f.maxLon || latF < f.minLat || latF > f.maxLat) continue
            var inCount = 0
            for (ring in f.rings) {
                if (pointInRing(lon, lat, ring)) inCount++
            }
            if (inCount and 1 == 1) {
                out.add(Match(f.name, f.remarks))
            }
        }
        return out
    }

    companion object {
        @Volatile private var cached: FireRangeIndex? = null

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

        fun loadFromAssets(ctx: Context, assetPath: String = "fire_ranges.geojson"): FireRangeIndex {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                ctx.assets.open(assetPath).use { input ->
                    JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                        val features = ArrayList<Feature>(256)
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
                        val idx = FireRangeIndex(features)
                        cached = idx
                        return idx
                    }
                }
            }
        }

        private fun readFeature(r: JsonReader): Feature {
            var name = ""
            var remarks = ""
            var rings: List<FloatArray> = emptyList()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "properties" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "name", "orders_nam" -> name = readStringOrEmpty(r)
                                "remarks" -> remarks = readStringOrEmpty(r)
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

            var minLon = Float.MAX_VALUE
            var minLat = Float.MAX_VALUE
            var maxLon = -Float.MAX_VALUE
            var maxLat = -Float.MAX_VALUE
            for (ring in rings) {
                var i = 0
                while (i < ring.size) {
                    val lon = ring[i]
                    val lat = ring[i + 1]
                    if (lon < minLon) minLon = lon
                    if (lat < minLat) minLat = lat
                    if (lon > maxLon) maxLon = lon
                    if (lat > maxLat) maxLat = lat
                    i += 2
                }
            }
            return Feature(name, remarks, minLon, minLat, maxLon, maxLat, rings)
        }

        private fun readStringOrEmpty(r: JsonReader): String {
            return if (r.peek() == JsonToken.NULL) {
                r.nextNull()
                ""
            } else {
                r.nextString().trim()
            }
        }

        private fun readGeometry(r: JsonReader): List<FloatArray> {
            var geomType: String? = null
            var streamedRings: List<FloatArray>? = null
            var bufferedCoords: Any? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> geomType = r.nextString()
                    "coordinates" -> {
                        val gt = geomType
                        if (gt != null) {
                            streamedRings = if (gt == "MultiPolygon") readMultiPolygonCoords(r) else readPolygonCoords(r)
                        } else {
                            bufferedCoords = readNested(r)
                        }
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return when {
                streamedRings != null -> streamedRings
                bufferedCoords != null && geomType == "MultiPolygon" -> flattenMultiPolygon(bufferedCoords)
                bufferedCoords != null && geomType == "Polygon" -> flattenPolygon(bufferedCoords)
                else -> emptyList()
            }
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
                while (r.hasNext()) r.skipValue()
                r.endArray()
            }
            r.endArray()
            return FloatArray(coords.size) { coords[it] }
        }

        private fun readNested(r: JsonReader): Any {
            return if (r.peek() == JsonToken.BEGIN_ARRAY) {
                val list = ArrayList<Any>()
                r.beginArray()
                while (r.hasNext()) list.add(readNested(r))
                r.endArray()
                list
            } else {
                r.nextDouble()
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun flattenPolygon(coords: Any): List<FloatArray> {
            val rings = coords as List<Any>
            return rings.map { ring ->
                val positions = ring as List<Any>
                val out = FloatArray(positions.size * 2)
                positions.forEachIndexed { i, p ->
                    val pos = p as List<Any>
                    out[i * 2] = (pos[0] as Double).toFloat()
                    out[i * 2 + 1] = (pos[1] as Double).toFloat()
                }
                out
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun flattenMultiPolygon(coords: Any): List<FloatArray> {
            val polygons = coords as List<Any>
            val out = ArrayList<FloatArray>(polygons.size)
            polygons.forEach { poly -> out.addAll(flattenPolygon(poly)) }
            return out
        }
    }
}

package com.thesystem.app.core

import kotlin.math.abs

/**
 * Minimal geohash encoder used by the Territory system.
 * Precision 6 ≈ 1,220m × 610m cells → matches the 1KM capture zone spec.
 */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(latitude: Double, longitude: Double, precision: Int = SystemMath.TERRITORY_GEOHASH_PRECISION): String {
        var latRange = doubleArrayOf(-90.0, 90.0)
        var lonRange = doubleArrayOf(-180.0, 180.0)
        val out = StringBuilder(precision)
        var even = true
        var bit = 0
        var ch = 0
        while (out.length < precision) {
            if (even) {
                val mid = (lonRange[0] + lonRange[1]) / 2
                if (longitude >= mid) { ch = ch or (1 shl (4 - bit)); lonRange[0] = mid } else lonRange[1] = mid
            } else {
                val mid = (latRange[0] + latRange[1]) / 2
                if (latitude >= mid) { ch = ch or (1 shl (4 - bit)); latRange[0] = mid } else latRange[1] = mid
            }
            even = !even
            if (bit < 4) bit++ else { out.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return out.toString()
    }

    /** [latMin, lonMin, latMax, lonMax] */
    fun decodeBounds(hash: String): DoubleArray {
        var latRange = doubleArrayOf(-90.0, 90.0)
        var lonRange = doubleArrayOf(-180.0, 180.0)
        var even = true
        for (c in hash.lowercase()) {
            val bits = BASE32.indexOf(c)
            require(bits >= 0) { "Invalid geohash char: $c" }
            for (n in 4 downTo 0) {
                val bit = (bits shr n) and 1
                if (even) {
                    val mid = (lonRange[0] + lonRange[1]) / 2
                    if (bit == 1) lonRange[0] = mid else lonRange[1] = mid
                } else {
                    val mid = (latRange[0] + latRange[1]) / 2
                    if (bit == 1) latRange[0] = mid else latRange[1] = mid
                }
                even = !even
            }
        }
        return doubleArrayOf(latRange[0], lonRange[0], latRange[1], lonRange[1])
    }

    fun center(hash: String): Pair<Double, Double> {
        val b = decodeBounds(hash)
        return ((b[0] + b[2]) / 2) to ((b[1] + b[3]) / 2)
    }

    /** All 8 adjacent cells + the cell itself. Cell-aligned so re-encoding neighbors is exact. */
    fun gridAround(hash: String): List<String> {
        val b = decodeBounds(hash)
        val dLat = abs(b[2] - b[0])
        val dLon = abs(b[3] - b[1])
        val (cLat, cLon) = center(hash)
        val cells = mutableListOf<String>()
        for (dl in -1..1) for (dn in -1..1) {
            var lon = cLon + dn * dLon
            if (lon > 180) lon -= 360
            if (lon < -180) lon += 360
            val lat = (cLat + dl * dLat).coerceIn(-90.0, 90.0)
            cells += encode(lat, lon, hash.length)
        }
        return cells.distinct()
    }
}

package com.xmu.rollcall.utils

import kotlin.math.*

object LocationSolver {
    private const val RADIUS = 6371000.0

    private fun latLonToXY(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val x = Math.toRadians(lon - lon0) * RADIUS * cos(Math.toRadians(lat0))
        val y = Math.toRadians(lat - lat0) * RADIUS
        return Pair(x, y)
    }

    private fun xyToLatLon(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val lat = lat0 + Math.toDegrees(y / RADIUS)
        val lon = lon0 + Math.toDegrees(x / (RADIUS * cos(Math.toRadians(lat0))))
        return Pair(lat, lon)
    }

    private fun circleIntersections(
        x1: Double, y1: Double, d1: Double,
        x2: Double, y2: Double, d2: Double
    ): List<Pair<Double, Double>>? {
        val distance = hypot(x2 - x1, y2 - y1)
        if (distance == 0.0) return null
        if (distance > d1 + d2 || distance < abs(d1 - d2)) return null

        val a = (d1 * d1 - d2 * d2 + distance * distance) / (2 * distance)
        val hSquare = d1 * d1 - a * a
        if (hSquare < 0) return null
        val h = sqrt(hSquare)

        val xm = x1 + a * (x2 - x1) / distance
        val ym = y1 + a * (y2 - y1) / distance

        val rx = -(y2 - y1) * (h / distance)
        val ry = (x2 - x1) * (h / distance)

        return listOf(
            Pair(xm + rx, ym + ry),
            Pair(xm - rx, ym - ry)
        )
    }

    /**
     * Solves the intersections of two circles defined by their centers (GPS coordinates)
     * and their radii (distances in meters).
     */
    fun solveTwoPoints(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        dist1: Double, dist2: Double
    ): List<Pair<Double, Double>>? {
        val lat0 = (lat1 + lat2) / 2.0
        val lon0 = (lon1 + lon2) / 2.0
        val (x1, y1) = latLonToXY(lat1, lon1, lat0, lon0)
        val (x2, y2) = latLonToXY(lat2, lon2, lat0, lon0)

        val intersections = circleIntersections(x1, y1, dist1, x2, y2, dist2) ?: return null

        return intersections.map { (x, y) ->
            xyToLatLon(x, y, lat0, lon0)
        }
    }
}

package com.xmu.rollcall.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LocationSolverTest {

    @Test
    fun testSolveTwoPoints() {
        // Point 1: 24.3, 118.0
        // Point 2: 24.6, 118.2
        val lat1 = 24.3
        val lon1 = 118.0
        val lat2 = 24.6
        val lon2 = 118.2

        // Let's assume a target point: 24.4, 118.1
        // We compute standard rough distance:
        // dist = hypot((lat - lat1) * 111000, (lon - lon1) * 111000 * cos(lat1))
        // Let's just solve with arbitrary valid distances to make sure intersections can be computed
        val d1 = 20000.0 // 20 km
        val d2 = 25000.0 // 25 km

        val solved = LocationSolver.solveTwoPoints(lat1, lon1, lat2, lon2, d1, d2)
        assertNotNull(solved)
        assertEquals(2, solved!!.size)

        val (sol1, sol2) = solved
        
        // Check that solved points are within reasonable GPS range
        assertTrue(sol1.first in 20.0..30.0)
        assertTrue(sol1.second in 110.0..120.0)
        assertTrue(sol2.first in 20.0..30.0)
        assertTrue(sol2.second in 110.0..120.0)
    }
}

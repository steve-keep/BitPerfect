package com.bitperfect.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {

    @Test
    fun verifyAppRoutes() {
        assertEquals("device_list", AppRoutes.DeviceList)
        assertEquals("track_list", AppRoutes.TrackList)
        assertEquals("settings", AppRoutes.Settings)
        assertEquals("about", AppRoutes.About)
        assertEquals("calibration", AppRoutes.Calibration)
    }
}

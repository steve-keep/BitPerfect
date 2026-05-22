package com.bitperfect.app.output

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import android.content.Context

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpnpManagerTest {

    private val playerJsonNoDeviceName = JSONObject("""
        {"type":"0","ch":"0","mode":"2","loop":"4","eq":"0","vendor":"","status":"stop","curpos":"11091","offset_pts":"11091","totlen":"170933","Title":"6D79206D696E642069732061206D6F756E7461696E","Artist":"446566746F6E6573","Album":"70726976617465206D75736963","alarmflag":"0","plicount":"0","plicurr":"0","vol":"30","mute":"0"}
    """.trimIndent())

    private val statusJsonWithDeviceName = JSONObject("""
        {"language":"en_us","ssid":"WiiM Amp-495C","hideSSID":"0","firmware":"Linkplay.5.2.814734","build":"release","project":"WiiM_Amp_4layer","priv_prj":"WiiM_Amp_4layer","project_build_name":"WiiM_Amp_4layer","Release":"20260423","uuid":"FF98F2F778FDB7536B1E1834","MAC":"9C:B8:B4:95:49:5C","hardware":"AmlogicA113","DeviceName":"Patio","GroupName":"Patio"}
    """.trimIndent())

    private val playerJsonWithDeviceName = JSONObject("""
        {"type":"0","ch":"0","DeviceName":"Living Room","uuid":"AA11BB22","hardware":"OtherHardware"}
    """.trimIndent())

    private val mockContext = mock(Context::class.java)

    @Test
    fun `mergeDeviceJson - getStatusEx has DeviceName, getPlayerStatusEx does not`() {
        val manager = UpnpManager(mockContext)
        val device = manager.mergeDeviceJson(
            playerJson = playerJsonNoDeviceName,
            statusJson = statusJsonWithDeviceName,
            ip = "192.168.4.44",
            baseUrl = "https://192.168.4.44:443"
        )

        assertEquals("Patio", device?.friendlyName)
        assertEquals("FF98F2F778FDB7536B1E1834", device?.udn)
        assertEquals("AmlogicA113", device?.modelName)
    }

    @Test
    fun `mergeDeviceJson - both responses null (non-WiiM device)`() {
        val manager = UpnpManager(mockContext)
        val device = manager.mergeDeviceJson(
            playerJson = null,
            statusJson = null,
            ip = "192.168.4.44",
            baseUrl = "https://192.168.4.44:443"
        )

        assertNull(device)
    }

    @Test
    fun `mergeDeviceJson - getStatusEx fails (null), getPlayerStatusEx succeeds with no DeviceName`() {
        val manager = UpnpManager(mockContext)
        val device = manager.mergeDeviceJson(
            playerJson = playerJsonNoDeviceName,
            statusJson = null,
            ip = "192.168.4.44",
            baseUrl = "https://192.168.4.44:443"
        )

        assertEquals("WiiM @ 192.168.4.44", device?.friendlyName)
        assertEquals("192.168.4.44", device?.udn) // fallback to IP when uuid is missing
        assertNull(device?.modelName)
    }

    @Test
    fun `mergeDeviceJson - getStatusEx fails (null), getPlayerStatusEx succeeds and has DeviceName`() {
        val manager = UpnpManager(mockContext)
        val device = manager.mergeDeviceJson(
            playerJson = playerJsonWithDeviceName,
            statusJson = null,
            ip = "192.168.4.44",
            baseUrl = "https://192.168.4.44:443"
        )

        assertEquals("Living Room", device?.friendlyName)
        assertEquals("AA11BB22", device?.udn)
        assertNull(device?.modelName) // Assuming hardware/project is only read from statusJson based on your mergeDeviceJson logic implementation
    }
}

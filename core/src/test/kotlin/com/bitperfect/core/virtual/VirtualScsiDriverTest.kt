package com.bitperfect.core.virtual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VirtualScsiDriverTest {

    @Test
    fun testInquiry() {
        val testCd = TestCdData.CDs[0]
        val driver = VirtualScsiDriver { testCd }
        val inquiryCmd = byteArrayOf(0x12, 0, 0, 0, 36, 0)

        val response = driver.executeScsiCommand(-1, inquiryCmd, 36)

        assertNotNull(response)
        assertEquals(36, response?.size)
        val vendor = String(response!!.sliceArray(8 until 16)).trim()
        val product = String(response.sliceArray(16 until 32)).trim()
        assertEquals(testCd.vendor.take(8).trim(), vendor)
        assertEquals(testCd.product.take(16).trim(), product)
    }

    @Test
    fun testReadToc() {
        val testCd = TestCdData.CDs[0]
        val driver = VirtualScsiDriver { testCd }
        val tocCmd = byteArrayOf(0x43, 0, 0, 0, 0, 0, 0, 0, 18, 0)

        val response = driver.executeScsiCommand(-1, tocCmd, 18)

        assertNotNull(response)
        assertEquals(1, response!![2].toInt())
        assertEquals(testCd.tracks.size, response[3].toInt())
    }
}

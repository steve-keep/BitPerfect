package com.bitperfect.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumUtilsTest {
    @Test
    fun testCrc32() {
        val data = "Hello World".toByteArray()
        val expected = 0x4A17B156L
        assertEquals(expected, ChecksumUtils.calculateCrc32(data))
    }
}

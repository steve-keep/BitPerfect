package com.bitperfect.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StringUtilsTest {

    @Test
    fun testDecodeUnicodeEscapes() {
        assertEquals("Café", "Caf\\u00e9".decodeUnicodeEscapes())
        assertEquals("Sébastien", "S\\u00e9bastien".decodeUnicodeEscapes())
        assertEquals("No escapes here", "No escapes here".decodeUnicodeEscapes())
        assertEquals("Björk", "Bj\\u00f6rk".decodeUnicodeEscapes())
        // Multiple
        assertEquals("Café & Sébastien", "Caf\\u00e9 & S\\u00e9bastien".decodeUnicodeEscapes())
    }
}

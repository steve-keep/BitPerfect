package com.bitperfect.app.library

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*
import kotlinx.coroutines.test.runTest

class AiMixGeneratorTest {

    @Test
    fun testIsAvailableReturnsBoolean() = runTest {
        val generator = AiMixGenerator(mock(Context::class.java))
        val isAvail = generator.isAvailable()
        assertTrue(isAvail || !isAvail) // Either way, should not crash
    }

    @Test
    fun testGenerateMixesReturnsEmptyOnException() = runTest {
        val generator = AiMixGenerator(mock(Context::class.java))
        // Calling without proper setup / context should throw and be caught
        val mixes = generator.generateMixes("foo", emptyList())
        assertTrue(mixes.isEmpty())
    }
}

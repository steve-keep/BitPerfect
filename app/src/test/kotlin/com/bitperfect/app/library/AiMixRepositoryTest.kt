package com.bitperfect.app.library

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class AiMixRepositoryTest {

    @Test
    fun testGetLatestMixesNullUri() {
        val repo = AiMixRepository()
        val context = mock(Context::class.java)
        val result = repo.getLatestMixes(context, null)
        assertEquals(0, result.size)
    }

    @Test
    fun testBuildLibrarySummaryNullUri() {
        val repo = AiMixRepository()
        val context = mock(Context::class.java)
        val result = repo.buildLibrarySummary(context, null)
        assertEquals("", result)
    }

    @Test
    fun testGetLastGeneratedAtNullUri() {
        val repo = AiMixRepository()
        val context = mock(Context::class.java)
        val result = repo.getLastGeneratedAt(context, null)
        assertEquals(null, result)
    }
}

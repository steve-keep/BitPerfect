package com.bitperfect.app.ripping.paranoia.fastpath

import com.bitperfect.app.ripping.paranoia.RipConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathEvaluatorTest {

    @Test
    fun `fast-path becomes eligible after threshold`() {
        val evaluator = FastPathEvaluator()
        assertFalse(evaluator.state.eligible)

        evaluator.reportMatch()
        assertFalse(evaluator.state.eligible)

        evaluator.reportMatch()
        assertFalse(evaluator.state.eligible)

        evaluator.reportMatch()
        assertTrue(evaluator.state.eligible)
        assertEquals(3, evaluator.state.stableChunkCount)
    }

    @Test
    fun `immediate mismatch revocation`() {
        val evaluator = FastPathEvaluator()
        evaluator.reportMatch()
        evaluator.reportMatch()
        evaluator.reportMatch()
        assertTrue(evaluator.state.eligible)

        evaluator.reportMismatch()
        assertFalse(evaluator.state.eligible)
        assertEquals(0, evaluator.state.consecutiveImmediateMatches)
        assertTrue(evaluator.state.hasRecentAnomalies)
    }

    @Test
    fun `anomaly revocation`() {
        val evaluator = FastPathEvaluator()
        evaluator.reportMatch()
        evaluator.reportMatch()
        evaluator.reportMatch()
        assertTrue(evaluator.state.eligible)

        evaluator.reportAnomaly()
        assertFalse(evaluator.state.eligible)
        assertEquals(0, evaluator.state.consecutiveImmediateMatches)
        assertTrue(evaluator.state.hasRecentAnomalies)
    }

    @Test
    fun `confidence downgrade revocation`() {
        val evaluator = FastPathEvaluator()
        evaluator.reportMatch()
        evaluator.reportMatch()
        evaluator.reportMatch()
        assertTrue(evaluator.state.eligible)

        evaluator.reportConfidenceDowngrade()
        assertFalse(evaluator.state.eligible)
        assertEquals(0, evaluator.state.consecutiveImmediateMatches)
        assertTrue(evaluator.state.hasRecentAnomalies)
    }

    @Test
    fun `reportMatch with non HIGH confidence revokes fast-path`() {
        val evaluator = FastPathEvaluator()
        evaluator.reportMatch()
        evaluator.reportMatch()
        evaluator.reportMatch()
        assertTrue(evaluator.state.eligible)

        evaluator.reportMatch(RipConfidence.MEDIUM)
        assertFalse(evaluator.state.eligible)
        assertEquals(0, evaluator.state.consecutiveImmediateMatches)
        assertTrue(evaluator.state.hasRecentAnomalies)
    }

    @Test
    fun `becomes eligible again after anomaly followed by consecutive matches`() {
        val evaluator = FastPathEvaluator()
        evaluator.reportMismatch()

        evaluator.reportMatch()
        evaluator.reportMatch()
        assertFalse(evaluator.state.eligible)
        assertTrue(evaluator.state.hasRecentAnomalies)

        evaluator.reportMatch()
        assertTrue(evaluator.state.eligible)
        assertFalse(evaluator.state.hasRecentAnomalies)
    }
}

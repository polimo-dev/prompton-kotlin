package dev.polimo.prompton.conformance

import dev.polimo.prompton.StopKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs every case of `conformance/stop_kind.json`. */
class StopKindConformanceTest {
    @Test
    fun `finish reason normalisation`() {
        var executed = 0
        for (case in Conformance.cases("stop_kind")) {
            val finishReason = Conformance.string(case, "finish_reason")
            val expected = Conformance.string(case, "stop_kind")!!
            val expectedTruncated = Conformance.native(case["truncated"]) == true
            val label = "finish_reason=${finishReason ?: "null"} (${Conformance.string(case, "source")})"

            val stopKind = StopKind.normalize(finishReason)
            assertEquals(expected, stopKind.wire, label)
            assertEquals(expectedTruncated, stopKind.isTruncated, label)
            assertEquals(expectedTruncated, StopKind.isTruncated(finishReason), label)
            assertEquals(stopKind, StopKind.normalize(stopKind.wire), "normalisation must be idempotent: $label")
            executed += 1
        }
        assertEquals(22, executed)
    }
}

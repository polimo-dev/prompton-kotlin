package dev.polimo.prompton

import dev.polimo.prompton.internal.PromptOnLifecycle
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Instances must not pile up.
 *
 * A shutdown hook is a GC root, so one hook per instance would pin every instance an app ever built
 * — a test suite or a per-request instantiation would accumulate hooks and threads without bound.
 * The SDK registers one hook for the process and lets a cleaner release an instance the app dropped
 * without closing it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LifecycleTest {
    private fun config() =
        PromptOnConfig(
            apiKey = "ptn_fixture_secret",
            host = "https://prompton.test",
            environment = "production",
            project = "fixture",
            cacheTtl = 10.seconds,
            pollingEnabled = false,
            diskCacheEnabled = false,
            transport = StubTransport { HttpResponse(304) },
        )

    private fun promptonThreads(): Int =
        Thread
            .getAllStackTraces()
            .keys
            .count { it.name == "prompton-prompts" || it.name == "prompton-logs" }

    @Test
    @Order(1)
    fun `many instances register one hook and deregister as they close`() {
        val before = PromptOnLifecycle.liveCount()
        val reclaimedBefore = PromptOnLifecycle.reclaimedCount()
        val instances = (1..50).map { PromptOn(config()) }
        try {
            assertTrue(PromptOnLifecycle.hookInstalled(), "the process-wide shutdown hook is installed")
            val growth = PromptOnLifecycle.liveCount() - before
            assertTrue(growth in 1..50, "the registry grew by $growth for 50 instances")
        } finally {
            instances.forEach { it.close() }
        }
        assertTrue(
            PromptOnLifecycle.liveCount() <= before,
            "closing deregisters every instance (${PromptOnLifecycle.liveCount()} left, was $before)",
        )
        assertEquals(
            reclaimedBefore,
            PromptOnLifecycle.reclaimedCount(),
            "a deliberate close is not reported as a forgotten instance",
        )
    }

    @Test
    @Order(2)
    fun `an instance the app forgets to close is reclaimed with its threads`() {
        val baselineThreads = promptonThreads()
        val reclaimedBefore = PromptOnLifecycle.reclaimedCount()

        repeat(40) { PromptOn(config()) }
        assertTrue(promptonThreads() > baselineThreads, "the forgotten instances did start threads")

        collectUntil("30 of the 40 forgotten instances to be reclaimed") {
            PromptOnLifecycle.reclaimedCount() - reclaimedBefore >= 30
        }
        collectUntil("their threads to be released") { promptonThreads() <= baselineThreads + 10 }
    }

    /** Nudges the collector, because the cleaner only runs once the instance is unreachable. */
    private fun collectUntil(
        label: String,
        timeoutMillis: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            System.gc()
            Thread.sleep(50)
        }
        fail("timed out waiting for $label")
    }
}

package dev.polimo.prompton

import dev.polimo.prompton.internal.PromptOnClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.fail

/** A clock the tests drive by hand, so `started_at` and `latency_ms` are reproducible. */
class FakeClock(
    var instant: Instant = Instant.parse("2026-09-04T09:00:00Z"),
    var nanos: Long = 0L,
) : PromptOnClock {
    override fun now(): Instant = instant

    override fun nanoTime(): Long = nanos

    fun advanceMillis(millis: Long) {
        instant = instant.plusMillis(millis)
        nanos += millis * 1_000_000
    }
}

/** A transport that answers from a script and records what it was asked. */
class StubTransport(
    private val handler: (HttpRequest) -> HttpResponse,
) : HttpTransport {
    val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
    private val failures = ConcurrentLinkedQueue<Exception>()

    override fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        failures.poll()?.let { throw it }
        return handler(request)
    }

    fun failNext(error: Exception) {
        failures.add(error)
    }

    fun requestCount(): Int = synchronized(requests) { requests.size }

    fun lastRequest(): HttpRequest = synchronized(requests) { requests.last() }

    /** Only the `POST /generations` calls, so a snapshot fetch never skews a log assertion. */
    fun posts(): List<HttpRequest> = synchronized(requests) { requests.filter { it.method == "POST" } }

    fun postCount(): Int = posts().size

    fun lastPost(): HttpRequest = posts().last()
}

/**
 * Compares two JSON trees, treating numbers by value.
 *
 * `1.2e-06` and `1.2E-6` are the same number written by two languages; everything else must match
 * exactly, including which keys are present.
 */
fun assertJsonEquivalent(
    expected: JsonElement,
    actual: JsonElement,
    label: String = "",
) {
    val difference = difference(expected, actual, "$")
    if (difference != null) {
        fail("$label: $difference\nexpected: $expected\nactual:   $actual")
    }
}

private fun difference(
    expected: JsonElement,
    actual: JsonElement,
    path: String,
): String? {
    if (expected is JsonObject && actual is JsonObject) {
        val missing = expected.keys - actual.keys
        val extra = actual.keys - expected.keys
        if (missing.isNotEmpty()) return "$path is missing ${missing.sorted()}"
        if (extra.isNotEmpty()) return "$path has unexpected ${extra.sorted()}"
        for (key in expected.keys) {
            difference(expected.getValue(key), actual.getValue(key), "$path.$key")?.let { return it }
        }
        return null
    }
    if (expected is JsonArray && actual is JsonArray) {
        if (expected.size != actual.size) return "$path has ${actual.size} entries, expected ${expected.size}"
        for (index in expected.indices) {
            difference(expected[index], actual[index], "$path[$index]")?.let { return it }
        }
        return null
    }
    if (expected is JsonPrimitive && actual is JsonPrimitive) {
        if (expected == actual) return null
        if (!expected.isString && !actual.isString) {
            val left = expected.content.toDoubleOrNull()
            val right = actual.content.toDoubleOrNull()
            if (left != null && right != null && left == right) return null
        }
        return "$path is $actual, expected $expected"
    }
    return "$path is $actual, expected $expected"
}

/** Asserts a list has the size the caller expects, printing the list when it does not. */
fun <T> assertSize(
    expected: Int,
    actual: List<T>,
    label: String = "",
) {
    assertEquals(expected, actual.size, "$label $actual")
}

/** Waits until [condition] holds, or fails after [timeoutMillis]. */
fun await(
    label: String,
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(5)
    }
    fail("timed out waiting for $label")
}

/** Waits until nothing further happens for [quietMillis], then asserts the condition still holds. */
fun assertStaysTrue(
    label: String,
    quietMillis: Long = 200,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + quietMillis
    while (System.currentTimeMillis() < deadline) {
        if (!condition()) fail("$label stopped holding")
        Thread.sleep(5)
    }
}

/** Waits until the SDK's background thread has finished processing what it was handed. */
fun settle(millis: Long = 150) {
    Thread.sleep(millis)
}

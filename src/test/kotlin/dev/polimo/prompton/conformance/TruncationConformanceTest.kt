package dev.polimo.prompton.conformance

import dev.polimo.prompton.PayloadMode
import dev.polimo.prompton.PayloadPolicy
import dev.polimo.prompton.internal.Payload
import dev.polimo.prompton.internal.PayloadOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs every case of `conformance/truncation.json`, plus the sampling buckets it pins down. */
class TruncationConformanceTest {
    @Test
    fun `payload policy cases`() {
        var executed = 0
        for (case in Conformance.cases("truncation")) {
            val name = Conformance.string(case, "name")!!
            val record = case["log"] as JsonObject
            val policyJson = case["policy"] as JsonObject
            val configJson = case["config"] as? JsonObject
            val expected = (case["expect"] as JsonObject)["log"] as JsonObject

            val policy =
                PayloadPolicy(
                    mode = PayloadMode.fromWire(Conformance.string(policyJson, "mode")),
                    sampleRate = (Conformance.native(policyJson["sample_rate"]) as Number).toDouble(),
                    maxBytes = (Conformance.native(policyJson["max_bytes"]) as Number).toInt(),
                )
            val options =
                PayloadOptions(
                    hashEndUser = Conformance.native(configJson?.get("hash_end_user")) == true,
                )

            assertEquals(expected, Payload.apply(record, policy, options), name)
            executed += 1
        }
        assertEquals(19, executed)
    }

    @Test
    fun `sampling buckets`() {
        val sampling = Conformance.load("truncation")["sampling"] as JsonObject
        for (entry in sampling["buckets"] as JsonArray) {
            val obj = entry as JsonObject
            val id = (obj["id"] as JsonPrimitive).content
            val expected = (obj["bucket"] as JsonPrimitive).content.toInt()
            assertEquals(expected, Payload.bucket(id), "bucket of '$id'")
        }
    }

    @Test
    fun `server field caps are not applied by the sdk`() {
        val caps = Conformance.load("truncation")["server_field_caps"] as JsonObject
        assertEquals("server", Conformance.string(caps, "applied_by"))

        val bigParams = JsonObject((1..400).associate { "key$it" to JsonPrimitive("value".repeat(4)) })
        val bigRaw = JsonObject((1..1000).associate { "token$it" to JsonPrimitive(it) })
        val record =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("0198f2a1-0000-7000-8000-000000009001"),
                    "status" to JsonPrimitive("ok"),
                    "params" to bigParams,
                    "usage" to JsonObject(mapOf("raw" to bigRaw)),
                ),
            )

        val result = Payload.apply(record, PayloadPolicy(), PayloadOptions())

        assertEquals(bigParams, result["params"], "params is the server's job, not the SDK's")
        assertEquals(bigRaw, (result["usage"] as JsonObject)["raw"], "usage.raw is the server's job, not the SDK's")
    }
}

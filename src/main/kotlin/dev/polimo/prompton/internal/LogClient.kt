package dev.polimo.prompton.internal

import dev.polimo.prompton.HttpRequest
import dev.polimo.prompton.HttpTransport
import dev.polimo.prompton.PromptOnConfig
import dev.polimo.prompton.PromptOnException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * `POST /logs`: one batch of monitoring logs, for one environment.
 *
 * It is a separate object rather than a method on the SDK instance so the log queue's worker thread
 * never pins that instance: a forgotten instance stays collectible and its threads can be released.
 */
internal class LogClient(
    private val config: PromptOnConfig,
    private val transport: HttpTransport?,
) {
    fun post(
        environment: String,
        records: List<JsonObject>,
    ): BatchOutcome {
        val body = Ptn.canonicalJson(JsonObject(mapOf("logs" to JsonArray(records))))
        val url =
            "${config.baseUrl}/logs?environment=" +
                URLEncoder.encode(environment, StandardCharsets.UTF_8)
        val client =
            transport ?: throw PromptOnException(
                "this PromptOn instance makes no remote calls (mode=${config.mode}, apiKey present=" +
                    "${!config.apiKey.isNullOrBlank()})",
            )
        val response = client.execute(HttpRequest("POST", url, config.wireHeaders(json = true), body))
        return when {
            response.status in 200..299 -> {
                val parsed = runCatching { Ptn.parseObject(response.body) }.getOrNull()
                BatchOutcome.Accepted(
                    accepted = Ptn.asInt(parsed?.get("accepted")) ?: 0,
                    duplicates = Ptn.asInt(parsed?.get("duplicates")) ?: 0,
                    rejected = Ptn.asArray(parsed?.get("rejected"))?.mapNotNull { Ptn.asObject(it) }.orEmpty(),
                )
            }

            response.status == 413 -> BatchOutcome.TooLarge
            response.status == 429 ->
                BatchOutcome.Retry(SnapshotManager.retryAfterOf(response), "rate limited (429)")

            response.status >= 500 ->
                BatchOutcome.Retry(SnapshotManager.retryAfterOf(response), "HTTP ${response.status}")

            else -> BatchOutcome.Rejected(response.status, SnapshotManager.errorMessageOf(response))
        }
    }
}

/** The headers every PromptOn call carries. */
internal fun PromptOnConfig.wireHeaders(json: Boolean): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers["accept"] = "application/json"
    headers["user-agent"] = userAgent
    if (json) headers["content-type"] = "application/json"
    apiKey?.let { headers["authorization"] = "Bearer $it" }
    return headers
}

package dev.polimo.prompton

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse.BodyHandlers
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** One HTTP request the SDK wants to make. */
public data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeout: Duration? = null,
)

/** One HTTP response. Header names are lowercase. */
public data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    public fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * The SDK's HTTP seam.
 *
 * Implement it to route PromptOn's two runtime calls through your own client, or to stub the server
 * in tests. Implementations must be safe to call from several threads.
 */
public interface HttpTransport : AutoCloseable {
    public fun execute(request: HttpRequest): HttpResponse

    override fun close() {
        // Nothing to release by default.
    }
}

/** The default transport: `java.net.http.HttpClient`, no third-party dependencies. */
public class JdkHttpTransport
    @JvmOverloads
    constructor(
        connectTimeout: Duration,
        private val defaultTimeout: Duration,
        private val client: HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(connectTimeout.toJavaDuration())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
    ) : HttpTransport {
        override fun execute(request: HttpRequest): HttpResponse {
            val builder =
                java.net.http.HttpRequest
                    .newBuilder()
                    .uri(URI.create(request.url))
                    .timeout(request.timeout?.toJavaDuration() ?: defaultTimeout.toJavaDuration())
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            val publisher =
                request.body
                    ?.let {
                        java.net.http.HttpRequest.BodyPublishers
                            .ofString(it, Charsets.UTF_8)
                    }
                    ?: java.net.http.HttpRequest.BodyPublishers
                        .noBody()
            builder.method(request.method, publisher)

            val response = client.send(builder.build(), BodyHandlers.ofString(Charsets.UTF_8))
            val headers =
                response
                    .headers()
                    .map()
                    .entries
                    .associate { (name, values) -> name.lowercase() to values.first() }
            return HttpResponse(response.statusCode(), headers, response.body() ?: "")
        }

        override fun close() {
            // HttpClient has no close() on JDK 17; connections are released with the client itself.
        }
    }

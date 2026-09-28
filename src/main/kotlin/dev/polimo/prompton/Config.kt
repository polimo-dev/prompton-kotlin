package dev.polimo.prompton

import dev.polimo.prompton.internal.Env
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How the SDK talks to the outside world. */
public enum class PromptOnMode {
    /** Normal operation: poll the server, cache to disk, send monitoring logs. */
    LIVE,

    /** No HTTP at all. Use case documents are injected and monitoring logs are captured for assertions. */
    TEST,

    /** Disk cache and bundle only: nothing is fetched and nothing is sent. */
    OFFLINE,
}

/** Buffering and payload options for monitoring logs. */
public data class LogOptions
    @JvmOverloads
    constructor(
        /** Flush at the latest this long after the first record of a batch was enqueued. */
        val flushInterval: Duration = 2.seconds,
        /** Flush as soon as this many records are queued. */
        val flushSize: Int = 100,
        /** Flush as soon as the queue holds this many encoded bytes. */
        val flushBytes: Int = 1_000_000,
        /** Hard cap on the queue. Over it the oldest records are dropped and counted. */
        val maxBufferSize: Int = 10_000,
        /** Records per request. The server refuses more than 200. */
        val maxBatchSize: Int = 200,
        /** Encoded bytes per request. The server's body limit is 5 MB. */
        val maxBatchBytes: Int = 4_000_000,
        /** How often one batch is retried before it is dropped and counted. */
        val maxAttempts: Int = 8,
        /** Applied to every record last, after truncation and hashing. Return the record to send. */
        val redact: ((JsonObject) -> JsonObject)? = null,
    ) {
        init {
            require(flushSize > 0) { "flushSize must be positive" }
            require(flushBytes > 0) { "flushBytes must be positive" }
            require(maxBufferSize > 0) { "maxBufferSize must be positive" }
            require(maxBatchSize in 1..200) { "maxBatchSize must be between 1 and 200" }
            require(maxBatchBytes in 1..5_000_000) { "maxBatchBytes must be at most 5000000" }
            require(maxAttempts > 0) { "maxAttempts must be positive" }
        }
    }

/**
 * SDK configuration.
 *
 * Every value follows the same precedence: an explicit argument wins, then the environment
 * variable, then the default. Constructing this reads `PTN_HOST`, `PTN_API_KEY`, `PTN_PROJECT` and
 * `PTN_ENVIRONMENT`.
 */
public data class PromptOnConfig
    @JvmOverloads
    constructor(
        /** `ptn_<project_slug>_…`. Without it the SDK makes no remote calls and works from disk or bundle. */
        val apiKey: String? = Env.get("PTN_API_KEY"),
        /** The PromptOn app's base URL. The SDK appends `/api/v1` itself. */
        val host: String = Env.get("PTN_HOST") ?: DEFAULT_HOST,
        /** Which environment this process reads. */
        val environment: String = Env.get("PTN_ENVIRONMENT") ?: DEFAULT_ENVIRONMENT,
        /** The project slug. Defaults to the one embedded in the API key. */
        val project: String? = Env.get("PTN_PROJECT") ?: projectFromApiKey(apiKey),
        /** How long a use case document is served without revalidating. */
        val cacheTtl: Duration = 10.seconds,
        /** Whether a background thread revalidates the use case document every [cacheTtl]. */
        val pollingEnabled: Boolean = true,
        val connectTimeout: Duration = 5.seconds,
        val requestTimeout: Duration = 5.seconds,
        /** The first fetch after start-up gets a shorter budget so it can never hold up a boot. */
        val startupFetchTimeout: Duration = 3.seconds,
        /** Mirror every fetched use case document to a local file. */
        val diskCacheEnabled: Boolean = true,
        /** Where that file lives. Defaults to the OS cache directory, named by project and environment. */
        val diskCachePath: Path? = null,
        /** A use case document JSON file shipped inside the app, used when memory and disk are empty. */
        val bundlePath: Path? = null,
        val log: LogOptions = LogOptions(),
        /** Send `sha256(end_user_ref)` instead of the raw reference. */
        val hashEndUser: Boolean = false,
        val mode: PromptOnMode = PromptOnMode.LIVE,
        /** The policy used for a use case whose document carries none. */
        val payloadDefaults: PayloadPolicy = PayloadPolicy.DEFAULT,
        /** Replaced in tests with a stub; `null` means the built-in `java.net.http` transport. */
        val transport: HttpTransport? = null,
    ) {
        init {
            require(environment.isNotBlank()) { "environment must not be blank" }
            require(cacheTtl >= 0.milliseconds) { "cacheTtl must not be negative" }
        }

        /** `<host>/api/v1`. */
        val baseUrl: String get() = host.trimEnd('/') + "/api/v1"

        val userAgent: String get() = "$SDK_NAME/$SDK_VERSION"

        /** The disk cache location actually used, or `null` when the disk tier is off. */
        val resolvedDiskCachePath: Path?
            get() =
                when {
                    !diskCacheEnabled -> null
                    diskCachePath != null -> diskCachePath
                    else -> defaultCacheDirectory().resolve("use-cases-${project ?: "default"}-$environment.json")
                }

        public companion object {
            public const val DEFAULT_HOST: String = "https://app.prompton.ai"
            public const val DEFAULT_ENVIRONMENT: String = "production"
            public const val SDK_NAME: String = "prompton-kotlin"
            public const val SDK_VERSION: String = "0.4.0"

            /** `ptn_<project_slug>_<random>` carries the project slug; that is where the cache file is named from. */
            public fun projectFromApiKey(apiKey: String?): String? {
                val parts = apiKey?.split("_") ?: return null
                if (parts.size < 3 || parts[0] != "ptn") return null
                return parts.subList(1, parts.size - 1).joinToString("_").takeIf { it.isNotBlank() }
            }

            internal fun defaultCacheDirectory(): Path {
                val os = System.getProperty("os.name").orEmpty().lowercase()
                val home = System.getProperty("user.home").orEmpty()
                val root =
                    when {
                        os.contains("mac") && home.isNotBlank() -> Paths.get(home, "Library", "Caches")
                        Env.get("XDG_CACHE_HOME") != null -> Paths.get(Env.get("XDG_CACHE_HOME")!!)
                        home.isNotBlank() -> Paths.get(home, ".cache")
                        else -> Paths.get(System.getProperty("java.io.tmpdir") ?: ".")
                    }
                return root.resolve("prompton")
            }
        }
    }

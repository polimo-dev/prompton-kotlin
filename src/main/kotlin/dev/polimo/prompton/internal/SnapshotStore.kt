package dev.polimo.prompton.internal

import dev.polimo.prompton.UseCaseDocument
import dev.polimo.prompton.UseCaseSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** One use case document plus where it came from and when it was last confirmed current. */
internal data class SnapshotEntry(
    val document: UseCaseDocument,
    val etag: String?,
    val lastModified: String?,
    val source: UseCaseSource,
    /** When this document's bytes were received. */
    val fetchedAt: Instant,
    /** When the server last confirmed the document is current (a 200 or a 304). */
    val validatedAt: Instant,
    /** Set while the server cannot be reached; cleared by the next successful revalidation. */
    val staleSince: Instant?,
)

/**
 * The disk tier.
 *
 * The use case document bytes go to `<path>` and the ETag, `Last-Modified`, project and environment to a
 * `<path>.meta.json` sidecar — the body carries no timestamp, because the ETag is a hash of it.
 * Writes are atomic (temp file, then rename), so several processes on one host can share the file:
 * a reader either sees the old file or the new one, and a partial or corrupt file is ignored rather
 * than raised.
 */
internal object SnapshotFiles {
    fun metaPath(path: Path): Path = path.resolveSibling(path.fileName.toString() + ".meta.json")

    fun read(
        path: Path,
        source: UseCaseSource,
        expectedEnvironment: String,
        expectedProject: String?,
    ): SnapshotEntry? {
        val body =
            try {
                if (!Files.isRegularFile(path)) return null
                String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            } catch (e: IOException) {
                PtnLog.warn("[PromptOn] could not read the ${source.wire} use case document at $path: ${e.message}")
                return null
            }

        val document =
            try {
                UseCaseDocument.parse(body)
            } catch (e: RuntimeException) {
                PtnLog.warn("[PromptOn] ignoring an unreadable ${source.wire} use case document at $path: ${e.message}")
                return null
            }

        if (document.environment != expectedEnvironment) {
            PtnLog.warn(
                "[PromptOn] refusing the ${source.wire} use case document at $path: it is for environment " +
                    "'${document.environment}', this process reads '$expectedEnvironment'",
            )
            return null
        }
        if (expectedProject != null && document.project != null && document.project != expectedProject) {
            PtnLog.warn(
                "[PromptOn] refusing the ${source.wire} use case document at $path: it is for project " +
                    "'${document.project}', this process reads '$expectedProject'",
            )
            return null
        }

        val meta = readMeta(path)
        val fetchedAt = Iso8601.parseOrNull(meta["fetched_at"]) ?: Instant.EPOCH
        return SnapshotEntry(
            document = document,
            etag = meta["etag"],
            lastModified = meta["last_modified"],
            source = source,
            fetchedAt = fetchedAt,
            validatedAt = fetchedAt,
            staleSince = fetchedAt,
        )
    }

    fun write(
        path: Path,
        body: String,
        meta: Map<String, String?>,
    ) {
        try {
            path.parent?.let { Files.createDirectories(it) }
            atomicWrite(path, body)
            val json =
                JsonObject(
                    meta.entries.associate { (key, value) ->
                        key to (value?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
                    },
                )
            atomicWrite(metaPath(path), Ptn.canonicalJson(json))
        } catch (e: IOException) {
            PtnLog.throttled("disk-cache-write", 60_000) {
                "[PromptOn] could not write the use case document disk cache at $path: ${e.message}"
            }
        }
    }

    private fun readMeta(path: Path): Map<String, String?> =
        try {
            val metaPath = metaPath(path)
            if (!Files.isRegularFile(metaPath)) {
                emptyMap()
            } else {
                val text = String(Files.readAllBytes(metaPath), StandardCharsets.UTF_8)
                Ptn
                    .parseObject(text)
                    .entries
                    .associate { (key, value) -> key to Ptn.asString(value)?.takeIf { it.isNotBlank() } }
            }
        } catch (_: IOException) {
            emptyMap()
        } catch (_: RuntimeException) {
            emptyMap()
        }

    private fun atomicWrite(
        path: Path,
        content: String,
    ) {
        val directory = path.parent ?: Path.of(".")
        val temp = Files.createTempFile(directory, path.fileName.toString(), ".tmp")
        try {
            Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }
}

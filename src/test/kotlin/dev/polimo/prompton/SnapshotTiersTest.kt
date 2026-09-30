package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Memory, disk and bundle: the three tiers, their load order, and the guard that stops a process
 * booting on another environment's or another project's configuration.
 */
class SnapshotTiersTest {
    @TempDir
    lateinit var tempDir: Path

    private val downTransport = StubTransport { HttpResponse(503, emptyMap(), "") }

    private fun config(
        transport: HttpTransport?,
        diskCache: Path? = null,
        bundle: Path? = null,
        environment: String = "production",
        project: String? = "fixture",
    ) = PromptOnConfig(
        apiKey = "ptn_fixture_secret",
        host = "https://prompton.test",
        environment = environment,
        project = project,
        cacheTtl = 10.seconds,
        pollingEnabled = false,
        diskCacheEnabled = diskCache != null,
        diskCachePath = diskCache,
        bundlePath = bundle,
        transport = transport,
    )

    @Test
    fun `a fetched snapshot is mirrored to disk with a sidecar`() {
        val diskCache = tempDir.resolve("snapshot.json")
        val transport =
            StubTransport {
                HttpResponse(
                    200,
                    mapOf(
                        "etag" to SnapshotFixtures.PRODUCTION_ETAG,
                        "last-modified" to "Fri, 04 Sep 2026 00:21:48 GMT",
                    ),
                    SnapshotFixtures.useCaseDocument(),
                )
            }
        PromptOn(config(transport, diskCache = diskCache), FakeClock()).use { prompton ->
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
        }

        val listing = Files.list(tempDir).use { it.map { path -> path.fileName.toString() }.sorted().toList() }
        assertTrue(Files.exists(diskCache), "disk cache missing, directory holds $listing")
        val sidecar = tempDir.resolve("snapshot.json.meta.json")
        assertTrue(Files.exists(sidecar), "sidecar missing, directory holds $listing")
        val body = Files.readString(diskCache)
        val entry = Ptn.asObject(Ptn.asObject(Ptn.parseObject(body)["entries"])!!["greeting"])!!
        val meta = Ptn.asObject(entry["meta"])!!
        assertEquals(SnapshotFixtures.PRODUCTION_ETAG, Ptn.asString(meta["etag"]))
        assertEquals("production", Ptn.asString(meta["environment"]))
        assertEquals("fixture", Ptn.asString(meta["project"]))
        assertTrue(listing.none { it.contains(".tmp") }, "a temp file was left behind: $listing")
    }

    @Test
    fun `with the server down the disk cache still resolves`() {
        val diskCache = tempDir.resolve("snapshot.json")
        Files.writeString(diskCache, SnapshotFixtures.useCaseDocument())

        PromptOn(config(downTransport, diskCache = diskCache), FakeClock()).use { prompton ->
            val useCase = prompton.useCase("greeting")
            assertEquals(UseCaseSource.DISK, useCase.source)
            assertEquals("openai/gpt-4o-mini", useCase.model)
            assertEquals("Say hello to Ada.", useCase.messages(mapOf("name" to "Ada"))[1].content)
        }
    }

    @Test
    fun `with no disk cache the bundle resolves`() {
        val bundle = tempDir.resolve("prompts.production.json")
        Files.writeString(bundle, SnapshotFixtures.useCaseDocument())

        PromptOn(config(downTransport, bundle = bundle), FakeClock()).use { prompton ->
            val useCase = prompton.useCase("greeting")
            assertEquals(UseCaseSource.BUNDLE, useCase.source)
            assertEquals("openai/gpt-4o-mini", useCase.model)
        }
    }

    @Test
    fun `the disk cache is preferred over the bundle`() {
        val diskCache = tempDir.resolve("snapshot.json")
        val bundle = tempDir.resolve("bundle.json")
        Files.writeString(diskCache, SnapshotFixtures.useCaseDocument(temperature = 0.7))
        Files.writeString(bundle, SnapshotFixtures.useCaseDocument(temperature = 0.1))

        PromptOn(config(downTransport, diskCache = diskCache, bundle = bundle), FakeClock()).use { prompton ->
            val useCase = prompton.useCase("greeting")
            assertEquals(UseCaseSource.DISK, useCase.source)
            assertEquals(0.7, useCase.params["temperature"])
        }
    }

    @Test
    fun `a corrupt file is ignored rather than raised`() {
        val diskCache = tempDir.resolve("snapshot.json")
        val bundle = tempDir.resolve("bundle.json")
        Files.writeString(diskCache, "{\"schema_version\": 3, \"use_ca")
        Files.writeString(bundle, SnapshotFixtures.useCaseDocument())

        PromptOn(config(downTransport, diskCache = diskCache, bundle = bundle), FakeClock()).use { prompton ->
            assertEquals(UseCaseSource.BUNDLE, prompton.useCase("greeting").source)
        }
    }

    @Test
    fun `a snapshot for another environment is never used`() {
        val bundle = tempDir.resolve("bundle.json")
        Files.writeString(bundle, SnapshotFixtures.useCaseDocument(environment = "staging"))

        PromptOn(config(downTransport, bundle = bundle), FakeClock()).use { prompton ->
            assertFailsWith<UseCaseDocumentUnavailableException> { prompton.useCase("greeting") }
        }
    }

    @Test
    fun `a snapshot for another project is never used`() {
        val bundle = tempDir.resolve("bundle.json")
        Files.writeString(bundle, SnapshotFixtures.useCaseDocument(project = "someone-else"))

        PromptOn(config(downTransport, bundle = bundle), FakeClock()).use { prompton ->
            assertFailsWith<UseCaseDocumentUnavailableException> { prompton.useCase("greeting") }
        }
    }

    @Test
    fun `schema version must be exactly v4`() {
        val bundle = tempDir.resolve("bundle.json")
        Files.writeString(
            bundle,
            SnapshotFixtures.useCaseDocument().replace("\"schema_version\": 4", "\"schema_version\": 3"),
        )

        PromptOn(config(downTransport, bundle = bundle), FakeClock()).use { prompton ->
            assertFailsWith<UseCaseDocumentUnavailableException> { prompton.useCase("greeting") }
        }
        assertFailsWith<UnsupportedSchemaVersionException> {
            UseCaseDocument.parse(
                SnapshotFixtures.useCaseDocument().replace("\"schema_version\": 4", "\"schema_version\": 3"),
            )
        }
        assertFailsWith<UnsupportedSchemaVersionException> {
            UseCaseDocument.parse(
                SnapshotFixtures.useCaseDocument().replace("\"schema_version\": 4", "\"schema_version\": 8"),
            )
        }
        assertFailsWith<PromptOnException> {
            UseCaseDocument.parse(
                SnapshotFixtures.useCaseDocument().replace("\"schema_version\": 4", "\"version\": 4"),
            )
        }
        assertFailsWith<PromptOnException> {
            UseCaseDocument.parse(
                SnapshotFixtures.useCaseDocument().replace("\"schema_version\": 4,\n", ""),
            )
        }
        assertFailsWith<PromptOnException> {
            UseCaseDocument.parse(
                SnapshotFixtures.useCaseDocument().replace("\"schema_version\": 4", "\"schema_version\": \"4\""),
            )
        }
        assertFailsWith<PromptOnException> {
            UseCaseDocument.parse(
                SnapshotFixtures.useCaseDocument().replace("\"schema_version\": 4", "\"schema_version\": 4.0"),
            )
        }
    }

    @Test
    fun `concurrent writers never expose a partial file`() {
        val diskCache = tempDir.resolve("shared.json")
        val writer =
            Thread {
                repeat(60) { index ->
                    dev.polimo.prompton.internal.SnapshotFiles.write(
                        diskCache,
                        SnapshotFixtures.useCaseDocument(temperature = if (index % 2 == 0) 0.1 else 0.9),
                        mapOf("etag" to "\"sha256-$index\"", "environment" to "production", "project" to "fixture"),
                    )
                }
            }
        writer.start()

        var reads = 0
        while (writer.isAlive || reads < 20) {
            val entry =
                dev.polimo.prompton.internal.SnapshotFiles
                    .read(diskCache, UseCaseSource.DISK, "production", "fixture")
            if (entry != null) {
                assertEquals("production", entry.document.environment)
                reads += 1
            }
            if (!writer.isAlive && reads >= 20) break
        }
        writer.join()
        assertTrue(reads >= 20, "expected at least 20 clean reads, got $reads")
    }

    @Test
    fun `offline mode never touches the network`() {
        val bundle = tempDir.resolve("bundle.json")
        Files.writeString(bundle, SnapshotFixtures.useCaseDocument())
        val transport = StubTransport { error("offline mode must not make requests") }

        val config =
            config(transport, bundle = bundle).copy(mode = PromptOnMode.OFFLINE)
        PromptOn(config, FakeClock()).use { prompton ->
            assertEquals(UseCaseSource.BUNDLE, prompton.useCase("greeting").source)
            prompton.log(
                LogRecord(
                    useCase = "greeting",
                    model = "openai/gpt-4o-mini",
                    status = LogStatus.OK,
                    startedAt = java.time.Instant.parse("2026-09-04T09:00:00Z"),
                ),
            )
            assertEquals(0, transport.requestCount())
        }
    }

    @Test
    fun `without an api key nothing is fetched and the disk tier still serves`() {
        val diskCache = tempDir.resolve("snapshot.json")
        Files.writeString(diskCache, SnapshotFixtures.useCaseDocument())
        val transport = StubTransport { error("no API key means no requests") }

        val config = config(transport, diskCache = diskCache).copy(apiKey = null)
        PromptOn(config, FakeClock()).use { prompton ->
            assertEquals(UseCaseSource.DISK, prompton.useCase("greeting").source)
            assertEquals(0, transport.requestCount())
        }
    }
}

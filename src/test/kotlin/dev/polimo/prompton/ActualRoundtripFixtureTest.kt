package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Optional check against server-captured flat schema-v9 fixtures. */
class ActualRoundtripFixtureTest {
    private val fixtures: Path =
        Path.of(
            System.getProperty(
                "promptonRoundtripFixtures",
                "/Users/ys/ws/.omx/artifacts/single-prompt-20261014/roundtrip",
            ),
        )

    @Test
    fun `decodes actual flat schema 9 chat and matches server render request`() {
        assumeTrue(Files.isDirectory(fixtures))
        val useCase =
            Resolver.resolve(
                UseCaseDocument.parse(Files.readString(fixtures.resolve("chat-config.json"))),
                "support_reply",
            )
        val request = useCase.request(readMap("chat-variables.json"))
        val expected = (readMap("chat-render.json")["request"] as Map<*, *>)["body"]

        assertEquals("chat_completions", request.api)
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals(expected, request.body)
    }

    @Test
    fun `decodes actual flat schema 9 Decision and matches server render request`() {
        assumeTrue(Files.isDirectory(fixtures))
        val useCase =
            Resolver.resolve(
                UseCaseDocument.parse(Files.readString(fixtures.resolve("decision-config.json"))),
                "decision_triage",
            )
        val request = useCase.request(readMap("decision-variables.json"))
        val expected = (readMap("decision-render.json")["request"] as Map<*, *>)["body"]

        assertEquals("decisions", request.api)
        assertEquals("/api/v1/systemone", request.path)
        assertEquals(expected, request.body)
    }

    private fun readMap(file: String): Map<String, Any?> = Ptn.toNativeMap(
        Ptn.parseObject(Files.readString(fixtures.resolve(file))),
    )
}

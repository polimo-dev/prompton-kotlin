package dev.polimo.prompton

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TemplateTest {
    @Test
    fun `message slots are rejected before rendering for every engine`() {
        val slotWithRole =
            PromptMessage(
                role = "system",
                content = "ignored",
                name = "history",
                type = "slot",
            )

        for (engine in TemplateEngine.entries) {
            val thrown =
                assertFailsWith<TemplateRenderException> {
                    Template.renderMessages(
                        listOf(slotWithRole),
                        mapOf("history" to listOf(mapOf("role" to "user", "content" to "hi"))),
                        engine,
                    )
                }
            assertEquals(Template.MESSAGE_SLOT_ERROR, thrown.reason)
        }
    }

    @Test
    fun `history is an ordinary variable name`() {
        val rendered =
            Template.renderMessages(
                listOf(PromptMessage(role = "system", content = "Remember {{ history }}.")),
                mapOf("history" to "the user prefers concise replies"),
            )

        assertEquals("Remember the user prefers concise replies.", rendered.single().content)
    }

    @Test
    fun `native message type name and structured content survive rendering`() {
        val nativeMessage =
            PromptMessage(
                role = "assistant",
                content = "",
                name = "lookup",
                type = "message",
                contentValue = listOf(mapOf("type" to "text", "text" to "found")),
                hasContent = true,
                extra = mapOf("provider_extra" to mapOf("opaque" to true)),
            )

        val rendered = Template.renderMessages(listOf(nativeMessage), mapOf("history" to "ignored")).single()

        assertEquals(nativeMessage, rendered)
    }

    @Test
    fun `native messages without content keep content absent during rendering`() {
        val absent =
            PromptMessage(
                role = "assistant",
                content = "",
                name = "helper",
                type = "native",
                hasContent = false,
                extra = mapOf("reasoning" to "opaque"),
            )
        val explicitNull =
            PromptMessage(role = "assistant", content = "", contentValue = null, hasContent = true)
        val emptyString = PromptMessage(role = "assistant", content = "")
        val emptyArray =
            PromptMessage(
                role = "assistant",
                content = "",
                contentValue = emptyList<Any?>(),
                hasContent = true,
            )

        val rendered = Template.renderMessages(listOf(absent, explicitNull, emptyString, emptyArray))

        assertEquals(absent, rendered[0])
        assertEquals(explicitNull, rendered[1])
        assertEquals(emptyString, rendered[2])
        assertEquals(emptyArray, rendered[3])
    }
}

package dev.polimo.prompton.conformance

import dev.polimo.prompton.MissingVariableException
import dev.polimo.prompton.Template
import dev.polimo.prompton.TemplateEngine
import dev.polimo.prompton.TemplateParseException
import dev.polimo.prompton.TemplateRenderException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs every case of `conformance/template.json`.
 *
 * Cases marked `"normative": false` are reference-implementation behaviour other SDKs need not
 * reproduce; they are counted and skipped.
 */
class TemplateConformanceTest {
    @Test
    fun `render cases`() {
        val cases = Conformance.cases("template")
        var executed = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        for (case in cases) {
            val name = Conformance.string(case, "name")!!
            if ((case["normative"] as? JsonPrimitive)?.content == "false") {
                skipped += 1
                continue
            }
            executed += 1
            val template = Conformance.string(case, "template")!!
            val variables = Conformance.nativeMap(case["variables"])
            val engine =
                if (Conformance.string(case, "engine") == "raw") TemplateEngine.RAW else TemplateEngine.LIQUID
            val expect = case["expect"] as JsonObject
            val expectedOutput = Conformance.string(expect, "output")
            val expectedError = Conformance.string(expect, "error")

            try {
                val output = Template.render(template, variables, engine)
                if (expectedError != null) {
                    failures += "$name: expected error $expectedError but rendered ${quote(output)}"
                } else if (output != expectedOutput) {
                    failures += "$name: expected ${quote(expectedOutput)} but got ${quote(output)}"
                }
            } catch (e: MissingVariableException) {
                checkError(name, expectedError, "missing_variable", failures) {
                    val expectedVariable = Conformance.string(expect, "variable")
                    if (expectedVariable != null && expectedVariable != e.variable) {
                        failures += "$name: expected missing variable $expectedVariable but got ${e.variable}"
                    }
                }
            } catch (_: TemplateParseException) {
                checkError(name, expectedError, "parse_error", failures) {}
            } catch (_: TemplateRenderException) {
                checkError(name, expectedError, "render_error", failures) {}
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(72, executed + skipped, "the suite must run every case in the file")
        assertTrue(executed >= 68, "expected at least 68 normative render cases, ran $executed")
    }

    @Test
    fun `lint cases`() {
        for (case in Conformance.cases("template", "lint_cases")) {
            val name = Conformance.string(case, "name")!!
            val template = Conformance.string(case, "template")!!
            val expect = case["expect"] as JsonObject
            val reasons = Template.lint(template)
            if (Conformance.string(expect, "lint") == "ok") {
                assertEquals(emptyList(), reasons, name)
            } else {
                val expected =
                    (expect["reasons"] as JsonArray).map { reason ->
                        val obj = reason as JsonObject
                        Conformance.string(obj, "kind") to Conformance.string(obj, "value")
                    }
                assertEquals(expected, reasons.map { it.kind to it.value }, name)
            }
        }
    }

    @Test
    fun `detected variables cases`() {
        for (case in Conformance.cases("template", "variables_cases")) {
            val name = Conformance.string(case, "name")!!
            val template = Conformance.string(case, "template")!!
            val expect = case["expect"] as JsonObject
            val expected = (expect["variables"] as JsonArray).map { (it as JsonPrimitive).content }
            assertEquals(expected, Template.variables(template), name)
        }
    }

    private fun checkError(
        name: String,
        expectedError: String?,
        actualError: String,
        failures: MutableList<String>,
        extra: () -> Unit,
    ) {
        if (expectedError == null) {
            failures += "$name: expected output but got $actualError"
        } else if (expectedError != actualError) {
            failures += "$name: expected $expectedError but got $actualError"
        } else {
            extra()
        }
    }

    private fun quote(value: String?): String = value?.let { "\"$it\"" } ?: "null"
}

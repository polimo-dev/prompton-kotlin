package dev.polimo.prompton

import dev.polimo.prompton.internal.Env
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Configuration precedence: an explicit option, then the environment variable, then the default. */
class ConfigTest {
    @AfterEach
    fun restoreEnvironment() {
        Env.reader = { System.getenv(it) }
    }

    private fun withEnvironment(values: Map<String, String>) {
        Env.reader = { name -> values[name] }
    }

    @Test
    fun `defaults apply with nothing configured`() {
        withEnvironment(emptyMap())
        val config = PromptOnConfig()
        assertNull(config.apiKey)
        assertEquals("https://app.prompton.ai", config.host)
        assertEquals("https://app.prompton.ai/api/v1", config.baseUrl)
        assertEquals("production", config.environment)
        assertEquals(10_000, config.cacheTtl.inWholeMilliseconds)
        assertEquals("prompton-kotlin/0.2.0", config.userAgent)
        assertEquals(PromptOnMode.LIVE, config.mode)
        assertTrue(config.diskCacheEnabled)
    }

    @Test
    fun `environment variables beat the defaults`() {
        withEnvironment(
            mapOf(
                "PTN_HOST" to "https://prompton.example",
                "PTN_API_KEY" to "ptn_acme_secret",
                "PTN_ENVIRONMENT" to "staging",
            ),
        )
        val config = PromptOnConfig()
        assertEquals("https://prompton.example", config.host)
        assertEquals("https://prompton.example/api/v1", config.baseUrl)
        assertEquals("ptn_acme_secret", config.apiKey)
        assertEquals("staging", config.environment)
        assertEquals("acme", config.project, "the project slug comes from the key")
    }

    @Test
    fun `an explicit option beats the environment variable`() {
        withEnvironment(mapOf("PTN_HOST" to "https://from-env.example", "PTN_ENVIRONMENT" to "staging"))
        val config = PromptOnConfig(host = "https://explicit.example", environment = "production")
        assertEquals("https://explicit.example", config.host)
        assertEquals("production", config.environment)
    }

    @Test
    fun `a trailing slash on the host does not double up`() {
        withEnvironment(emptyMap())
        assertEquals("https://prompton.example/api/v1", PromptOnConfig(host = "https://prompton.example/").baseUrl)
    }

    @Test
    fun `the project slug is read out of the api key`() {
        assertEquals("sdkfixture", PromptOnConfig.projectFromApiKey("ptn_sdkfixture_0000test0000"))
        assertEquals("my_app", PromptOnConfig.projectFromApiKey("ptn_my_app_abc123"))
        assertNull(PromptOnConfig.projectFromApiKey("not-a-prompton-key"))
        assertNull(PromptOnConfig.projectFromApiKey(null))
    }

    @Test
    fun `the default disk cache path is named by project and environment`() {
        withEnvironment(emptyMap())
        val path = PromptOnConfig(apiKey = "ptn_acme_secret", environment = "staging").resolvedDiskCachePath
        assertTrue(path.toString().endsWith("prompton/use-cases-acme-staging.json"), path.toString())
    }

    @Test
    fun `the disk tier can be switched off`() {
        withEnvironment(emptyMap())
        assertNull(PromptOnConfig(diskCacheEnabled = false).resolvedDiskCachePath)
    }

    @Test
    fun `log options refuse impossible values`() {
        val error = kotlin.runCatching { LogOptions(maxBatchSize = 500) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected a rejection, got $error")
        assertTrue(kotlin.runCatching { LogOptions(maxBatchBytes = 6_000_000) }.isFailure)
        assertTrue(kotlin.runCatching { LogOptions(flushSize = 0) }.isFailure)
    }

    @Test
    fun `a blank environment is refused`() {
        assertTrue(kotlin.runCatching { PromptOnConfig(environment = " ") }.isFailure)
    }
}

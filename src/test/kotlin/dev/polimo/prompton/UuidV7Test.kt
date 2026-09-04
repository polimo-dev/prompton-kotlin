package dev.polimo.prompton

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The monitoring-log id is a UUIDv7.
 *
 * Request validation accepts any UUID, but the ingest column is a UUIDv7 type, so a v4 id fails on
 * write and comes back in `rejected` saying only "record could not be stored".
 */
class UuidV7Test {
    private val format = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `the layout is version 7 and variant 10`() {
        repeat(200) {
            val uuid = UuidV7.generate()
            assertTrue(format.matches(uuid), "not a UUIDv7: $uuid")
        }
    }

    @Test
    fun `the timestamp is the first 48 bits`() {
        val millis = 1_788_000_000_000L
        val uuid = UuidV7.generate(millis)
        assertEquals(millis, UuidV7.timestampMillis(uuid))
        assertTrue(uuid.startsWith("01a04d1a-d800"), uuid)
    }

    @Test
    fun `ids from different milliseconds sort by time`() {
        val early = UuidV7.generate(1_788_000_000_000L)
        val later = UuidV7.generate(1_788_000_000_001L)
        assertTrue(early < later, "$early should sort before $later")
    }

    @Test
    fun `ids are unique`() {
        val ids = (1..10_000).map { UuidV7.generate() }.toSet()
        assertEquals(10_000, ids.size)
    }

    @Test
    fun `a string that is not a uuid has no timestamp`() {
        assertNull(UuidV7.timestampMillis("not-a-uuid"))
        assertNotNull(UuidV7.timestampMillis(UuidV7.generate()))
    }

    @Test
    fun `java's own parser accepts it`() {
        val uuid = java.util.UUID.fromString(UuidV7.generate())
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }
}

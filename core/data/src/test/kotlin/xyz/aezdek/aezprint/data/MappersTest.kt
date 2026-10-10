package xyz.aezdek.aezprint.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.aezdek.aezprint.data.network.dto.JobDto
import xyz.aezdek.aezprint.data.network.dto.PrinterDto
import xyz.aezdek.aezprint.data.network.dto.duplexSupport
import xyz.aezdek.aezprint.data.network.dto.epochMillis
import xyz.aezdek.aezprint.data.network.dto.mediaFrom
import xyz.aezdek.aezprint.data.network.dto.printerStatus
import xyz.aezdek.aezprint.data.network.dto.toDomain
import xyz.aezdek.aezprint.data.network.dto.toDto
import xyz.aezdek.aezprint.domain.model.BookletOptions
import xyz.aezdek.aezprint.domain.model.BookletSubset
import xyz.aezdek.aezprint.domain.model.ContentMode
import xyz.aezdek.aezprint.domain.model.Duplex
import xyz.aezdek.aezprint.domain.model.DuplexSupport
import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.PageSelection
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.model.PrinterStatus
import xyz.aezdek.aezprint.domain.model.Sizing
import xyz.aezdek.aezprint.domain.model.SizingMode

class MappersTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun `printer state handles ipp reasons and friendly names`() {
        assertEquals(PrinterStatus.IDLE, printerStatus("idle", emptyList()))
        assertEquals(PrinterStatus.PRINTING, printerStatus("processing", emptyList()))
        assertEquals(PrinterStatus.OUT_OF_PAPER, printerStatus("stopped", listOf("media-empty-error")))
        assertEquals(PrinterStatus.JAM, printerStatus("stopped", listOf("media-jam-error")))
        assertEquals(PrinterStatus.OFFLINE, printerStatus("stopped", listOf("offline-report")))
        assertEquals(PrinterStatus.OUT_OF_PAPER, printerStatus("out_of_paper", emptyList()))
        assertEquals(PrinterStatus.OFFLINE, printerStatus("offline", emptyList()))
    }

    @Test
    fun `duplex capability accepts booleans and strings`() {
        assertEquals(DuplexSupport.MANUAL, duplexSupport(JsonPrimitive(true)))
        assertEquals(DuplexSupport.NONE, duplexSupport(JsonPrimitive(false)))
        assertEquals(DuplexSupport.MANUAL, duplexSupport(JsonPrimitive("manual")))
        assertEquals(DuplexSupport.NONE, duplexSupport(null))
    }

    @Test
    fun `media accepts pwg strings and objects`() {
        assertEquals("A4", mediaFrom(JsonPrimitive("iso_a4_210x297mm"))?.displayName)
        val custom = mediaFrom(buildJsonObject {
            put("id", "custom_receipt")
            put("label", "Receipt")
            put("widthMm", 80)
            put("heightMm", 200)
        })!!
        assertEquals("Receipt", custom.displayName)
        assertEquals(80.0, custom.widthMm, 0.01)
    }

    @Test
    fun `full printer payload decodes`() {
        val payload = """
            {"name":"ML-2165","state":"idle","stateReasons":[],"accepting":true,
             "media":["iso_a4_210x297mm","na_letter_8.5x11in"],
             "capabilities":{"color":false,"duplex":"manual","resolutions":[600,"1200dpi"]}}
        """.trimIndent()
        val printer = json.decodeFromString<PrinterDto>(payload).toDomain()
        assertEquals(2, printer.media.size)
        assertEquals(listOf(600, 1200), printer.capabilities.resolutions)
        assertEquals(DuplexSupport.MANUAL, printer.capabilities.duplex)
    }

    @Test
    fun `options serialize with contract names`() {
        val options = PrintOptions(
            pages = PageSelection.Custom(" 1-3, 7 "),
            sizing = Sizing(mode = SizingMode.BOOKLET),
            booklet = BookletOptions(BookletSubset.FRONT_ONLY),
            duplex = Duplex.SHORT_EDGE,
            content = ContentMode.FORM_FIELDS_ONLY,
        )
        val encoded = json.encodeToString(options.toDto())
        listOf(
            "\"pageRange\":\"1-3,7\"",
            "\"mode\":\"booklet\"",
            "\"subset\":\"frontOnly\"",
            "\"duplex\":\"shortEdge\"",
            "\"content\":\"formFieldsOnly\"",
            "\"tonerSave\":false",
        ).forEach { assert(it in encoded) { "$it missing from $encoded" } }
    }

    @Test
    fun `all pages omits the page range`() {
        assertNull(PrintOptions().toDto().pageRange)
    }

    @Test
    fun `job maps unknown states to failed with a message`() {
        val job = JobDto(state = "on_fire").toDomain("job_1")
        assertEquals(JobState.FAILED, job.state)
        assertEquals("job_1", job.id)
    }

    @Test
    fun `expiry accepts seconds, millis and iso dates`() {
        assertEquals(1_700_000_000_000, epochMillis(JsonPrimitive(1_700_000_000)))
        assertEquals(1_700_000_000_000, epochMillis(JsonPrimitive(1_700_000_000_000)))
        assertEquals(0L, epochMillis(JsonPrimitive("2023-11-14T22:13:20Z")) - 1_700_000_000_000)
    }
}

package xyz.aezdek.aezprint.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SetupCodeTest {

    @Test
    fun `parses json payload`() {
        val code = SetupCode.parse("""{"url":"https://print.aezdek.xyz","clientId":"a.access","clientSecret":"s","apiKey":"k"}""")!!
        assertEquals("https://print.aezdek.xyz", code.url)
        assertEquals("a.access", code.clientId)
    }

    @Test
    fun `parses link payload with encoded values`() {
        val code = SetupCode.parse("aezprint://setup?url=https%3A%2F%2Fprint.example&clientId=id&clientSecret=a%2Bb&apiKey=k")!!
        assertEquals("https://print.example", code.url)
        assertEquals("a+b", code.clientSecret)
    }

    @Test
    fun `rejects incomplete or insecure payloads`() {
        assertNull(SetupCode.parse("""{"clientId":"a"}"""))
        assertNull(SetupCode.parse("hello"))
        assertNull(SetupCode.parse("""{"url":"http://x","clientId":"a","clientSecret":"b","apiKey":"c"}""")?.url)
    }

    @Test
    fun `secrets are not printed`() {
        val code = SetupCode.parse("""{"clientId":"a","clientSecret":"topsecret","apiKey":"k"}""")!!
        assertFalse(code.toString().contains("topsecret"))
    }
}

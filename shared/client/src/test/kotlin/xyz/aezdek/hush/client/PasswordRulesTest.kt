package xyz.aezdek.hush.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PasswordRulesTest {
    /** The same answers as the web app, case for case (crypto-vectors/password-rules.json). */
    @Test
    fun sameAnswersAsTheWebApp() {
        val file = File(System.getProperty("hush.vectorsDir") ?: "../../crypto-vectors", "password-rules.json")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray
        for (case in cases.map { it.jsonObject }) {
            val password = case["password"]!!.jsonPrimitive.content
            val expected = case["result"]!!.jsonPrimitive.content
            val got = PasswordRules.check(password, case["username"]!!.jsonPrimitive.content)?.name?.lowercase() ?: "ok"
            assertEquals("for \"$password\"", expected, got)
        }
    }

    @Test
    fun theListShipsWithTheCode() {
        assertEquals(PasswordRules.Problem.COMMON, PasswordRules.check("Sunshine#2024"))
    }
}

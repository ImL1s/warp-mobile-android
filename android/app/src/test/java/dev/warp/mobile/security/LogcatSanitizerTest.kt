package dev.warp.mobile.security

import org.junit.Assert.assertEquals
import org.junit.Test

class LogcatSanitizerTest {

    @Test
    fun testEmptyString() {
        assertEquals("", LogcatSanitizer.sanitize(""))
    }

    @Test
    fun testNullString() {
        assertEquals("", LogcatSanitizer.sanitize(null))
    }

    @Test
    fun testNoFalsePositives() {
        val msg = "This is a normal message with some data like skip and token not matching."
        assertEquals(msg, LogcatSanitizer.sanitize(msg))
    }

    @Test
    fun testAnthropicApiKeySanitized() {
        val msg = "Testing api key sk-ant-api03-ABC123def456 here"
        val expected = "Testing api key ***REDACTED*** here"
        assertEquals(expected, LogcatSanitizer.sanitize(msg))
    }

    @Test
    fun testGenericApiKeySanitized() {
        val msg = "My key is sk-12345ABCD"
        val expected = "My key is ***REDACTED***"
        assertEquals(expected, LogcatSanitizer.sanitize(msg))
    }

    @Test
    fun testTokenSanitized() {
        val msg = "Token token_9876xyz received"
        val expected = "Token ***REDACTED*** received"
        assertEquals(expected, LogcatSanitizer.sanitize(msg))
    }

    @Test
    fun testKeySanitized() {
        val msg = "Use key_abc123 to login"
        val expected = "Use ***REDACTED*** to login"
        assertEquals(expected, LogcatSanitizer.sanitize(msg))
    }

    @Test
    fun testBearerSanitized() {
        val msg = "Authorization: Bearer eyJhbGci.eyJzdWIi.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val expected = "Authorization: ***REDACTED***"
        assertEquals(expected, LogcatSanitizer.sanitize(msg))
    }

    @Test
    fun testPasswordSanitized() {
        val msg = "Connecting with password=super_secret!123"
        val expected = "Connecting with ***REDACTED***"
        assertEquals(expected, LogcatSanitizer.sanitize(msg))
    }

    @Test
    fun testMultipleSecretsSanitized() {
        val msg = "Bearer token123 and sk-ant-api03-test and password=pass"
        val expected = "***REDACTED*** and ***REDACTED*** and ***REDACTED***"
        assertEquals(expected, LogcatSanitizer.sanitize(msg))
    }
}

package dev.warp.mobile.ai

import dev.warp.mobile.AiKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiKeyStoreTest {

    @Test
    fun testAiKeyStore_redact_formatsBothAnthropicAndOpenAi() {
        val anthropicKey = "sk-ant-api03-1234567890abcdefghijklmnopqrstuvwxyz"
        val redactedAnt = AiKeyStore.redact(anthropicKey)
        assertTrue(redactedAnt.startsWith("Bearer sk-ant-a"))
        assertTrue(redactedAnt.endsWith("wxyz"))
        assertTrue(redactedAnt.contains("***..."))

        val openAiKey = "sk-proj-1234567890abcdef"
        val redactedOai = AiKeyStore.redact(openAiKey)
        assertTrue(redactedOai.startsWith("Bearer sk-proj-"))
        assertTrue(redactedOai.endsWith("cdef"))
        assertTrue(redactedOai.contains("***..."))

        assertEquals("(no key)", AiKeyStore.redact(null))
        assertEquals("(no key)", AiKeyStore.redact(""))
    }

    @Test
    fun testAiKeyStore_redact_handlesShortKey() {
        val shortKey = "abcd"
        val redacted = AiKeyStore.redact(shortKey)
        assertTrue(redacted.startsWith("Bearer abcd"))
    }
}

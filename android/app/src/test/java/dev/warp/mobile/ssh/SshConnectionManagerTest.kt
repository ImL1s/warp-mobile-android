package dev.warp.mobile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionManagerTest {

    @Test
    fun testConnectionConfig_validHostPort() {
        val config = SshConnectionConfig(
            host = "192.168.1.1",
            port = 22,
            username = "root",
            authMethod = SshAuthMethod.Password("password")
        )
        assertEquals("192.168.1.1", config.host)
        assertEquals(22, config.port)
        assertEquals("root", config.username)
    }

    @Test
    fun testAuthMethod_passwordCreation() {
        val auth = SshAuthMethod.Password("secret")
        assertEquals("secret", auth.value)
    }

    @Test
    fun testAuthMethod_publicKeyCreation() {
        val auth = SshAuthMethod.PublicKey("/path/to/key", "passphrase")
        assertEquals("/path/to/key", auth.keyPath)
        assertEquals("passphrase", auth.passphrase)
    }

    @Test
    fun testHostKeyVerification_unknownKey() {
        val verification = SshHostKeyVerification.Unknown("ab:cd:ef:12")
        assertEquals("ab:cd:ef:12", verification.fingerprint)
    }

    @Test
    fun testSessionState_disconnectedByDefault() {
        val manager = SshConnectionManager()
        assertFalse(manager.isConnected)
    }
}

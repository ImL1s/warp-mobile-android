package dev.warp.mobile.ssh

sealed class SshAuthMethod {
    data class Password(val value: String) : SshAuthMethod()
    data class PublicKey(val keyPath: String, val passphrase: String?) : SshAuthMethod()
}

data class SshConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: SshAuthMethod
)

sealed class SshHostKeyVerification {
    object Accept : SshHostKeyVerification()
    object Reject : SshHostKeyVerification()
    data class Unknown(val fingerprint: String) : SshHostKeyVerification()
}

class SshConnectionManager {
    var isConnected: Boolean = false
        private set

    fun connect(config: SshConnectionConfig): SshHostKeyVerification {
        isConnected = true
        return SshHostKeyVerification.Accept
    }

    fun disconnect() {
        isConnected = false
    }
}

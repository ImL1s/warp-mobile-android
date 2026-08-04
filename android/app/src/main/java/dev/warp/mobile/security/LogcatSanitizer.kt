package dev.warp.mobile.security

object LogcatSanitizer {
    private val PATTERNS = listOf(
        Regex("sk-ant-api03-[a-zA-Z0-9_-]+"),
        Regex("sk-[a-zA-Z0-9_-]+"),
        Regex("key_[a-zA-Z0-9_-]+"),
        Regex("token_[a-zA-Z0-9_-]+"),
        Regex("Bearer\\s+[a-zA-Z0-9_\\-\\.]+"),
        Regex("password=[a-zA-Z0-9_\\-\\.\\!@#\\$%\\^&\\*]+")
    )

    fun sanitize(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        var sanitized: String = message
        for (pattern in PATTERNS) {
            sanitized = pattern.replace(sanitized, "***REDACTED***")
        }
        return sanitized
    }
}

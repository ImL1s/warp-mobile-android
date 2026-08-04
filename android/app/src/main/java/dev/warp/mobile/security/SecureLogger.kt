package dev.warp.mobile.security

import android.util.Log

object SecureLogger {
    fun d(tag: String, message: String) {
        Log.d(tag, LogcatSanitizer.sanitize(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, LogcatSanitizer.sanitize(message))
    }

    fun w(tag: String, message: String) {
        Log.w(tag, LogcatSanitizer.sanitize(message))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, LogcatSanitizer.sanitize(message), throwable)
        } else {
            Log.e(tag, LogcatSanitizer.sanitize(message))
        }
    }
}

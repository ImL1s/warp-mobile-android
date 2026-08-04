package dev.warp.mobile

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class WarpBlockState(
    val id: String,
    val command: String,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val output: String = "",
    val isRunning: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(jsonObj: JSONObject): WarpBlockState {
            val id = try {
                if (jsonObj.has("id") && !jsonObj.isNull("id")) jsonObj.optString("id", "") else ""
            } catch (e: Throwable) { "" }

            val command = try {
                if (jsonObj.has("command") && !jsonObj.isNull("command")) jsonObj.optString("command", "") else ""
            } catch (e: Throwable) { "" }

            val exitCode = try {
                if (jsonObj.has("exit_code") && !jsonObj.isNull("exit_code")) {
                    val raw = jsonObj.get("exit_code")
                    when (raw) {
                        is Number -> raw.toInt()
                        is String -> raw.toIntOrNull()
                        else -> null
                    }
                } else null
            } catch (e: Throwable) { null }

            val startTimeMs = try {
                if (jsonObj.has("start_time_unix_ms") && !jsonObj.isNull("start_time_unix_ms")) {
                    val raw = jsonObj.get("start_time_unix_ms")
                    when (raw) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                } else 0L
            } catch (e: Throwable) { 0L }

            val timestamp = try {
                if (jsonObj.has("timestamp") && !jsonObj.isNull("timestamp")) {
                    val raw = jsonObj.get("timestamp")
                    when (raw) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull() ?: startTimeMs
                        else -> startTimeMs
                    }
                } else startTimeMs
            } catch (e: Throwable) { startTimeMs }

            val durationMs = try {
                if (jsonObj.has("duration_ms") && !jsonObj.isNull("duration_ms")) {
                    val raw = jsonObj.get("duration_ms")
                    when (raw) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull()
                        else -> null
                    }
                } else if (jsonObj.has("end_time_unix_ms") && !jsonObj.isNull("end_time_unix_ms")) {
                    val rawEnd = jsonObj.get("end_time_unix_ms")
                    val endMs = when (rawEnd) {
                        is Number -> rawEnd.toLong()
                        is String -> rawEnd.toLongOrNull()
                        else -> null
                    }
                    if (endMs != null && endMs >= timestamp) endMs - timestamp else null
                } else {
                    null
                }
            } catch (e: Throwable) { null }

            val isRunning = try {
                if (jsonObj.has("is_running") && !jsonObj.isNull("is_running")) {
                    val raw = jsonObj.get("is_running")
                    when (raw) {
                        is Boolean -> raw
                        is String -> raw.toBooleanStrictOrNull() ?: (exitCode == null)
                        else -> exitCode == null
                    }
                } else {
                    exitCode == null
                }
            } catch (e: Throwable) { exitCode == null }

            val output = try {
                if (jsonObj.has("output") && !jsonObj.isNull("output")) {
                    jsonObj.optString("output", "")
                } else ""
            } catch (e: Throwable) { "" }

            return WarpBlockState(
                id = id,
                command = command,
                exitCode = exitCode,
                durationMs = durationMs,
                output = output,
                isRunning = isRunning,
                timestamp = timestamp
            )
        }

        fun parseBlocksJson(jsonStr: String): List<WarpBlockState> {
            val trimmed = jsonStr.trim()
            if (trimmed.isBlank() || !trimmed.startsWith("[")) return emptyList()

            // 1. Try org.json parsing
            try {
                val array = JSONArray(trimmed)
                if (array.length() > 0) {
                    val result = mutableListOf<WarpBlockState>()
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i)
                        if (obj != null) {
                            val parsed = fromJson(obj)
                            result.add(parsed)
                        }
                    }
                    if (result.isNotEmpty()) return result
                }
            } catch (e: Throwable) {
                // Fallback to manual string JSON parser
            }

            // 2. Fallback manual string parser for stubbed org.json JVM environments
            return parseBlocksJsonFallback(trimmed)
        }

        private fun parseBlocksJsonFallback(jsonStr: String): List<WarpBlockState> {
            val result = mutableListOf<WarpBlockState>()
            var i = 0
            while (i < jsonStr.length) {
                val start = jsonStr.indexOf('{', i)
                if (start == -1) break
                var depth = 1
                var j = start + 1
                var inString = false
                var esc = false
                while (j < jsonStr.length && depth > 0) {
                    val c = jsonStr[j]
                    if (esc) {
                        esc = false
                    } else if (c == '\\') {
                        esc = true
                    } else if (c == '"') {
                        inString = !inString
                    } else if (!inString) {
                        if (c == '{') depth++
                        else if (c == '}') depth--
                    }
                    j++
                }

                if (depth != 0) break

                val body = jsonStr.substring(start + 1, j - 1)
                val id = extractJsonStringField(body, "id") ?: ""
                val command = extractJsonStringField(body, "command") ?: ""
                val exitCode = extractJsonIntField(body, "exit_code")
                val durationMs = extractJsonLongField(body, "duration_ms")
                val isRunning = extractJsonBoolField(body, "is_running") ?: (exitCode == null)
                val timestamp = extractJsonLongField(body, "timestamp")
                    ?: extractJsonLongField(body, "start_time_unix_ms")
                    ?: 0L
                val output = extractJsonStringField(body, "output") ?: ""

                val computedDurationMs = durationMs ?: run {
                    val endMs = extractJsonLongField(body, "end_time_unix_ms")
                    if (endMs != null && endMs >= timestamp) endMs - timestamp else null
                }

                result.add(
                    WarpBlockState(
                        id = id,
                        command = command,
                        exitCode = exitCode,
                        durationMs = computedDurationMs,
                        output = output,
                        isRunning = isRunning,
                        timestamp = timestamp
                    )
                )
                i = j
            }
            return result
        }

        private fun extractJsonStringField(body: String, field: String): String? {
            val key = "\"$field\""
            val keyIdx = body.indexOf(key)
            if (keyIdx == -1) return null
            val colonIdx = body.indexOf(':', keyIdx + key.length)
            if (colonIdx == -1) return null

            var i = colonIdx + 1
            while (i < body.length && body[i].isWhitespace()) {
                i++
            }
            if (i >= body.length) return null

            if (body[i] == '"') {
                val sb = StringBuilder()
                var esc = false
                var j = i + 1
                while (j < body.length) {
                    val c = body[j]
                    if (esc) {
                        when (c) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (j + 4 < body.length) {
                                    val hex = body.substring(j + 1, j + 5)
                                    val codePoint = hex.toIntOrNull(16)
                                    if (codePoint != null) {
                                        sb.append(codePoint.toChar())
                                        j += 4
                                    } else {
                                        sb.append('u')
                                    }
                                } else {
                                    sb.append('u')
                                }
                            }
                            else -> sb.append(c)
                        }
                        esc = false
                    } else if (c == '\\') {
                        esc = true
                    } else if (c == '"') {
                        return sb.toString()
                    } else {
                        sb.append(c)
                    }
                    j++
                }
                return sb.toString()
            } else {
                val end = body.indexOfAny(charArrayOf(',', '}', '\n', '\r'), i)
                val raw = if (end == -1) body.substring(i).trim() else body.substring(i, end).trim()
                return if (raw == "null") null else raw
            }
        }

        private fun extractJsonIntField(body: String, field: String): Int? {
            val s = extractJsonRawField(body, field) ?: return null
            return if (s == "null") null else s.toIntOrNull()
        }

        private fun extractJsonLongField(body: String, field: String): Long? {
            val s = extractJsonRawField(body, field) ?: return null
            return if (s == "null") null else s.toLongOrNull()
        }

        private fun extractJsonBoolField(body: String, field: String): Boolean? {
            val s = extractJsonRawField(body, field) ?: return null
            return when (s) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

        private fun extractJsonRawField(body: String, field: String): String? {
            val key = "\"$field\""
            val keyIdx = body.indexOf(key)
            if (keyIdx == -1) return null
            val colonIdx = body.indexOf(':', keyIdx + key.length)
            if (colonIdx == -1) return null
            var i = colonIdx + 1
            while (i < body.length && body[i].isWhitespace()) {
                i++
            }
            if (i >= body.length) return null
            val end = body.indexOfAny(charArrayOf(',', '}', '\n', '\r'), i)
            val raw = if (end == -1) body.substring(i).trim() else body.substring(i, end).trim()
            return raw.removeSurrounding("\"")
        }
    }
}

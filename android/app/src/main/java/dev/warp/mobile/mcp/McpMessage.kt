package dev.warp.mobile.mcp

sealed class McpMessage {
    data class Request(
        val id: String,
        val method: String,
        val params: Map<String, Any?>? = null
    ) : McpMessage()

    data class Response(
        val id: String,
        val result: Any? = null
    ) : McpMessage()

    data class Error(
        val id: String?,
        val code: Int,
        val message: String,
        val data: Any? = null
    ) : McpMessage()

    data class Notification(
        val method: String,
        val params: Map<String, Any?>? = null
    ) : McpMessage()

    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        fun serialize(msg: McpMessage): String {
            return when (msg) {
                is Request -> {
                    val paramsStr = msg.params?.let { ", \"params\": ${serializeMap(it)}" } ?: ""
                    "{\"jsonrpc\": \"2.0\", \"id\": \"${msg.id}\", \"method\": \"${msg.method}\"$paramsStr}"
                }
                is Response -> {
                    val resultStr = if (msg.result is String) "\"${msg.result}\"" else "${msg.result}"
                    "{\"jsonrpc\": \"2.0\", \"id\": \"${msg.id}\", \"result\": $resultStr}"
                }
                is Error -> {
                    val idStr = if (msg.id != null) "\"${msg.id}\"" else "null"
                    val dataStr = msg.data?.let { ", \"data\": \"$it\"" } ?: ""
                    "{\"jsonrpc\": \"2.0\", \"id\": $idStr, \"error\": {\"code\": ${msg.code}, \"message\": \"${msg.message}\"$dataStr}}"
                }
                is Notification -> {
                    val paramsStr = msg.params?.let { ", \"params\": ${serializeMap(it)}" } ?: ""
                    "{\"jsonrpc\": \"2.0\", \"method\": \"${msg.method}\"$paramsStr}"
                }
            }
        }
        
        private fun serializeMap(map: Map<String, Any?>): String {
            val entries = map.entries.joinToString(", ") { (k, v) ->
                val vStr = if (v is String) "\"$v\"" else "$v"
                "\"$k\": $vStr"
            }
            return "{$entries}"
        }
    }
}

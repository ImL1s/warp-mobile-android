package dev.warp.mobile.mcp

import org.junit.Assert.*
import org.junit.Test

class McpMessageTest {

    @Test
    fun testSerializeRequest_noParams() {
        val req = McpMessage.Request("1", "testMethod")
        val json = McpMessage.serialize(req)
        assertEquals("{\"jsonrpc\": \"2.0\", \"id\": \"1\", \"method\": \"testMethod\"}", json)
    }

    @Test
    fun testSerializeRequest_withParams() {
        val req = McpMessage.Request("2", "testMethod", mapOf("key" to "value"))
        val json = McpMessage.serialize(req)
        assertEquals("{\"jsonrpc\": \"2.0\", \"id\": \"2\", \"method\": \"testMethod\", \"params\": {\"key\": \"value\"}}", json)
    }

    @Test
    fun testSerializeResponse() {
        val res = McpMessage.Response("3", "success")
        val json = McpMessage.serialize(res)
        assertEquals("{\"jsonrpc\": \"2.0\", \"id\": \"3\", \"result\": \"success\"}", json)
    }

    @Test
    fun testSerializeError() {
        val err = McpMessage.Error("4", McpMessage.METHOD_NOT_FOUND, "Method not found")
        val json = McpMessage.serialize(err)
        assertEquals("{\"jsonrpc\": \"2.0\", \"id\": \"4\", \"error\": {\"code\": -32601, \"message\": \"Method not found\"}}", json)
    }

    @Test
    fun testSerializeNotification() {
        val notif = McpMessage.Notification("update", mapOf("status" to 100))
        val json = McpMessage.serialize(notif)
        assertEquals("{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": {\"status\": 100}}", json)
    }
}

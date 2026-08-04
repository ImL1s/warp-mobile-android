package dev.warp.mobile.mcp

import java.io.InputStream
import java.io.OutputStream
import java.util.Scanner

interface McpTransport {
    fun send(message: McpMessage)
    fun receive(): String? 
}

class StdioTransport(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) : McpTransport {

    override fun send(message: McpMessage) {
        val json = McpMessage.serialize(message)
        val bytes = "$json\n".toByteArray(Charsets.UTF_8)
        outputStream.write(bytes)
        outputStream.flush()
    }

    override fun receive(): String? {
        val scanner = Scanner(inputStream, "UTF-8")
        if (scanner.hasNextLine()) {
            return scanner.nextLine()
        }
        return null
    }
}

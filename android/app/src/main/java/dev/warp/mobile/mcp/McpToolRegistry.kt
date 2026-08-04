package dev.warp.mobile.mcp

data class Tool(
    val name: String,
    val description: String,
    val requiresPermission: Boolean
)

class McpToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    private val handlers = mutableMapOf<String, (Map<String, Any?>?) -> Any?>()

    fun registerTool(tool: Tool, handler: (Map<String, Any?>?) -> Any?) {
        tools[tool.name] = tool
        handlers[tool.name] = handler
    }

    fun executeTool(name: String, params: Map<String, Any?>?, userApproved: Boolean): Any? {
        val tool = tools[name] ?: throw IllegalArgumentException("Tool not found: $name")
        if (tool.requiresPermission && !userApproved) {
            throw SecurityException("Execution of tool $name requires user approval")
        }
        val handler = handlers[name] ?: throw IllegalStateException("Handler missing for tool: $name")
        return handler(params)
    }
    
    fun getTool(name: String): Tool? = tools[name]
    fun getAllTools(): List<Tool> = tools.values.toList()
}

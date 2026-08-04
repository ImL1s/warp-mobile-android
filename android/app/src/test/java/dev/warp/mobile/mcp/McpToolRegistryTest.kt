package dev.warp.mobile.mcp

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpToolRegistryTest {

    private lateinit var registry: McpToolRegistry

    @Before
    fun setUp() {
        registry = McpToolRegistry()
    }

    @Test
    fun testRegisterTool() {
        val tool = Tool("test_tool", "A test tool", false)
        registry.registerTool(tool) { "result" }
        assertEquals(tool, registry.getTool("test_tool"))
        assertEquals(1, registry.getAllTools().size)
    }

    @Test
    fun testExecuteTool_noPermissionRequired_success() {
        val tool = Tool("safe_tool", "A safe tool", false)
        registry.registerTool(tool) { params -> params?.get("val") }
        
        val result = registry.executeTool("safe_tool", mapOf("val" to "hello"), false)
        assertEquals("hello", result)
    }

    @Test
    fun testExecuteTool_permissionRequired_approved_success() {
        val tool = Tool("unsafe_tool", "An unsafe tool", true)
        registry.registerTool(tool) { "done" }
        
        val result = registry.executeTool("unsafe_tool", null, true)
        assertEquals("done", result)
    }

    @Test(expected = SecurityException::class)
    fun testExecuteTool_permissionRequired_notApproved_throwsException() {
        val tool = Tool("unsafe_tool", "An unsafe tool", true)
        registry.registerTool(tool) { "done" }
        
        registry.executeTool("unsafe_tool", null, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testExecuteTool_toolNotFound_throwsException() {
        registry.executeTool("missing_tool", null, true)
    }
}

package dev.warp.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class BlockStateTest {

    @Test
    fun testWarpBlockState_parseValidJson_populatesAllFields() {
        val json = """
            [
                {
                    "id": "session-1-0",
                    "start_time_unix_ms": 1000,
                    "timestamp": 1000,
                    "command": "ls -la",
                    "exit_code": 0,
                    "duration_ms": 150,
                    "is_running": false,
                    "end_time_unix_ms": 1150,
                    "output": "total 42\nfile1.txt\n"
                }
            ]
        """.trimIndent()

        val blocks = WarpBlockState.parseBlocksJson(json)
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertEquals("session-1-0", block.id)
        assertEquals("ls -la", block.command)
        assertEquals(0, block.exitCode)
        assertEquals(150L, block.durationMs)
        assertEquals("total 42\nfile1.txt\n", block.output)
        assertFalse(block.isRunning)
        assertEquals(1000L, block.timestamp)
    }

    @Test
    fun testWarpBlockState_parseRunningBlock_nullExitCodeAndNullDuration() {
        val json = """
            [
                {
                    "id": "session-1-1",
                    "start_time_unix_ms": 2000,
                    "command": "ping 8.8.8.8",
                    "exit_code": null,
                    "end_time_unix_ms": null,
                    "output": "PING 8.8.8.8 (8.8.8.8)"
                }
            ]
        """.trimIndent()

        val blocks = WarpBlockState.parseBlocksJson(json)
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertEquals("session-1-1", block.id)
        assertEquals("ping 8.8.8.8", block.command)
        assertNull(block.exitCode)
        assertNull(block.durationMs)
        assertTrue(block.isRunning)
        assertEquals("PING 8.8.8.8 (8.8.8.8)", block.output)
    }

    @Test
    fun testWarpBlockState_parseFailedBlock_nonZeroExitCode() {
        val json = """
            [
                {
                    "id": "session-1-2",
                    "start_time_unix_ms": 3000,
                    "timestamp": 3000,
                    "command": "cat missing.txt",
                    "exit_code": 1,
                    "duration_ms": 25,
                    "is_running": false,
                    "end_time_unix_ms": 3025,
                    "output": "cat: missing.txt: No such file or directory\n"
                }
            ]
        """.trimIndent()

        val blocks = WarpBlockState.parseBlocksJson(json)
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertEquals("session-1-2", block.id)
        assertEquals("cat missing.txt", block.command)
        assertEquals(1, block.exitCode)
        assertEquals(25L, block.durationMs)
        assertFalse(block.isRunning)
        assertEquals("cat: missing.txt: No such file or directory\n", block.output)
    }

    @Test
    fun testWarpBlockState_parseDurationFallbackFromEndAndStartTime() {
        val json = """
            [
                {
                    "id": "session-1-3",
                    "start_time_unix_ms": 5000,
                    "command": "pwd",
                    "exit_code": 0,
                    "end_time_unix_ms": 5250,
                    "output": "/home/user"
                }
            ]
        """.trimIndent()

        val blocks = WarpBlockState.parseBlocksJson(json)
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertEquals("session-1-3", block.id)
        assertEquals(250L, block.durationMs)
        assertFalse(block.isRunning)
    }

    @Test
    fun testWarpAppState_updateBlocksFromDump_updatesBlockList() {
        val json = """
            [
                {
                    "id": "b-1",
                    "start_time_unix_ms": 100,
                    "command": "echo hi",
                    "exit_code": 0,
                    "duration_ms": 10,
                    "is_running": false,
                    "output": "hi\n"
                },
                {
                    "id": "b-2",
                    "start_time_unix_ms": 200,
                    "command": "top",
                    "exit_code": null,
                    "output": ""
                }
            ]
        """.trimIndent()

        val initialState = WarpAppState()
        assertEquals(0, initialState.blocks.size)

        val updatedState = initialState.updateBlocksFromDump(json)
        assertEquals(2, updatedState.blocks.size)
        assertEquals("b-1", updatedState.blocks[0].id)
        assertEquals("b-2", updatedState.blocks[1].id)
        assertTrue(updatedState.blocks[1].isRunning)
    }

    @Test
    fun testWarpBlockState_parseEmptyAndCorruptJson_returnsEmptyList() {
        assertTrue(WarpBlockState.parseBlocksJson("").isEmpty())
        assertTrue(WarpBlockState.parseBlocksJson("   ").isEmpty())
        assertTrue(WarpBlockState.parseBlocksJson("invalid json content").isEmpty())
        assertTrue(WarpBlockState.parseBlocksJson("{}").isEmpty())
    }

    @Test
    fun testWarpBlockState_parseMalformedAndCorruptJson_neverCrashes() {
        // Truncated / Invalid JSON
        assertTrue(WarpBlockState.parseBlocksJson("[invalid json").isEmpty())
        assertTrue(WarpBlockState.parseBlocksJson("{invalid json").isEmpty())

        // Primitive values instead of objects
        assertTrue(WarpBlockState.parseBlocksJson("[123, true, null, \"string\"]").isEmpty())

        // Non-array JSON
        assertTrue(WarpBlockState.parseBlocksJson("12345").isEmpty())
        assertTrue(WarpBlockState.parseBlocksJson("\"just a string\"").isEmpty())

        // Invalid data types inside block object
        val corruptTypesJson = """
            [
                {
                    "id": 12345,
                    "command": true,
                    "exit_code": "invalid_int",
                    "duration_ms": "not_a_long",
                    "is_running": "not_a_boolean",
                    "timestamp": "invalid_time",
                    "output": 999
                }
            ]
        """.trimIndent()

        val blocks = WarpBlockState.parseBlocksJson(corruptTypesJson)
        assertEquals(1, blocks.size)
        val block = blocks[0]
        assertEquals("12345", block.id)
        assertEquals("true", block.command)
        assertEquals("999", block.output)
    }

    @Test
    fun testWarpBlockState_parseNullFieldsInJson_handledSafely() {
        val jsonWithNulls = """
            [
                {
                    "id": null,
                    "command": null,
                    "exit_code": null,
                    "duration_ms": null,
                    "end_time_unix_ms": null,
                    "is_running": null,
                    "timestamp": null,
                    "output": null
                }
            ]
        """.trimIndent()

        val blocks = WarpBlockState.parseBlocksJson(jsonWithNulls)
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertNull(block.exitCode)
        assertNull(block.durationMs)
        assertTrue(block.isRunning)
        assertEquals(0L, block.timestamp)
    }

    @Test
    fun testWarpBlockState_parseMissingOptionalFields_handledSafely() {
        val minimalJson = """
            [
                {
                    "id": "b-minimal"
                }
            ]
        """.trimIndent()

        val blocks = WarpBlockState.parseBlocksJson(minimalJson)
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertEquals("b-minimal", block.id)
        assertEquals("", block.command)
        assertEquals("", block.output)
        assertEquals(0L, block.timestamp)
    }

    @Test
    fun testWarpBlockState_largeOutput100kBytes_deserializesAndHandlesWithoutOomOrCrash() {
        val linePattern = "Line %05d: \u001B[31mError\u001B[0m occurred at step\n"
        val repeatedOutput = (1..1500).joinToString("") { i -> String.format(linePattern, i) }

        val jsonStr = """
            [
                {
                    "id": "large-block-1",
                    "command": "cat huge_log.txt",
                    "exit_code": 0,
                    "duration_ms": 1200,
                    "is_running": false,
                    "output": "$repeatedOutput"
                }
            ]
        """.trimIndent()
        assertTrue(jsonStr.length > 50000)

        val startTime = System.currentTimeMillis()
        val blocks = WarpBlockState.parseBlocksJson(jsonStr)
        val parseDuration = System.currentTimeMillis() - startTime

        assertEquals(1, blocks.size)
        val block = blocks[0]
        assertEquals("large-block-1", block.id)
        assertEquals("cat huge_log.txt", block.command)
        assertEquals(0, block.exitCode)
        assertEquals(1200L, block.durationMs)
        assertFalse(block.isRunning)
        assertTrue(block.output.length > 50000)

        // Verify ANSI styling parse performance on large output
        val ansiStartTime = System.currentTimeMillis()
        val annotated = dev.warp.mobile.ui.parseAnsiToAnnotatedString(block.output)
        val ansiDuration = System.currentTimeMillis() - ansiStartTime

        assertTrue(annotated.text.isNotEmpty())
        assertTrue("Parsing 50KB+ JSON should be fast", parseDuration < 2000)
        assertTrue("Parsing 50KB+ ANSI should be fast", ansiDuration < 2000)
    }
}

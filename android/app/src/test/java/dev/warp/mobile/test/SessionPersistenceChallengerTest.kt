package dev.warp.mobile.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.warp.mobile.ProcessState
import dev.warp.mobile.SessionManager
import dev.warp.mobile.SessionPersistenceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPersistenceChallengerTest : BaseWarpUnitTest() {

    private lateinit var context: Context

    @Before
    override fun setUp() {
        super.setUp()
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.mkdirs()
        SessionManager.createForTesting(context).resetForTesting()
    }

    @Test
    fun testEmptyAndZeroByteFiles_returnsNullAndFallbacksToDefaultSession() = runTest {
        val sessionsFile = SessionPersistenceManager.getSessionFile(context)

        // 1. Non-existent file
        sessionsFile.delete()
        var loaded = SessionPersistenceManager.loadSessionState(context)
        assertNull("Non-existent file should load as null", loaded)

        // 2. 0-byte file
        sessionsFile.createNewFile()
        assertEquals(0L, sessionsFile.length())
        loaded = SessionPersistenceManager.loadSessionState(context)
        assertNull("0-byte file should load as null", loaded)

        // 3. Re-instantiate SessionManager on 0-byte file -> initializes default tab
        val manager = SessionManager.createForTesting(context)
        val state = manager.appState.value
        assertEquals(1, state.tabs.size)
        assertEquals("Terminal 1", state.activeTab?.title)
    }

    @Test
    fun testCorruptJson_quarantinesToBakAndRecoversCleanSession() = runTest {
        val sessionsFile = SessionPersistenceManager.getSessionFile(context)
        val bakFile = File(context.filesDir, "sessions.json.bak")
        bakFile.delete()

        val corruptPayloads = listOf(
            "{ invalid_json: [ }",
            "{\"version\": 1}", // missing sessions array
            "{\"version\": 1, \"sessions\": \"not_an_array\"}", // type mismatch
            "{\"version\": 1, \"sessions\": []}" // empty sessions array
        )

        for (corruptPayload in corruptPayloads) {
            sessionsFile.writeText(corruptPayload)

            val manager = SessionManager.createForTesting(context)
            assertTrue("Corrupt file should be quarantined to sessions.json.bak", bakFile.exists())
            assertEquals("Bak file should contain corrupt payload", corruptPayload, bakFile.readText())

            val state = manager.appState.value
            assertEquals(1, state.tabs.size)
            assertEquals("Terminal 1", state.activeTab?.title)

            // Reset for next payload test
            manager.resetForTesting()
            bakFile.delete()
        }
    }

    @Test
    fun testMultiSessionStateRestoration_preservesMetadataAndOrder() = runTest {
        val activeManager = SessionManager.createForTesting(context)
        // Clear default initial tab
        activeManager.appState.value.tabs.toList().forEach { activeManager.closeSession(it.id) }

        val validDir1 = File(context.filesDir, "work_dir_1").apply { mkdirs() }
        val validDir2 = File(context.filesDir, "work_dir_2").apply { mkdirs() }

        val env1 = mapOf("ENV_KEY1" to "VAL1", "PATH" to "/bin:/usr/bin")
        val env2 = mapOf("ENV_KEY2" to "VAL2", "UNICODE" to "🚀 中文")

        val id1 = activeManager.createSession(title = "Tab 1 Backend", cwd = validDir1.absolutePath, env = env1)
        val id2 = activeManager.createSession(title = "Tab 2 Frontend", cwd = validDir2.absolutePath, env = env2)
        val id3 = activeManager.createSession(title = "Tab 3 Logs", cwd = validDir1.absolutePath)

        activeManager.switchSession(id2)

        val saved = activeManager.saveSessionState()
        assertTrue("Save session state should succeed", saved)

        // Restore in a fresh manager instance
        val restoredManager = SessionManager.createForTesting(context)
        val restoredState = restoredManager.appState.value

        assertEquals(3, restoredState.tabs.size)
        assertEquals(id2, restoredState.activeSessionId)
        assertEquals("Tab 2 Frontend", restoredState.activeTab?.title)

        val tab1 = restoredState.tabs.find { it.id == id1 }
        assertNotNull(tab1)
        assertEquals("Tab 1 Backend", tab1?.title)
        assertEquals(validDir1.absolutePath, tab1?.cwd)
        assertEquals("VAL1", tab1?.env?.get("ENV_KEY1"))

        val tab2 = restoredState.tabs.find { it.id == id2 }
        assertNotNull(tab2)
        assertEquals("Tab 2 Frontend", tab2?.title)
        assertEquals(validDir2.absolutePath, tab2?.cwd)
        assertEquals("VAL2", tab2?.env?.get("ENV_KEY2"))
        assertEquals("🚀 中文", tab2?.env?.get("UNICODE"))
    }

    @Test
    fun testRestorationWithInvalidCwd_fallsBackToDefaultHome() = runTest {
        val jsonWithInvalidCwd = """
            {
                "version": 1,
                "active_session_id": "sess_invalid_cwd",
                "sessions": [
                    {
                        "id": "sess_invalid_cwd",
                        "title": "Invalid Dir Tab",
                        "cwd": "/non_existent_directory_path_12345",
                        "program": "/system/bin/sh",
                        "env": {}
                    }
                ]
            }
        """.trimIndent()

        val sessionsFile = SessionPersistenceManager.getSessionFile(context)
        sessionsFile.writeText(jsonWithInvalidCwd)

        val manager = SessionManager.createForTesting(context)
        val state = manager.appState.value

        assertEquals(1, state.tabs.size)
        val tab = state.activeTab
        assertNotNull(tab)
        val defaultHome = "${context.applicationInfo.dataDir}/files/home"
        assertEquals("Non-existent cwd should resolve to defaultHome", defaultHome, tab?.cwd)
    }

    @Test
    fun testAtomicSave_handlesTmpFileRenameAndOverwrite() = runTest {
        val jsonPayload = """
            {
                "version": 1,
                "active_session_id": "s1",
                "sessions": [
                    { "id": "s1", "title": "Tab 1", "cwd": "~", "program": "/system/bin/sh", "env": {} }
                ]
            }
        """.trimIndent()

        val saved = SessionPersistenceManager.saveSessionState(context, jsonPayload)
        assertTrue(saved)

        val sessionsFile = SessionPersistenceManager.getSessionFile(context)
        val tmpFile = File(context.filesDir, "sessions.json.tmp")

        assertTrue(sessionsFile.exists())
        assertFalse("Temp file should be cleaned up after atomic save", tmpFile.exists())
        assertEquals(jsonPayload, sessionsFile.readText())
    }

    @Test
    fun testRepeatedSaveRestoreCycles_preservesActiveSwitchAndPtyEnv() = runTest {
        val validDir = File(context.filesDir, "valid_work_dir").apply { mkdirs() }
        val activeManager = SessionManager.createForTesting(context)
        activeManager.appState.value.tabs.toList().forEach { activeManager.closeSession(it.id) }

        val createdIds = mutableListOf<String>()
        val expectedEnvList = mutableListOf<Map<String, String>>()
        val expectedPrograms = listOf("/system/bin/zsh", "/system/bin/sh", "/system/bin/bash", "/system/bin/zsh", "/system/bin/sh")

        for (i in 0 until 5) {
            val env = mapOf(
                "INDEX" to i.toString(),
                "PATH_EXTRA" to "/opt/bin/dir_$i",
                "UNICODE_ENV" to "🚀 繁體中文 Tab $i",
                "COMPLEX_VAL" to "FOO=BAR; export FOO"
            )
            val prog = expectedPrograms[i]
            val id = activeManager.createSession(
                title = "Tab $i",
                cwd = validDir.absolutePath,
                program = prog,
                env = env
            )
            createdIds.add(id)
            expectedEnvList.add(env)
        }

        activeManager.switchSession(createdIds[2])
        assertTrue(activeManager.saveSessionState())

        var currentManager = activeManager
        for (cycle in 1..50) {
            val targetActiveId = createdIds[cycle % 5]
            currentManager.switchSession(targetActiveId)

            val restoredManager = SessionManager.createForTesting(context)
            val state = restoredManager.appState.value

            assertEquals("Cycle $cycle: tab count mismatch", 5, state.tabs.size)
            assertEquals("Cycle $cycle: activeSessionId mismatch", targetActiveId, state.activeSessionId)
            assertEquals("Cycle $cycle: activeTab title mismatch", "Tab ${cycle % 5}", state.activeTab?.title)

            for (i in 0 until 5) {
                val tab = state.tabs.find { it.id == createdIds[i] }
                assertNotNull("Cycle $cycle: Tab $i missing", tab)
                assertEquals("Cycle $cycle: Tab $i title mismatch", "Tab $i", tab?.title)
                assertEquals("Cycle $cycle: Tab $i program mismatch", expectedPrograms[i], tab?.program)
                assertEquals("Cycle $cycle: Tab $i cwd mismatch", validDir.absolutePath, tab?.cwd)
                assertEquals("Cycle $cycle: Tab $i env INDEX mismatch", i.toString(), tab?.env?.get("INDEX"))
                assertEquals("Cycle $cycle: Tab $i env PATH_EXTRA mismatch", "/opt/bin/dir_$i", tab?.env?.get("PATH_EXTRA"))
                assertEquals("Cycle $cycle: Tab $i env UNICODE_ENV mismatch", "🚀 繁體中文 Tab $i", tab?.env?.get("UNICODE_ENV"))
                assertEquals("Cycle $cycle: Tab $i env COMPLEX_VAL mismatch", "FOO=BAR; export FOO", tab?.env?.get("COMPLEX_VAL"))
            }

            currentManager = restoredManager
        }
    }
}


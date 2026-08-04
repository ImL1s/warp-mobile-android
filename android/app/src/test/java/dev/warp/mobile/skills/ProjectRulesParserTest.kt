package dev.warp.mobile.skills

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule as JunitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectRulesParserTest {

    @get:JunitRule
    val tempFolder = TemporaryFolder()

    private lateinit var parser: ProjectRulesParser
    private lateinit var workspaceRoot: File

    @Before
    fun setUp() {
        parser = ProjectRulesParser()
        workspaceRoot = tempFolder.newFolder("workspace")
    }

    @Test
    fun testParse_noFile_returnsEmptyList() {
        val rules = parser.parse(workspaceRoot)
        assertTrue(rules.isEmpty())
    }

    @Test
    fun testParse_emptyFile_returnsEmptyList() {
        val rulesFile = File(workspaceRoot, ".warprules")
        rulesFile.createNewFile()
        val rules = parser.parse(workspaceRoot)
        assertTrue(rules.isEmpty())
    }

    @Test
    fun testParse_singleRule_parsesCorrectly() {
        val rulesFile = File(workspaceRoot, ".warprules")
        rulesFile.writeText("""
            Rule: Kotlin Style
            Description: Use standard Kotlin conventions
            - always use val
            - indentation is 4 spaces
        """.trimIndent())

        val rules = parser.parse(workspaceRoot)
        assertEquals(1, rules.size)
        assertEquals("Kotlin Style", rules[0].name)
        assertEquals("Use standard Kotlin conventions", rules[0].description)
        assertEquals(listOf("always use val", "indentation is 4 spaces"), rules[0].triggerPatterns)
    }

    @Test
    fun testParse_multipleRules_parsesCorrectly() {
        val rulesFile = File(workspaceRoot, ".warprules")
        rulesFile.writeText("""
            Rule: Rule 1
            Description: Desc 1
            - pattern 1
            Rule: Rule 2
            Description: Desc 2
            - pattern 2
        """.trimIndent())

        val rules = parser.parse(workspaceRoot)
        assertEquals(2, rules.size)
        assertEquals("Rule 1", rules[0].name)
        assertEquals("Rule 2", rules[1].name)
    }

    @Test
    fun testParse_malformedFile_ignoresErrors() {
        val rulesFile = File(workspaceRoot, ".warprules")
        rulesFile.writeText("""
            Some random text
            Rule: Valid Rule
            - pat
        """.trimIndent())

        val rules = parser.parse(workspaceRoot)
        assertEquals(1, rules.size)
        assertEquals("Valid Rule", rules[0].name)
        assertEquals(listOf("pat"), rules[0].triggerPatterns)
    }
}

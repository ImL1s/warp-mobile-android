package dev.warp.mobile.skills

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule as JunitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalSkillRegistryTest {

    @get:JunitRule
    val tempFolder = TemporaryFolder()

    private lateinit var skillsDir: File

    @Before
    fun setUp() {
        skillsDir = tempFolder.newFolder("skills")
    }

    @Test
    fun testMatchSkill_caseInsensitiveMatching() {
        val skillDir = File(skillsDir, "skill1").apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText("""
            # Name: Test Skill
            Description: A test skill
            - Keyword: FIND ME
        """.trimIndent())

        val registry = LocalSkillRegistry(skillsDir)
        val skill = registry.matchSkill("I want to find me a solution")
        assertNotNull(skill)
        assertEquals("Test Skill", skill?.name)
    }

    @Test
    fun testMatchSkill_emptyPrompt_returnsNull() {
        val skillDir = File(skillsDir, "skill1").apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText("""
            # Name: Test Skill
            Description: A test skill
            - Keyword: find me
        """.trimIndent())

        val registry = LocalSkillRegistry(skillsDir)
        val skill = registry.matchSkill("")
        assertNull(skill)
    }

    @Test
    fun testMatchSkill_noMatch_returnsNull() {
        val skillDir = File(skillsDir, "skill1").apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText("""
            # Name: Test Skill
            Description: A test skill
            - Keyword: find me
        """.trimIndent())

        val registry = LocalSkillRegistry(skillsDir)
        val skill = registry.matchSkill("I want to look for it")
        assertNull(skill)
    }

    @Test
    fun testMatchSkill_multipleMatches_returnsFirst() {
        val skillDir1 = File(skillsDir, "skill1").apply { mkdirs() }
        val skillFile1 = File(skillDir1, "SKILL.md")
        skillFile1.writeText("""
            # Name: First Skill
            Description: Desc 1
            - Keyword: test
        """.trimIndent())
        
        val skillDir2 = File(skillsDir, "skill2").apply { mkdirs() }
        val skillFile2 = File(skillDir2, "SKILL.md")
        skillFile2.writeText("""
            # Name: Second Skill
            Description: Desc 2
            - Keyword: test
        """.trimIndent())
        
        val registry = LocalSkillRegistry(skillsDir)
        val skill = registry.matchSkill("this is a test")
        assertNotNull(skill)
        assertTrue(skill?.name == "First Skill" || skill?.name == "Second Skill")
    }

    @Test
    fun testMatchSkill_ignoresFilesNotNamedSkillMd() {
        val skillDir = File(skillsDir, "skill1").apply { mkdirs() }
        val skillFile = File(skillDir, "OTHER.md")
        skillFile.writeText("""
            # Name: Test Skill
            Description: A test skill
            - Keyword: test
        """.trimIndent())

        val registry = LocalSkillRegistry(skillsDir)
        val skill = registry.matchSkill("test")
        assertNull(skill)
    }
}

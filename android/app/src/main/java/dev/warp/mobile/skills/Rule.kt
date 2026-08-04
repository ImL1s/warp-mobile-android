package dev.warp.mobile.skills

data class Rule(
    val name: String,
    val description: String,
    val triggerPatterns: List<String>
)

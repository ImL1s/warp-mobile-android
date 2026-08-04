package dev.warp.mobile.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.warp.mobile.WarpBlockState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

data class BlockSearchMatch(
    val blockId: String,
    val blockIndex: Int,
    val isCommandMatch: Boolean,
    val matchRange: IntRange
)

data class SearchResult(
    val matches: List<BlockSearchMatch>,
    val matchedBlockIds: Set<String>
)

object BlockSearchEngine {

    fun stripAnsiCodes(text: String): String {
        if (!text.contains("\u001B") && !text.contains("\u001b")) return text
        return dev.warp.mobile.ui.parseAnsiToAnnotatedString(text).text
    }

    suspend fun search(
        blocks: List<WarpBlockState>,
        query: String,
        isRegex: Boolean = false,
        ignoreCase: Boolean = true,
        maxMatches: Int = 10_000
    ): SearchResult = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext SearchResult(emptyList(), emptySet())

        val matches = mutableListOf<BlockSearchMatch>()
        val matchedIds = mutableSetOf<String>()

        val targetQuery = if (ignoreCase && !isRegex) query.lowercase() else query

        val pattern = if (isRegex) {
            val patternFlags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
            try {
                Pattern.compile(query, patternFlags)
            } catch (e: Exception) {
                return@withContext SearchResult(emptyList(), emptySet())
            }
        } else null

        for ((blockIndex, block) in blocks.withIndex()) {
            if (matches.size >= maxMatches) break
            var foundInBlock = false
            val cleanCmd = stripAnsiCodes(block.command)
            val cleanOut = stripAnsiCodes(block.output)

            if (isRegex && pattern != null) {
                // Command search
                val cmdMatcher = pattern.matcher(cleanCmd)
                while (cmdMatcher.find() && matches.size < maxMatches) {
                    val start = cmdMatcher.start()
                    val end = cmdMatcher.end()
                    if (start < end) {
                        matches.add(BlockSearchMatch(block.id, blockIndex, true, start until end))
                        foundInBlock = true
                    }
                }
                // Output search
                val outMatcher = pattern.matcher(cleanOut)
                while (outMatcher.find() && matches.size < maxMatches) {
                    val start = outMatcher.start()
                    val end = outMatcher.end()
                    if (start < end) {
                        matches.add(BlockSearchMatch(block.id, blockIndex, false, start until end))
                        foundInBlock = true
                    }
                }
            } else {
                // Command search
                val targetCmd = if (ignoreCase) cleanCmd.lowercase() else cleanCmd
                val targetOut = if (ignoreCase) cleanOut.lowercase() else cleanOut

                var cmdIdx = targetCmd.indexOf(targetQuery)
                while (cmdIdx != -1 && matches.size < maxMatches) {
                    matches.add(BlockSearchMatch(block.id, blockIndex, true, cmdIdx until (cmdIdx + query.length)))
                    foundInBlock = true
                    cmdIdx = targetCmd.indexOf(targetQuery, cmdIdx + query.length.coerceAtLeast(1))
                }
                // Output search
                var outIdx = targetOut.indexOf(targetQuery)
                while (outIdx != -1 && matches.size < maxMatches) {
                    matches.add(BlockSearchMatch(block.id, blockIndex, false, outIdx until (outIdx + query.length)))
                    foundInBlock = true
                    outIdx = targetOut.indexOf(targetQuery, outIdx + query.length.coerceAtLeast(1))
                }
            }

            if (foundInBlock) {
                matchedIds.add(block.id)
            }
        }

        SearchResult(matches, matchedIds)
    }

    fun highlightSearchMatches(
        annotated: AnnotatedString,
        query: String,
        isRegex: Boolean = false,
        activeMatchRange: IntRange? = null,
        highlightStyle: SpanStyle = SpanStyle(background = Color(0xFFFFD54F), color = Color.Black),
        activeHighlightStyle: SpanStyle = SpanStyle(background = Color(0xFFFF8F00), color = Color.White)
    ): AnnotatedString {
        if (query.isBlank()) return annotated
        return buildAnnotatedString {
            append(annotated)
            val text = annotated.text

            if (isRegex) {
                try {
                    val pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE)
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        val start = matcher.start()
                        val end = matcher.end()
                        if (start < end) {
                            val range = start until end
                            val style = if (activeMatchRange != null && range == activeMatchRange) activeHighlightStyle else highlightStyle
                            addStyle(style, start, end)
                        }
                    }
                } catch (e: Exception) {
                    // Fallback on regex compile failure
                }
            } else {
                var idx = text.indexOf(query, ignoreCase = true)
                while (idx != -1) {
                    val end = idx + query.length
                    val range = idx until end
                    val style = if (activeMatchRange != null && range == activeMatchRange) activeHighlightStyle else highlightStyle
                    addStyle(style, idx, end)
                    idx = text.indexOf(query, idx + query.length.coerceAtLeast(1), ignoreCase = true)
                }
            }
        }
    }
}

package dev.warp.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

fun parseAnsiToAnnotatedString(text: String): AnnotatedString {
    if (!text.contains("\u001B") && !text.contains("\u001b")) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var currentFg: Color? = null
        var currentBg: Color? = null
        var isBold = false
        var isDim = false
        var isItalic = false
        var isUnderline = false
        var isReverse = false
        var isStrikethrough = false

        fun applyCurrentStyle(content: String) {
            if (content.isEmpty()) return

            val effectiveFg = if (isDim) (currentFg ?: Color.White).copy(alpha = 0.6f) else currentFg
            val fg = if (isReverse) (currentBg ?: Color.Black) else (effectiveFg ?: Color.Unspecified)
            val bg = if (isReverse) (effectiveFg ?: Color.White) else (currentBg ?: Color.Unspecified)

            val decorations = mutableListOf<TextDecoration>()
            if (isUnderline) decorations.add(TextDecoration.Underline)
            if (isStrikethrough) decorations.add(TextDecoration.LineThrough)
            val textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else TextDecoration.None

            val style = SpanStyle(
                color = fg,
                background = bg,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = textDecoration
            )
            pushStyle(style)
            append(content)
            pop()
        }

        var currentIndex = 0
        val length = text.length

        while (currentIndex < length) {
            val escIndex = text.indexOf('\u001B', currentIndex)
            if (escIndex == -1) {
                applyCurrentStyle(text.substring(currentIndex))
                break
            }

            if (escIndex > currentIndex) {
                applyCurrentStyle(text.substring(currentIndex, escIndex))
            }

            var escCount = 0
            while (escIndex + escCount < length && text[escIndex + escCount] == '\u001B') {
                escCount++
            }
            val afterEscIndex = escIndex + escCount
            var bracketCount = 0
            while (afterEscIndex + bracketCount < length && text[afterEscIndex + bracketCount] == '[') {
                bracketCount++
            }

            if (bracketCount > 0) {
                // CSI Sequence (supports single or multiple consecutive \u001B paired with [)
                val pairs = minOf(escCount, bracketCount)
                if (escCount > pairs) {
                    val extraEscapes = escCount - pairs
                    applyCurrentStyle(text.substring(escIndex, escIndex + extraEscapes))
                }
                val actualBracketIndex = afterEscIndex + pairs - 1

                var mIndex = actualBracketIndex + 1
                while (mIndex < length && (text[mIndex] in '0'..'9' || text[mIndex] == ';' || text[mIndex] == ':')) {
                    mIndex++
                }
                if (mIndex < length) {
                    val finalChar = text[mIndex]
                    val codesStr = text.substring(actualBracketIndex + 1, mIndex)
                    val isValidFinal = finalChar == 'm' || finalChar in 'A'..'Z' || finalChar in 'a'..'z' || finalChar == '@' || finalChar == '~'

                    if (isValidFinal && !codesStr.endsWith(";") && !codesStr.endsWith(":")) {
                        if (finalChar == 'm') {
                            if (codesStr.isEmpty() || codesStr == "0") {
                                currentFg = null
                                currentBg = null
                                isBold = false
                                isDim = false
                                isItalic = false
                                isUnderline = false
                                isReverse = false
                                isStrikethrough = false
                            } else {
                                val rawTokens = codesStr.split(";", ":")
                                val codes = rawTokens.map { it.toIntOrNull() }
                                var i = 0
                                while (i < codes.size) {
                                    val code = codes[i]
                                    if (code == null) { i++; continue }
                                    when (code) {
                                        0 -> {
                                            currentFg = null
                                            currentBg = null
                                            isBold = false
                                            isDim = false
                                            isItalic = false
                                            isUnderline = false
                                            isReverse = false
                                            isStrikethrough = false
                                        }
                                        1 -> isBold = true
                                        2 -> isDim = true
                                        3 -> isItalic = true
                                        4 -> isUnderline = true
                                        7 -> isReverse = true
                                        9 -> isStrikethrough = true
                                        22 -> { isBold = false; isDim = false }
                                        23 -> isItalic = false
                                        24 -> isUnderline = false
                                        27 -> isReverse = false
                                        29 -> isStrikethrough = false
                                        in 30..37 -> currentFg = getAnsiColor(code - 30)
                                        39 -> currentFg = null
                                        in 40..47 -> currentBg = getAnsiColor(code - 40)
                                        49 -> currentBg = null
                                        in 90..97 -> currentFg = getAnsiBrightColor(code - 90)
                                        in 100..107 -> currentBg = getAnsiBrightColor(code - 100)
                                        38, 48 -> {
                                            val isFg = code == 38
                                            if (i + 1 < codes.size) {
                                                when (codes[i + 1]) {
                                                    5 -> {
                                                        if (i + 2 < codes.size && codes[i + 2] != null) {
                                                            val idx = codes[i + 2]!!
                                                            if (idx in 0..255) {
                                                                val color = getAnsi256Color(idx)
                                                                if (isFg) currentFg = color else currentBg = color
                                                            }
                                                            i += 2
                                                        } else {
                                                            i += 1
                                                        }
                                                    }
                                                    2 -> {
                                                        if (i + 2 < codes.size && codes[i + 2] == null) {
                                                            // 38:2::R:G:B
                                                            val r = codes.getOrNull(i + 3) ?: 0
                                                            val g = codes.getOrNull(i + 4) ?: 0
                                                            val b = codes.getOrNull(i + 5) ?: 0
                                                            val color = Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                                                            if (isFg) currentFg = color else currentBg = color
                                                            i += 5
                                                        } else if (i + 5 < codes.size && codes[i + 2] != null && (codes[i + 2] == 0 || codes[i + 2] == 1)) {
                                                            // 38:2:CS:R:G:B
                                                            val r = codes.getOrNull(i + 3) ?: 0
                                                            val g = codes.getOrNull(i + 4) ?: 0
                                                            val b = codes.getOrNull(i + 5) ?: 0
                                                            val color = Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                                                            if (isFg) currentFg = color else currentBg = color
                                                            i += 5
                                                        } else if (i + 4 < codes.size && codes[i + 2] != null) {
                                                            // 38;2;R;G;B
                                                            val r = codes.getOrNull(i + 2) ?: 0
                                                            val g = codes.getOrNull(i + 3) ?: 0
                                                            val b = codes.getOrNull(i + 4) ?: 0
                                                            val color = Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                                                            if (isFg) currentFg = color else currentBg = color
                                                            i += 4
                                                        } else {
                                                            i += 1
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    i++
                                }
                            }
                        }
                        currentIndex = mIndex + 1
                        continue
                    } else {
                        // Malformed CSI sequence with trailing delimiter or invalid final char
                        val isColorPrefix = codesStr.startsWith("38;") || codesStr.startsWith("48;") || codesStr.startsWith("38:") || codesStr.startsWith("48:")
                        currentIndex = if (!isColorPrefix && (finalChar in 'a'..'z' || finalChar in 'A'..'Z' || finalChar == 'm')) mIndex + 1 else mIndex
                        continue
                    }
                } else {
                    // Unterminated CSI sequence at EOF: preserve remaining sequence as raw text
                    applyCurrentStyle(text.substring(escIndex))
                    currentIndex = length
                    continue
                }
            } else if (escIndex + 1 < length) {
                val nextChar = text[escIndex + 1]
                if (nextChar == '\u001B') {
                    // Consecutive escape characters (e.g. \u001B\u001B)
                    currentIndex = escIndex + 1
                    continue
                } else if (nextChar == ']') {
                    // OSC Sequence
                    var oscIndex = escIndex + 2
                    var oscEnd = -1
                    var is7BitSt = false

                    while (oscIndex < length) {
                        val c = text[oscIndex]
                        if (c == '\u0007' || c == '\u009C') {
                            oscEnd = oscIndex
                            break
                        } else if (c == '\u001B' && oscIndex + 1 < length && text[oscIndex + 1] == '\\') {
                            oscEnd = oscIndex
                            is7BitSt = true
                            break
                        }
                        oscIndex++
                    }

                    if (oscEnd != -1) {
                        val oscBody = text.substring(escIndex + 2, oscEnd)
                        if (oscBody.startsWith("8;")) {
                            val payload = oscBody.substring(2)
                            val semiIdx = payload.indexOf(';')
                            if (semiIdx != -1) {
                                val url = payload.substring(semiIdx + 1)
                                if (url.isNotEmpty()) {
                                    pushStringAnnotation(tag = "URL", annotation = url)
                                } else {
                                    pop()
                                }
                            }
                        }
                        currentIndex = if (is7BitSt) oscEnd + 2 else oscEnd + 1
                        continue
                    } else {
                        // Unclosed OSC sequence at EOF: preserve remaining sequence as raw text
                        applyCurrentStyle(text.substring(escIndex))
                        currentIndex = length
                        continue
                    }
                }
            }

            applyCurrentStyle("\u001B")
            currentIndex = escIndex + 1
        }
    }
}

private fun getAnsiColor(index: Int): Color = when (index) {
    0 -> Color(0xFF000000)
    1 -> Color(0xFFCD3131)
    2 -> Color(0xFF0DBC79)
    3 -> Color(0xFFE5E510)
    4 -> Color(0xFF2472C8)
    5 -> Color(0xFFBC3FBC)
    6 -> Color(0xFF11A8CD)
    7 -> Color(0xFFE5E5E5)
    else -> Color.Unspecified
}

private fun getAnsiBrightColor(index: Int): Color = when (index) {
    0 -> Color(0xFF666666)
    1 -> Color(0xFFF14C4C)
    2 -> Color(0xFF23D18B)
    3 -> Color(0xFFF5F543)
    4 -> Color(0xFF3B8EEA)
    5 -> Color(0xFFD670D6)
    6 -> Color(0xFF29B8DB)
    7 -> Color(0xFFFFFFFF)
    else -> Color.Unspecified
}

private fun getAnsi256Color(index: Int): Color {
    return when {
        index < 8 -> getAnsiColor(index)
        index < 16 -> getAnsiBrightColor(index - 8)
        index in 16..231 -> {
            val i = index - 16
            val r = (i / 36) * 51
            val g = ((i % 36) / 6) * 51
            val b = (i % 6) * 51
            Color(r, g, b)
        }
        index in 232..255 -> {
            val gray = (index - 232) * 10 + 8
            Color(gray, gray, gray)
        }
        else -> Color.Unspecified
    }
}

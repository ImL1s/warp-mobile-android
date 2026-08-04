package dev.warp.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class AnsiParserTest {

    @Test
    fun testParseAnsi_plainTextWithoutEscape_returnsOriginalText() {
        val result = parseAnsiToAnnotatedString("Hello World")
        assertEquals("Hello World", result.text)
    }

    @Test
    fun testParseAnsi_singleColorSequence_preservesAllTextAndAppliesColor() {
        val input = "Hello \u001B[31mRed World\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Hello Red World", result.text)
    }

    @Test
    fun testParseAnsi_consecutiveEscapeSequences_doesNotCrash() {
        val input = "Bold Red: \u001B[1m\u001B[31mTEST\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Bold Red: TEST", result.text)
    }

    @Test
    fun testParseAnsi_complex256AndTruecolor_doesNotTruncate() {
        val input = "\u001B[38;5;196m256Color\u001B[0m \u001B[38;2;255;0;0mTrueColor\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("256Color TrueColor", result.text)
    }

    @Test
    fun testParseAnsi_unparsedCsiAndOsc_doesNotCorruptText() {
        val input = "Header\u001B[2J\u001B[32mGreen\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("HeaderGreen", result.text)
    }

    @Test
    fun testParseAnsi_consecutiveEscapeSequences_appliesCombinedStyles() {
        val input = "\u001B[1m\u001B[31mTEST"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("TEST", result.text)
        val coloredSpan = result.spanStyles.first { it.item.color == Color(0xFFCD3131) }
        assertEquals(0, coloredSpan.start)
        assertEquals(4, coloredSpan.end)
        assertEquals(FontWeight.Bold, coloredSpan.item.fontWeight)
    }

    @Test
    fun testParseAnsi_compoundEscapeSequence_appliesCombinedStyles() {
        val input = "\u001B[1;31;42mTEST"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("TEST", result.text)
        val span = result.spanStyles.first { it.item.color == Color(0xFFCD3131) }
        assertEquals(0, span.start)
        assertEquals(4, span.end)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(Color(0xFFCD3131), span.item.color)
        assertEquals(Color(0xFF0DBC79), span.item.background)
    }

    @Test
    fun testParseAnsi_emptyAndIncompleteSequences_handlesGracefully() {
        assertEquals("", parseAnsiToAnnotatedString("").text)
        assertEquals("\u001B[", parseAnsiToAnnotatedString("\u001B[").text)
        assertEquals("\u001B[31", parseAnsiToAnnotatedString("\u001B[31").text)
    }

    // ── Task 3 (#26) VT/ANSI/OSC/Unicode Compatibility Unit Tests ─────────────

    @Test
    fun test_kotlin_ansi_256_color_fg_bg_span_styles() {
        val input = "\u001B[38;5;196mRed\u001B[48;5;21mBlue"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("RedBlue", result.text)
        val redSpan = result.spanStyles.first { it.start == 0 && it.end == 3 }
        assertEquals(Color(255, 0, 0), redSpan.item.color)

        val blueSpan = result.spanStyles.first { it.start == 3 && it.end == 7 }
        assertEquals(Color(0, 0, 255), blueSpan.item.background)
    }

    @Test
    fun test_kotlin_ansi_truecolor_rgb_span_styles() {
        val input = "\u001B[38;2;255;128;64mTrueColor\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("TrueColor", result.text)
        val span = result.spanStyles.first { it.item.color == Color(255, 128, 64) }
        assertEquals(Color(255, 128, 64), span.item.color)
    }

    @Test
    fun test_kotlin_ansi_colon_separated_truecolor() {
        val input = "\u001B[38:2::100:200:50mColonTrueColor\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("ColonTrueColor", result.text)
        val span = result.spanStyles.first { it.item.color == Color(100, 200, 50) }
        assertEquals(Color(100, 200, 50), span.item.color)
    }

    @Test
    fun test_kotlin_ansi_invalid_color_codes_graceful_fallback() {
        val input = "\u001B[38;5;999mInvalid256\u001B[38;2;255;128mIncompleteTrueColor"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Invalid256IncompleteTrueColor", result.text)
    }

    @Test
    fun test_kotlin_ansi_dim_reverse_strikethrough_styles() {
        val input = "\u001B[2mDim\u001B[22m \u001B[7mReverse\u001B[27m \u001B[9mStrike\u001B[29m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Dim Reverse Strike", result.text)
        val strikeSpan = result.spanStyles.first { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals(TextDecoration.LineThrough, strikeSpan.item.textDecoration)
    }

    @Test
    fun test_kotlin_ansi_cjk_wide_chars_with_colors() {
        val input = "\u001B[31m繁體中文\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("繁體中文", result.text)
        val span = result.spanStyles.first { it.item.color == Color(0xFFCD3131) }
        assertEquals(0, span.start)
        assertEquals(4, span.end)
        assertEquals(Color(0xFFCD3131), span.item.color)
    }

    @Test
    fun test_kotlin_ansi_cjk_mixed_with_ascii_spans() {
        val input = "Pre \u001B[32m測試\u001B[0m Post"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Pre 測試 Post", result.text)
        val span = result.spanStyles.first { it.item.color == Color(0xFF0DBC79) }
        assertEquals(4, span.start)
        assertEquals(6, span.end)
        assertEquals(Color(0xFF0DBC79), span.item.color)
    }

    @Test
    fun test_kotlin_ansi_cjk_fullwidth_punctuation() {
        val input = "\u001B[34m：，；\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("：，；", result.text)
        assertEquals(3, result.text.length)
        val span = result.spanStyles.first { it.item.color == Color(0xFF2472C8) }
        assertEquals(Color(0xFF2472C8), span.item.color)
    }

    @Test
    fun test_kotlin_ansi_combining_marks_with_sgr() {
        val input = "\u001B[33me\u0301\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("e\u0301", result.text)
        assertEquals(2, result.text.length)
        val span = result.spanStyles.first { it.item.color == Color(0xFFE5E510) }
        assertEquals(Color(0xFFE5E510), span.item.color)
    }

    @Test
    fun test_kotlin_ansi_precomposed_vs_decomposed_unicode() {
        val nfd = "e\u0301"
        val nfc = "é"
        val resultNfd = parseAnsiToAnnotatedString("\u001B[31m$nfd\u001B[0m")
        val resultNfc = parseAnsiToAnnotatedString("\u001B[31m$nfc\u001B[0m")
        assertEquals(nfd, resultNfd.text)
        assertEquals(nfc, resultNfc.text)
    }

    @Test
    fun test_kotlin_ansi_powerline_prompt_theme_parsing() {
        val input = "\u001B[44m\u001B[30m ~/dir \u001B[40m\u001B[34m\uE0B0\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals(" ~/dir \uE0B0", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun test_kotlin_ansi_powerline_adjacent_color_swaps() {
        val input = "\u001B[48;5;235m\u001B[38;5;31m\uE0B0\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("\uE0B0", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun test_kotlin_ansi_emoji_surrogate_pairs_with_styles() {
        val input = "\u001B[31m🔥🚀\u001B[0m"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("🔥🚀", result.text)
        assertEquals(4, result.text.length)
        val span = result.spanStyles.first { it.item.color == Color(0xFFCD3131) }
        assertEquals(Color(0xFFCD3131), span.item.color)
    }

    @Test
    fun test_kotlin_ansi_emoji_zwj_sequence_span_bounds() {
        val input = "Status: \u001B[32m👨‍💻\u001B[0m Done"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Status: 👨‍💻 Done", result.text)
        val span = result.spanStyles.first { it.item.color == Color(0xFF0DBC79) }
        assertEquals(8, span.start)
        assertEquals(13, span.end)
        assertEquals(Color(0xFF0DBC79), span.item.color)
    }

    @Test
    fun test_kotlin_osc_8_hyperlink_parsing() {
        val input = "\u001B]8;;https://warp.dev\u001B\\Warp\u001B]8;;\u001B\\"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Warp", result.text)
        val annotations = result.getStringAnnotations(tag = "URL", start = 0, end = 4)
        assertEquals(1, annotations.size)
        assertEquals("https://warp.dev", annotations[0].item)
    }

    @Test
    fun test_kotlin_osc_non_hyperlink_stripping() {
        val input = "Hello\u001B]0;title\u0007 World"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Hello World", result.text)
    }

    @Test
    fun test_kotlin_csi_cursor_movement_stripping() {
        val input = "Line1\u001B[2JLine2\u001B[HLine3"
        val result = parseAnsiToAnnotatedString(input)
        assertEquals("Line1Line2Line3", result.text)
    }

    // ── Additional Empirical Stress Tests (Challenger 2) ─────────────────────

    @Test
    fun test_malformed_ansi_unterminated_and_corrupt_sequences() {
        // Unterminated CSI
        val res1 = parseAnsiToAnnotatedString("Text\u001B[31")
        assertEquals("Text\u001B[31", res1.text)

        // Unterminated OSC
        val res2 = parseAnsiToAnnotatedString("Text\u001B]8;;https://example.com")
        assertEquals("Text\u001B]8;;https://example.com", res2.text)

        // Double ESC
        val res3 = parseAnsiToAnnotatedString("\u001B\u001B[31mRed\u001B[0m")
        assertEquals("\u001BRed", res3.text)

        // Corrupt token inside CSI: sequence gracefully skipped without crashing
        val res4 = parseAnsiToAnnotatedString("\u001B[31;bad;42mText\u001B[0m")
        assertEquals("ad;42mText", res4.text)
    }

    @Test
    fun test_colon_truecolor_variants_and_clamping() {
        // 38:2:0:R:G:B
        val res1 = parseAnsiToAnnotatedString("\u001B[38:2:0:100:150:200mColor1\u001B[0m")
        assertEquals("Color1", res1.text)
        assertEquals(Color(100, 150, 200), res1.spanStyles.first { it.item.color == Color(100, 150, 200) }.item.color)

        // 48:2::50:100:150 (bg truecolor colon)
        val res2 = parseAnsiToAnnotatedString("\u001B[48:2::50:100:150mBgColor\u001B[0m")
        assertEquals("BgColor", res2.text)
        assertEquals(Color(50, 100, 150), res2.spanStyles.first { it.item.background == Color(50, 100, 150) }.item.background)

        // Out-of-bounds RGB values (clamping check)
        val res3 = parseAnsiToAnnotatedString("\u001B[38;2;300;0;999mClamped\u001B[0m")
        assertEquals("Clamped", res3.text)
        assertEquals(Color(255, 0, 255), res3.spanStyles.first { it.item.color == Color(255, 0, 255) }.item.color)
    }

    @Test
    fun test_out_of_bounds_256_color_and_truncated_parameters() {
        // 256 color index > 255
        val res1 = parseAnsiToAnnotatedString("\u001B[38;5;300mOOB\u001B[0m")
        assertEquals("OOB", res1.text)

        // Truncated 256 color code
        val res2 = parseAnsiToAnnotatedString("\u001B[38;5mTruncated\u001B[0m")
        assertEquals("Truncated", res2.text)

        // Truncated Truecolor code
        val res3 = parseAnsiToAnnotatedString("\u001B[38;2;255mTruncatedTrue\u001B[0m")
        assertEquals("TruncatedTrue", res3.text)
    }

    @Test
    fun test_cjk_nested_color_spans_boundary_alignment() {
        val input = "Prefix \u001B[31m繁體中文 \u001B[32m綠色文字 \u001B[34m藍色標點：，；\u001B[0m Postfix"
        val res = parseAnsiToAnnotatedString(input)
        assertEquals("Prefix 繁體中文 綠色文字 藍色標點：，； Postfix", res.text)

        // Check CJK span boundaries (UTF-16 code units)
        val redSpan = res.spanStyles.first { it.item.color == Color(0xFFCD3131) }
        assertEquals(7, redSpan.start)
        assertEquals(12, redSpan.end)

        val greenSpan = res.spanStyles.first { it.item.color == Color(0xFF0DBC79) }
        assertEquals(12, greenSpan.start)
        assertEquals(17, greenSpan.end)

        val blueSpan = res.spanStyles.first { it.item.color == Color(0xFF2472C8) }
        assertEquals(17, blueSpan.start)
        assertEquals(24, blueSpan.end)
    }

    @Test
    fun test_multi_codepoint_emoji_and_flag_span_boundaries() {
        // Complex family emoji ZWJ sequence: 👨‍👩‍👧‍👦 (11 UTF-16 code units)
        val input1 = "Family: \u001B[33m👨‍👩‍👧‍👦\u001B[0m End"
        val res1 = parseAnsiToAnnotatedString(input1)
        assertEquals("Family: 👨‍👩‍👧‍👦 End", res1.text)
        val emojiSpan = res1.spanStyles.first { it.item.color == Color(0xFFE5E510) }
        assertEquals(8, emojiSpan.start)
        assertEquals(19, emojiSpan.end)

        // Flag emoji: 🇹🇼 (4 UTF-16 code units)
        val input2 = "\u001B[36m🇹🇼\u001B[0m"
        val res2 = parseAnsiToAnnotatedString(input2)
        assertEquals("🇹🇼", res2.text)
        val flagSpan = res2.spanStyles.first { it.item.color == Color(0xFF11A8CD) }
        assertEquals(0, flagSpan.start)
        assertEquals(4, flagSpan.end)
    }

    @Test
    fun test_osc8_link_with_parameters_and_bel_delimiter() {
        val input = "\u001B]8;id=link1;https://warp.dev\u0007Warp Site\u001B]8;;\u0007"
        val res = parseAnsiToAnnotatedString(input)
        assertEquals("Warp Site", res.text)
        val annotations = res.getStringAnnotations("URL", 0, 9)
        assertEquals(1, annotations.size)
        assertEquals("https://warp.dev", annotations[0].item)
    }

    @Test
    fun test_rapid_pty_stream_performance_stress() {
        val sb = StringBuilder()
        for (i in 0 until 1000) {
            sb.append("\u001B[38;5;${i % 256}mLine $i: \u001B[1m繁體中文 \u001B[38;2;${i % 256};100;200mTruecolor\u001B[0m \u001B]8;;https://warp.dev/line/$i\u001B\\Link $i\u001B]8;;\u001B\\\n")
        }
        val ptyStream = sb.toString()
        assertTrue(ptyStream.length > 50000)

        val duration = measureTimeMillis {
            val result = parseAnsiToAnnotatedString(ptyStream)
            assertTrue(result.text.contains("Line 999:"))
            assertTrue(result.spanStyles.isNotEmpty())
        }

        // Must process >50KB ANSI stream in less than 200ms
        assertTrue("Processing took $duration ms, expected < 200ms", duration < 200)
    }
}

package dev.warp.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WarpInputConnectionTest {

    private lateinit var context: Context
    private lateinit var warpInputView: WarpInputView
    private lateinit var inputConnection: WarpInputView.WarpInputConnection

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        warpInputView = WarpInputView(context)
        inputConnection = warpInputView.onCreateInputConnection(android.view.inputmethod.EditorInfo()) as WarpInputView.WarpInputConnection
    }

    @Test
    fun testGboardCjkComposingLifecycleSequence() {
        // Gboard typing Pinyin: setComposingText("nihao")
        inputConnection.setComposingText("nihao", 1)

        // Gboard quirk: fires finishComposingText() right before commitText("你好")
        inputConnection.finishComposingText()

        // Gboard fires commitText("你好")
        val commitRes = inputConnection.commitText("你好", 1)
        assertEquals(true, commitRes)

        // Verify context before cursor holds committed text
        val textBefore = inputConnection.getTextBeforeCursor(10, 0)
        assertNotNull(textBefore)
        assertEquals("你好", textBefore.toString())
    }

    @Test
    fun testUnicodeCodePointCountSurrogatePair() {
        // Test surrogate pair code point counting logic (e.g. 𪚥 has length 2 in UTF-16, codePointCount = 1)
        val surrogateStr = "\uD869\uDEE5" // 𪚥
        assertEquals(2, surrogateStr.length)
        assertEquals(1, surrogateStr.codePointCount(0, surrogateStr.length))

        inputConnection.commitText(surrogateStr, 1)
        val textBefore = inputConnection.getTextBeforeCursor(10, 0)
        assertEquals(surrogateStr, textBefore.toString())
    }

    @Test
    fun testGetTextBeforeCursorLineContext() {
        inputConnection.commitText("git checkout main\n", 1)
        inputConnection.commitText("ls -la", 1)

        val before = inputConnection.getTextBeforeCursor(10, 0)
        assertEquals("ls -la", before.toString())
    }
}

package dev.warp.mobile.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class GhostTextVisualTransformation(private val ghostSuffix: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (ghostSuffix.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val builder = AnnotatedString.Builder(text)
        val startOffset = text.length
        builder.append(ghostSuffix)
        builder.addStyle(
            style = SpanStyle(color = Color.Gray.copy(alpha = 0.5f)),
            start = startOffset,
            end = startOffset + ghostSuffix.length
        )

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset
            override fun transformedToOriginal(offset: Int): Int = offset.coerceAtMost(text.length)
        }

        return TransformedText(builder.toAnnotatedString(), offsetMapping)
    }
}

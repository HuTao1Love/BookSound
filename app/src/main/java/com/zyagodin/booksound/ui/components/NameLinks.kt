package com.zyagodin.booksound.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.zyagodin.booksound.core.library.LibrarySearch

/**
 * [text] with the people it names tappable, each on its own: [names] is the part of [text] that
 * lists them, e.g. "A & B" in "Narrated by A & B". [onClick] gets the tapped name.
 */
@Composable
fun NameLinksText(
    text: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    names: String = text,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val pressed = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val annotated = remember(text, names, linkColor, pressed) {
        buildAnnotatedString {
            append(text)
            val offset = text.indexOf(names)
            if (offset < 0) return@buildAnnotatedString
            val styles = TextLinkStyles(
                style = SpanStyle(color = linkColor),
                pressedStyle = SpanStyle(color = linkColor, background = pressed),
            )
            for (range in LibrarySearch.nameRanges(names)) {
                val name = names.substring(range)
                addLink(
                    LinkAnnotation.Clickable(name, styles) { currentOnClick(name) },
                    offset + range.first, offset + range.last + 1,
                )
            }
        }
    }
    Text(
        annotated,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

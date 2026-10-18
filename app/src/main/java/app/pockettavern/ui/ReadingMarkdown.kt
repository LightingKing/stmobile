package app.pockettavern.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ReadingBlock(val text: String, val code: Boolean = false, val language: String = "")

/** Native, text-only Markdown: never executes HTML, links or scripts from model output. */
@Composable
internal fun ReadingMarkdown(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) {
        val result = mutableListOf<ReadingBlock>()
        val code = mutableListOf<String>()
        var inCode = false
        var language = ""
        content.replace("\r\n", "\n").lines().forEach { line ->
            when {
                line.trimStart().startsWith("```") -> {
                    if (inCode) { result += ReadingBlock(code.joinToString("\n"), true, language); code.clear() }
                    else language = line.trim().removePrefix("```").trim()
                    inCode = !inCode
                }
                inCode -> code += line
                else -> result += ReadingBlock(line.trimEnd())
            }
        }
        if (inCode) result += ReadingBlock(code.joinToString("\n"), true, language)
        result
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            blocks.forEach { block ->
                val line = block.text
                val heading = line.takeWhile { it == '#' }.length
                when {
                    block.code -> Surface(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (block.language.isNotBlank()) Text(block.language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(line, Modifier.horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp))
                        }
                    }
                    heading in 1..6 && line.drop(heading).startsWith(" ") -> Text(line.drop(heading).trim(), Modifier.padding(top = 10.dp, bottom = 4.dp), style = when (heading) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }, fontWeight = FontWeight.SemiBold)
                    line.trimStart().startsWith("> ") -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { ReadingInline(line.trimStart().drop(2)) }
                    }
                    line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        ReadingInline(line.trimStart().drop(2), Modifier.weight(1f))
                    }
                    line.isBlank() -> Spacer(Modifier.height(8.dp))
                    else -> ReadingInline(line)
                }
            }
        }
    }
}

@Composable
private fun ReadingInline(text: String, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val annotated = remember(text, background) { buildAnnotatedString {
        val token = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)")
        var cursor = 0
        token.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val value = match.value
            when {
                value.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value.removeSurrounding("**")) }
                value.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(value.removeSurrounding("*")) }
                else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = background)) { append(value.removeSurrounding("`")) }
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    } }
    Text(annotated, modifier, style = MaterialTheme.typography.bodyLarge)
}

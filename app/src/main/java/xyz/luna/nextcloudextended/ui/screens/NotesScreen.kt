package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.data.model.NextcloudNote

@Composable
fun NotesScreen(
    notes: List<NextcloudNote>,
    onNoteSelected: (NextcloudNote) -> Unit,
    onToggleFavorite: (NextcloudNote) -> Unit
) {
    val s = LocalStrings.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredNotes = remember(notes, searchQuery) {
        if (searchQuery.isBlank()) notes
        else notes.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.content.contains(searchQuery, ignoreCase = true) ||
            it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text(s.searchNotes) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true, shape = RoundedCornerShape(12.dp)
        )

        if (filteredNotes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searchQuery.isBlank()) s.noNotes else s.noResultsFor(searchQuery),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                items(filteredNotes) { note ->
                    NoteItem(note, onClick = { onNoteSelected(note) }, onToggleFav = { onToggleFavorite(note) })
                }
            }
        }
    }
}

@Composable
fun NoteItem(note: NextcloudNote, onClick: () -> Unit, onToggleFav: () -> Unit) {
    val s = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (note.category.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text(note.category.uppercase(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(note.title, style = MaterialTheme.typography.titleSmall)
                if (note.content.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val preview = note.content.lines().firstOrNull { it.trim().isNotEmpty() }?.take(80) ?: ""
                    Text(if (preview.length >= 80) "$preview…" else preview,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { onToggleFav() }) {
                Icon(
                    imageVector = if (note.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = s.favorite,
                    tint = if (note.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Lightweight Markdown renderer using AnnotatedString — covers 90% of Nextcloud Notes content
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val baseSize = 14.sp
    val annotated = remember(markdown) {
        buildAnnotatedString {
            markdown.lines().forEach { rawLine ->
                val line = rawLine.trimEnd()
                when {
                    line.startsWith("### ") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)) { appendInlineMarkdown(line.removePrefix("### ")) }
                    }
                    line.startsWith("## ") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) { appendInlineMarkdown(line.removePrefix("## ")) }
                    }
                    line.startsWith("# ") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) { appendInlineMarkdown(line.removePrefix("# ")) }
                    }
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        append("• "); appendInlineMarkdown(line.drop(2))
                    }
                    line.startsWith("  - ") || line.startsWith("  * ") -> {
                        append("   ◦ "); appendInlineMarkdown(line.trimStart().drop(2))
                    }
                    line.startsWith("```") -> { /* skip code fences */ }
                    else -> appendInlineMarkdown(line)
                }
                append("\n")
            }
        }
    }
    Text(annotated, modifier = modifier, lineHeight = 20.sp)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    val boldItalicRegex = Regex("""\*\*\*(.*?)\*\*\*""")
    val boldRegex = Regex("""\*\*(.*?)\*\*""")
    val italicRegex = Regex("""\*(.*?)\*""")
    val codeRegex = Regex("""`(.*?)`""")

    val allPatterns = Regex("""\*\*\*(.*?)\*\*\*|\*\*(.*?)\*\*|\*(.*?)\*|`(.*?)`""")
    var last = 0
    for (match in allPatterns.findAll(text)) {
        if (match.range.first > last) append(text.substring(last, match.range.first))
        val raw = match.value
        when {
            raw.startsWith("***") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(match.groupValues[1]) }
            raw.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[2]) }
            raw.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[3]) }
            raw.startsWith("`") -> withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)) { append(match.groupValues[4]) }
        }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

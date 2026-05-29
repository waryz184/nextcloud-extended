package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.data.model.NextcloudNote

@Composable
fun NotesScreen(
    notes: List<NextcloudNote>,
    onNoteSelected: (NextcloudNote) -> Unit,
    onToggleFavorite: (NextcloudNote) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Toutes vos notes :", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune note trouvée.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                items(notes) { note ->
                    NoteItem(note, onClick = { onNoteSelected(note) }, onToggleFav = { onToggleFavorite(note) })
                }
            }
        }
    }
}

@Composable
fun NoteItem(note: NextcloudNote, onClick: () -> Unit, onToggleFav: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (note.category.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = note.category.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(note.title, style = MaterialTheme.typography.titleSmall)
                if (note.content.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (note.content.length > 80) note.content.take(80) + "…" else note.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { onToggleFav() }) {
                Icon(
                    imageVector = if (note.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favori",
                    tint = if (note.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

package xyz.luna.nextcloudextended.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.Strings
import xyz.luna.nextcloudextended.data.model.LabeledValue
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.PostalAddress
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val phoneTypes = listOf("CELL", "HOME", "WORK", "FAX", "OTHER")
private val emailTypes = listOf("HOME", "WORK", "OTHER")
private val addressTypes = listOf("HOME", "WORK", "OTHER")

private fun typeLabel(s: Strings, token: String): String = when (token.uppercase()) {
    "CELL", "MOBILE" -> s.typeMobile
    "HOME" -> s.typeHome
    "WORK" -> s.typeWork
    "FAX" -> s.typeFax
    "OTHER", "" -> s.typeOther
    else -> token
}

private fun formatAddress(a: PostalAddress): String =
    listOf(a.street, listOf(a.postalCode, a.city).filter { it.isNotBlank() }.joinToString(" "), a.country)
        .filter { it.isNotBlank() }.joinToString(", ")

@Composable
fun ContactsScreen(
    addressBooks: List<Pair<String, String>>,
    selectedName: String,
    contacts: List<NextcloudContact>,
    onAddressBookSelected: (String, String) -> Unit,
    onContactSelected: (NextcloudContact) -> Unit
) {
    val s = LocalStrings.current
    var dropdownExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) contacts
        else contacts.filter {
            it.fullName.contains(searchQuery, true) ||
            it.organization?.contains(searchQuery, true) == true ||
            it.phones.any { p -> p.value.contains(searchQuery, true) } ||
            it.emails.any { e -> e.value.contains(searchQuery, true) } ||
            it.categories.any { c -> c.contains(searchQuery, true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (addressBooks.size > 1) {
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Card(modifier = Modifier.fillMaxWidth().clickable { dropdownExpanded = true }, shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(selectedName.ifEmpty { s.selectEllipsis }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                    addressBooks.forEach { book ->
                        DropdownMenuItem(text = { Text(book.second) }, onClick = { dropdownExpanded = false; onAddressBookSelected(book.first, book.second) })
                    }
                }
            }
        }

        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text(s.searchContacts) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true, shape = RoundedCornerShape(12.dp)
        )

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searchQuery.isBlank()) s.noContacts else s.noResultsFor(searchQuery), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(filtered) { contact -> ContactItem(contact, onClick = { onContactSelected(contact) }) }
            }
        }
    }
}

@Composable
private fun ContactItem(contact: NextcloudContact, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(contact.fullName, contact.photoBase64, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.fullName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = contact.phones.firstOrNull()?.value ?: contact.emails.firstOrNull()?.value ?: contact.organization ?: ""
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ContactAvatar(name: String, photoBase64: String?, size: Dp) {
    val img = rememberDecodedPhoto(photoBase64)
    if (img != null) {
        Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        val initials = name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("")
        Box(modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(initials.ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun rememberDecodedPhoto(base64: String?): ImageBitmap? = remember(base64) {
    if (base64.isNullOrBlank()) null
    else runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

// ── Detail sheet ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailSheet(
    contact: NextcloudContact,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDial: (String) -> Unit,
    onSendMail: (String) -> Unit,
    onOpenMap: (String) -> Unit
) {
    val s = LocalStrings.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(s.deleteContactTitle) },
            text = { Text(s.deleteContactConfirm(contact.fullName)) },
            confirmButton = { Button(onClick = { showDeleteConfirm = false; onDismiss(); onDelete() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(s.delete) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(s.cancel) } }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactAvatar(contact.fullName, contact.photoBase64, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(contact.fullName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (!contact.organization.isNullOrBlank()) Text(contact.organization, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))

            contact.phones.forEach { p ->
                DetailRow(Icons.Default.Phone, p.value, p.type.takeIf { it.isNotBlank() }?.let { typeLabel(s, it) }) { onDial(p.value) }
            }
            contact.emails.forEach { e ->
                DetailRow(Icons.Default.Email, e.value, e.type.takeIf { it.isNotBlank() }?.let { typeLabel(s, it) }) { onSendMail(e.value) }
            }
            contact.addresses.forEach { a ->
                DetailRow(Icons.Default.LocationOn, formatAddress(a), a.type.takeIf { it.isNotBlank() }?.let { typeLabel(s, it) }) { onOpenMap(formatAddress(a)) }
            }
            contact.birthday?.takeIf { it.isNotBlank() }?.let { DetailRow(Icons.Default.Cake, it, s.birthday) }
            contact.categories.takeIf { it.isNotEmpty() }?.let { DetailRow(Icons.Default.Label, it.joinToString(", "), s.groups) }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDeleteConfirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(s.delete)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onDismiss(); onEdit() }) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(s.edit)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
    Row(
        modifier = (if (onClick != null) base.clickable { onClick() } else base).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Add / edit dialog ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDialog(
    initial: NextcloudContact?,
    onDismiss: () -> Unit,
    onSave: (NextcloudContact) -> Unit
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = initial ?: NextcloudContact("", "", emptyList(), emptyList(), null, addressBookHref = "")

    var fullName by remember { mutableStateOf(base.fullName.takeIf { it != "?" } ?: "") }
    var organization by remember { mutableStateOf(base.organization ?: "") }
    var birthday by remember { mutableStateOf(base.birthday ?: "") }
    var photoBase64 by remember { mutableStateOf(base.photoBase64) }
    var photoMime by remember { mutableStateOf(base.photoMimeType) }
    val phones = remember { mutableStateListOf<LabeledValue>().apply { addAll(base.phones); if (isEmpty()) add(LabeledValue("", "CELL")) } }
    val emails = remember { mutableStateListOf<LabeledValue>().apply { addAll(base.emails); if (isEmpty()) add(LabeledValue("", "HOME")) } }
    val addresses = remember { mutableStateListOf<PostalAddress>().apply { addAll(base.addresses) } }
    val categories = remember { mutableStateListOf<String>().apply { addAll(base.categories) } }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes?.let { compressPhoto(it) }
                }.getOrNull()
            }
            if (result != null) { photoBase64 = result.first; photoMime = result.second }
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = birthday.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() }.getOrNull()
        }
    )
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    datePickerState.selectedDateMillis?.let { millis ->
                        birthday = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(s.cancel) } }
        ) { DatePicker(state = datePickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) s.newContact else s.editContact) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Photo
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    ContactAvatar(fullName.ifBlank { "?" }, photoBase64, 64.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = { photoPicker.launch("image/*") }) {
                            Icon(Icons.Default.AddAPhoto, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text(if (photoBase64 == null) s.addPhoto else s.changePhoto)
                        }
                        if (photoBase64 != null) {
                            TextButton(onClick = { photoBase64 = null; photoMime = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text(s.removePhoto)
                            }
                        }
                    }
                }

                OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text(s.name) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = organization, onValueChange = { organization = it }, label = { Text(s.organization) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))

                // Birthday
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    OutlinedTextField(value = birthday, onValueChange = {}, readOnly = true, label = { Text(s.birthday) }, placeholder = { Text(s.dueDatePlaceholder) }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Default.DateRange, s.birthday, tint = MaterialTheme.colorScheme.primary) }
                    if (birthday.isNotEmpty()) IconButton(onClick = { birthday = "" }) { Icon(Icons.Default.Clear, s.clearDate, tint = MaterialTheme.colorScheme.error) }
                }

                SectionLabel(s.phone)
                LabeledValueEditor(phones, s.phone, s.addPhone, phoneTypes, KeyboardType.Phone)

                SectionLabel(s.email)
                LabeledValueEditor(emails, s.email, s.addEmail, emailTypes, KeyboardType.Email)

                SectionLabel(s.address)
                AddressEditor(addresses)

                SectionLabel(s.groups)
                CategoriesEditor(categories)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (fullName.isNotBlank()) {
                    onDismiss()
                    onSave(base.copy(
                        fullName = fullName.trim(),
                        phones = phones.filter { it.value.isNotBlank() }.map { it.copy(value = it.value.trim()) },
                        emails = emails.filter { it.value.isNotBlank() }.map { it.copy(value = it.value.trim()) },
                        organization = organization.trim().ifBlank { null },
                        addresses = addresses.filter { !it.isEmpty },
                        birthday = birthday.trim().ifBlank { null },
                        photoBase64 = photoBase64,
                        photoMimeType = photoMime,
                        categories = categories.map { it.trim() }.filter { it.isNotBlank() }
                    ))
                }
            }) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun TypeSelector(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(typeLabel(s, selected))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(typeLabel(s, opt)) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

@Composable
private fun LabeledValueEditor(
    items: SnapshotStateList<LabeledValue>,
    label: String,
    addLabel: String,
    typeOptions: List<String>,
    keyboardType: KeyboardType
) {
    items.forEachIndexed { i, item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = item.value, onValueChange = { items[i] = item.copy(value = it) },
                label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Default.Clear, null) }
        }
        TypeSelector(selected = item.type, options = typeOptions, onSelect = { items[i] = item.copy(type = it) })
    }
    TextButton(onClick = { items.add(LabeledValue("", typeOptions.first())) }) {
        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(addLabel)
    }
}

@Composable
private fun AddressEditor(addresses: SnapshotStateList<PostalAddress>) {
    val s = LocalStrings.current
    addresses.forEachIndexed { i, adr ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TypeSelector(selected = adr.type, options = addressTypes, onSelect = { addresses[i] = adr.copy(type = it) })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { addresses.removeAt(i) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                }
                OutlinedTextField(value = adr.street, onValueChange = { addresses[i] = adr.copy(street = it) }, label = { Text(s.street) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                OutlinedTextField(value = adr.city, onValueChange = { addresses[i] = adr.copy(city = it) }, label = { Text(s.city) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                OutlinedTextField(value = adr.postalCode, onValueChange = { addresses[i] = adr.copy(postalCode = it) }, label = { Text(s.postalCode) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                OutlinedTextField(value = adr.country, onValueChange = { addresses[i] = adr.copy(country = it) }, label = { Text(s.country) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    TextButton(onClick = { addresses.add(PostalAddress(type = "HOME")) }) {
        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(s.addAddress)
    }
}

@Composable
private fun CategoriesEditor(items: SnapshotStateList<String>) {
    val s = LocalStrings.current
    items.forEachIndexed { i, value ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = value, onValueChange = { items[i] = it }, label = { Text(s.group) }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Default.Clear, null) }
        }
    }
    TextButton(onClick = { items.add("") }) {
        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(s.addGroup)
    }
}

// ── Photo helpers ────────────────────────────────────────────────────────────

// Decodes, downsamples and JPEG-recompresses a picked image so the vCard PHOTO stays small.
private fun compressPhoto(bytes: ByteArray): Pair<String, String>? {
    val bmp = decodeSampled(bytes, 512) ?: return null
    val max = 512f
    val largest = maxOf(bmp.width, bmp.height).coerceAtLeast(1)
    val scaled = if (largest > max) {
        val scale = max / largest
        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    } else bmp
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
}

private fun decodeSampled(bytes: ByteArray, reqSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > reqSize * 2 || bounds.outHeight / sample > reqSize * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

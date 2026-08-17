package xyz.luna.nextcloudextended.sync

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SyncResult
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import android.util.Log
import xyz.luna.nextcloudextended.account.NextcloudAccountManager
import xyz.luna.nextcloudextended.data.model.LabeledValue
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.PostalAddress
import xyz.luna.nextcloudextended.data.network.CalDavClient
import java.util.UUID

class ContactSyncEngine(private val context: Context) {
    private companion object {
        private const val TAG = "ContactSync"
        private const val BATCH_SIZE = 100
    }

    private val resolver: ContentResolver get() = context.contentResolver
    private val am = NextcloudAccountManager(context)

    fun sync(account: Account, syncResult: SyncResult) {
        val serverUrl = am.serverUrlOf(account)
        val username = am.usernameOf(account)
        val password = am.passwordOf(account)
        var addressBookHref = am.addressBookHrefOf(account)

        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            Log.w(TAG, "Account $account has incomplete credentials")
            return
        }

        if (addressBookHref.isBlank()) {
            try {
                val client = CalDavClient(serverUrl, username, password)
                val books = client.getAddressBooksSync()
                if (books.isEmpty()) return
                addressBookHref = books.first().first
                am.setAddressBook(account, books.first().first, books.first().second)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch address books", e)
                syncResult.stats.numIoExceptions++
                return
            }
        }

        val client = CalDavClient(serverUrl, username, password)

        // 1. Fetch server contacts
        val serverContacts: List<NextcloudContact>
        try {
            serverContacts = client.getContactsSync(addressBookHref)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch contacts from server", e)
            syncResult.stats.numIoExceptions++
            return
        }

        // 2. Load local RawContacts for this account.
        val localByUid = loadLocalContacts(account)

        // 3. Build batch: remote → local
        val ops = mutableListOf<ContentProviderOperation>()
        val serverUids = mutableSetOf<String>()

        for (server in serverContacts) {
            serverUids.add(server.uid)
            val local = localByUid[server.uid]
            if (local == null) {
                val rawIndex = ops.size
                ops.add(
                    ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                        .withValue(RawContacts.ACCOUNT_NAME, account.name)
                        .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                        .withValue(RawContacts.SOURCE_ID, server.uid)
                        .withValue(RawContacts.SYNC1, server.etag)
                        .withYieldAllowed(true)
                        .build()
                )
                addDataRows(ops, rawIndex, server, account)
                syncResult.stats.numInserts++
            } else {
                if (server.etag.isNotEmpty() && server.etag != local.etag) {
                    val rawId = local.rawContactId
                    ops.add(
                        ContentProviderOperation.newUpdate(
                            RawContacts.CONTENT_URI.buildUpon().appendPath(rawId.toString()).build()
                        )
                            .withValue(RawContacts.SYNC1, server.etag)
                            .withYieldAllowed(true)
                            .build()
                    )
                    ops.add(
                        ContentProviderOperation.newDelete(
                            dataUri()
                        )
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                                arrayOf(rawId.toString())
                            )
                            .withYieldAllowed(true)
                            .build()
                    )
                    addDataRowsRaw(ops, rawId, server, account)
                    syncResult.stats.numUpdates++
                }
                localByUid.remove(server.uid)
            }
            if (ops.size >= BATCH_SIZE) {
                applyBatch(ops, syncResult)
                ops.clear()
            }
        }

        for ((_, local) in localByUid) {
            ops.add(
                ContentProviderOperation.newDelete(
                    RawContacts.CONTENT_URI.buildUpon().appendPath(local.rawContactId.toString()).build()
                )
                    .withYieldAllowed(true)
                    .build()
            )
            syncResult.stats.numDeletes++
            if (ops.size >= BATCH_SIZE) {
                applyBatch(ops, syncResult)
                ops.clear()
            }
        }
        if (ops.isNotEmpty()) {
            applyBatch(ops, syncResult)
        }

        uploadLocalChanges(account, addressBookHref, client, syncResult, serverUids)
    }

    // ── Local data model ─────────────────────────────────────────────────────────

    private data class LocalContact(
        val rawContactId: Long,
        val uid: String,
        val etag: String
    )

    private fun loadLocalContacts(account: Account): MutableMap<String, LocalContact> {
        val result = mutableMapOf<String, LocalContact>()
        val uri = RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()
        resolver.query(uri, arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1), null, null, null)
            ?.use {
                while (it.moveToNext()) {
                    val uid = it.getString(1) ?: ""
                    if (uid.isNotBlank()) {
                        result[uid] = LocalContact(it.getLong(0), uid, it.getString(2) ?: "")
                    }
                }
            }
        return result
    }

    // ── Data row builders ────────────────────────────────────────────────────────

    private fun addDataRows(ops: MutableList<ContentProviderOperation>, rawIndex: Int, server: NextcloudContact, account: Account) {
        if (server.fullName.isNotBlank() && server.fullName != "?") {
            ops.add(dataInsert(rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, server.fullName)
                .withValue(StructuredName.GIVEN_NAME, server.fullName.substringBeforeLast(' '))
                .withValue(StructuredName.FAMILY_NAME, server.fullName.substringAfterLast(' '))
                .build())
        }
        for (phone in server.phones) {
            ops.add(dataInsert(rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, phone.value)
                .withValue(Phone.TYPE, phoneType(phone.type))
                .build())
        }
        for (email in server.emails) {
            ops.add(dataInsert(rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, email.value)
                .withValue(Email.TYPE, emailType(email.type))
                .build())
        }
        server.organization?.takeIf { it.isNotBlank() }?.let {
            ops.add(dataInsert(rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, it)
                .withValue(Organization.TYPE, Organization.TYPE_WORK)
                .build())
        }
        for (addr in server.addresses) {
            ops.add(dataInsert(rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.STREET, addr.street)
                .withValue(StructuredPostal.CITY, addr.city)
                .withValue(StructuredPostal.POSTCODE, addr.postalCode)
                .withValue(StructuredPostal.COUNTRY, addr.country)
                .withValue(StructuredPostal.TYPE, postalType(addr.type))
                .build())
        }
        server.birthday?.takeIf { it.isNotBlank() }?.let {
            ops.add(dataInsert(rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, it)
                .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                .build())
        }
        server.photoBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                ops.add(dataInsert(rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, bytes)
                    .build())
            } catch (_: IllegalArgumentException) {}
        }
        for (groupTitle in server.categories) {
            val groupId = ensureGroup(account, groupTitle)
            if (groupId != null) {
                ops.add(dataInsert(rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, groupId)
                    .build())
            }
        }
    }

    private fun addDataRowsRaw(ops: MutableList<ContentProviderOperation>, rawId: Long, server: NextcloudContact, account: Account) {
        if (server.fullName.isNotBlank() && server.fullName != "?") {
            ops.add(dataInsertRaw(rawId)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, server.fullName)
                .withValue(StructuredName.GIVEN_NAME, server.fullName.substringBeforeLast(' '))
                .withValue(StructuredName.FAMILY_NAME, server.fullName.substringAfterLast(' '))
                .build())
        }
        for (phone in server.phones) {
            ops.add(dataInsertRaw(rawId)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, phone.value)
                .withValue(Phone.TYPE, phoneType(phone.type))
                .build())
        }
        for (email in server.emails) {
            ops.add(dataInsertRaw(rawId)
                .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, email.value)
                .withValue(Email.TYPE, emailType(email.type))
                .build())
        }
        server.organization?.takeIf { it.isNotBlank() }?.let {
            ops.add(dataInsertRaw(rawId)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, it)
                .withValue(Organization.TYPE, Organization.TYPE_WORK)
                .build())
        }
        for (addr in server.addresses) {
            ops.add(dataInsertRaw(rawId)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.STREET, addr.street)
                .withValue(StructuredPostal.CITY, addr.city)
                .withValue(StructuredPostal.POSTCODE, addr.postalCode)
                .withValue(StructuredPostal.COUNTRY, addr.country)
                .withValue(StructuredPostal.TYPE, postalType(addr.type))
                .build())
        }
        server.birthday?.takeIf { it.isNotBlank() }?.let {
            ops.add(dataInsertRaw(rawId)
                .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, it)
                .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                .build())
        }
        server.photoBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                ops.add(dataInsertRaw(rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, bytes)
                    .build())
            } catch (_: IllegalArgumentException) {}
        }
        for (groupTitle in server.categories) {
            val groupId = ensureGroup(account, groupTitle)
            if (groupId != null) {
                ops.add(dataInsertRaw(rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, groupId)
                    .build())
            }
        }
    }

    // ── Upload local changes to server ───────────────────────────────────────────

    private fun uploadLocalChanges(
        account: Account,
        addressBookHref: String,
        client: CalDavClient,
        syncResult: SyncResult,
        serverUids: Set<String>
    ) {
        val localRawIds = getLocalDirtyRawIds(account)
        for (rawId in localRawIds) {
            try {
                val isDeleted = isDeletedRaw(rawId)
                if (isDeleted) {
                    val uid = getUidForRawId(account, rawId)
                    if (uid != null) {
                        try {
                            client.deleteContactSync(
                                NextcloudContact(uid = uid, fullName = "", phones = emptyList(), emails = emptyList(), organization = null, addressBookHref = addressBookHref, href = "${addressBookHref.trimEnd('/')}/$uid.vcf")
                            )
                        } catch (_: Exception) {}
                    }
                    syncResult.stats.numDeletes++
                    continue
                }

                // Contact created locally has no SOURCE_ID yet → generate a stable UID now.
                var uid = getUidForRawId(account, rawId)
                if (uid.isNullOrBlank()) {
                    uid = UUID.randomUUID().toString()
                    val values = ContentValues(1).apply { put(RawContacts.SOURCE_ID, uid) }
                    resolver.update(
                        RawContacts.CONTENT_URI.buildUpon().appendPath(rawId.toString()).build(),
                        values, null, null
                    )
                }

                val contact = buildNextcloudContact(account, uid, addressBookHref)
                if (contact == null) continue
                val href = getServerHref(account, uid)
                val etag = client.saveContactSync(contact.copy(href = href))
                updateLocalEtag(account, uid, etag)
                clearDirty(rawId)
                syncResult.stats.numUpdates++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload local change for rawId=$rawId", e)
                syncResult.stats.numIoExceptions++
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun dataUri() = ContactsContract.Data.CONTENT_URI.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .build()

    private fun dataInsert(rawIndex: Int) =
        ContentProviderOperation.newInsert(dataUri())
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)

    private fun dataInsertRaw(rawId: Long) =
        ContentProviderOperation.newInsert(dataUri())
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)

    private fun phoneType(type: String): Int = when (type.uppercase()) {
        "CELL" -> Phone.TYPE_MOBILE
        "HOME" -> Phone.TYPE_HOME
        "WORK" -> Phone.TYPE_WORK
        "FAX" -> Phone.TYPE_FAX_WORK
        "FAX,WORK" -> Phone.TYPE_FAX_WORK
        "FAX,HOME" -> Phone.TYPE_FAX_HOME
        "PAGER" -> Phone.TYPE_PAGER
        else -> Phone.TYPE_OTHER
    }

    private fun emailType(type: String): Int = when (type.uppercase()) {
        "HOME" -> Email.TYPE_HOME
        "WORK" -> Email.TYPE_WORK
        "MOBILE" -> Email.TYPE_MOBILE
        else -> Email.TYPE_OTHER
    }

    private fun postalType(type: String): Int = when (type.uppercase()) {
        "HOME" -> StructuredPostal.TYPE_HOME
        "WORK" -> StructuredPostal.TYPE_WORK
        else -> StructuredPostal.TYPE_OTHER
    }

    private fun applyBatch(ops: MutableList<ContentProviderOperation>, syncResult: SyncResult) {
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        } catch (e: Exception) {
            Log.e(TAG, "Batch apply failed, retrying individually", e)
            for (op in ops) {
                try {
                    resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op))
                } catch (e2: Exception) {
                    Log.e(TAG, "Individual operation failed", e2)
                    syncResult.stats.numIoExceptions++
                }
            }
        }
    }

    private fun getLocalDirtyRawIds(account: Account): List<Long> {
        val ids = mutableListOf<Long>()
        val uri = RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()
        resolver.query(uri, arrayOf(RawContacts._ID, RawContacts.DIRTY), null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getInt(1) == 1) ids.add(it.getLong(0))
            }
        }
        return ids
    }

    private fun getUidForRawId(account: Account, rawId: Long): String? {
        val uri = RawContacts.CONTENT_URI.buildUpon()
            .appendPath(rawId.toString())
            .build()
        resolver.query(uri, arrayOf(RawContacts.SOURCE_ID), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private fun isDeletedRaw(rawId: Long): Boolean {
        val uri = RawContacts.CONTENT_URI.buildUpon()
            .appendPath(rawId.toString())
            .build()
        resolver.query(uri, arrayOf(RawContacts.DELETED), null, null, null)?.use {
            if (it.moveToFirst()) return it.getInt(0) == 1
        }
        return false
    }

    private fun updateLocalEtag(account: Account, uid: String, etag: String) {
        val values = ContentValues(1).apply { put(RawContacts.SYNC1, etag) }
        resolver.update(
            RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                .build(),
            values,
            "${RawContacts.SOURCE_ID} = ?",
            arrayOf(uid)
        )
    }

    private fun clearDirty(rawId: Long) {
        val values = ContentValues(1).apply { put(RawContacts.DIRTY, 0) }
        resolver.update(
            RawContacts.CONTENT_URI.buildUpon().appendPath(rawId.toString()).build(),
            values, null, null
        )
    }

    private fun buildNextcloudContact(account: Account, uid: String, addressBookHref: String): NextcloudContact? {
        val uri = RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()
        var rawId: Long = -1L
        val cursor = resolver.query(uri, arrayOf(RawContacts._ID), "${RawContacts.SOURCE_ID} = ?", arrayOf(uid), null)
        cursor?.use {
            if (it.moveToFirst()) rawId = it.getLong(0)
        }
        if (rawId < 0) return null

        val dataCursor = resolver.query(dataUri(),
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5,
                ContactsContract.Data.DATA6,
                ContactsContract.Data.DATA7,
                ContactsContract.Data.DATA15
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawId.toString()),
            null
        ) ?: return null

        var fullName = "?"
        val phones = mutableListOf<LabeledValue>()
        val emails = mutableListOf<LabeledValue>()
        var organization: String? = null
        val addresses = mutableListOf<PostalAddress>()
        var birthday: String? = null
        var photoB64: String? = null
        var photoMime: String? = null
        val categories = mutableListOf<String>()

        dataCursor.use {
            while (it.moveToNext()) {
                val mime = it.getString(0) ?: ""
                val data1 = it.getString(1) ?: ""
                when (mime) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        if (data1.isNotBlank()) fullName = data1
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        phones.add(LabeledValue(data1, phoneTypeToLabel(it.getInt(2))))
                    }
                    Email.CONTENT_ITEM_TYPE -> {
                        emails.add(LabeledValue(data1, emailTypeToLabel(it.getInt(2))))
                    }
                    Organization.CONTENT_ITEM_TYPE -> {
                        if (organization == null && data1.isNotBlank()) organization = data1
                    }
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        addresses.add(PostalAddress(
                            type = postalTypeToLabel(it.getInt(2)),
                            street = it.getString(4) ?: "",
                            city = it.getString(5) ?: "",
                            postalCode = it.getString(6) ?: "",
                            country = it.getString(7) ?: ""
                        ))
                    }
                    Event.CONTENT_ITEM_TYPE -> {
                        if (it.getInt(2) == Event.TYPE_BIRTHDAY) birthday = data1
                    }
                    Photo.CONTENT_ITEM_TYPE -> {
                        val bytes = it.getBlob(7)
                        if (bytes != null && bytes.isNotEmpty()) {
                            photoB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            photoMime = "image/jpeg"
                        }
                    }
                    GroupMembership.CONTENT_ITEM_TYPE -> {
                        val groupId = it.getLong(2)
                        val groupName = getGroupName(groupId)
                        if (groupName != null) categories.add(groupName)
                    }
                }
            }
        }

        return NextcloudContact(
            uid = uid,
            fullName = fullName,
            phones = phones,
            emails = emails,
            organization = organization,
            addresses = addresses,
            birthday = birthday,
            photoBase64 = photoB64,
            photoMimeType = photoMime,
            categories = categories,
            addressBookHref = addressBookHref,
            href = getServerHref(account, uid)
        )
    }

    private fun getGroupName(groupId: Long): String? {
        resolver.query(ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups._ID} = ?",
            arrayOf(groupId.toString()), null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private fun ensureGroup(account: Account, title: String): Long? {
        val uri = ContactsContract.Groups.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.Groups.ACCOUNT_NAME, account.name)
            .appendQueryParameter(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
            .build()
        resolver.query(uri, arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE), null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(1) == title) return it.getLong(0)
            }
        }
        val values = ContentValues().apply {
            put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
            put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Groups.TITLE, title)
            put(ContactsContract.Groups.GROUP_VISIBLE, true)
        }
        val result = resolver.insert(ContactsContract.Groups.CONTENT_URI, values)
        return result?.lastPathSegment?.toLongOrNull()
    }

    private fun getServerHref(account: Account, uid: String): String {
        val ab = am.addressBookHrefOf(account)
        val base = if (ab.endsWith("/")) ab else "$ab/"
        return "$base$uid.vcf"
    }

    private fun phoneTypeToLabel(type: Int): String = when (type) {
        Phone.TYPE_MOBILE -> "CELL"
        Phone.TYPE_HOME -> "HOME"
        Phone.TYPE_WORK -> "WORK"
        Phone.TYPE_FAX_WORK -> "FAX,WORK"
        Phone.TYPE_FAX_HOME -> "FAX,HOME"
        Phone.TYPE_PAGER -> "PAGER"
        else -> ""
    }

    private fun emailTypeToLabel(type: Int): String = when (type) {
        Email.TYPE_HOME -> "HOME"
        Email.TYPE_WORK -> "WORK"
        Email.TYPE_MOBILE -> "MOBILE"
        else -> ""
    }

    private fun postalTypeToLabel(type: Int): String = when (type) {
        StructuredPostal.TYPE_HOME -> "HOME"
        StructuredPostal.TYPE_WORK -> "WORK"
        else -> ""
    }
}
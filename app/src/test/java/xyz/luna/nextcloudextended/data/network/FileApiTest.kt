package xyz.luna.nextcloudextended.data.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.luna.nextcloudextended.data.network.FileApi.CollisionPolicy
import xyz.luna.nextcloudextended.data.network.FileApi.UploadOptions
import xyz.luna.nextcloudextended.data.network.FileApi.UploadSource

class FileApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dav: FakeDavServer
    private lateinit var api: FileApi
    private val root = "/remote.php/dav/files/alice/"

    @Before fun setUp() {
        dav = FakeDavServer()
        server = MockWebServer().also { it.dispatcher = dav; it.start() }
        api = FileApi(DavSession(server.url("/").toString().trimEnd('/'), "alice", "Basic x", allowInsecureHttp = true, sleeper = { }))
    }

    @After fun tearDown() { server.shutdown() }

    private fun bytes(size: Int, seed: Int = 1) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    // ── listing / metadata ──────────────────────────────────────────────────────────────────

    @Test fun listReturnsChildrenWithoutTheFolderItself() {
        dav.files["${root}docs.txt"] = "hello".toByteArray()
        dav.dirs.add("${root}Photos/")
        val items = api.list(root)
        assertEquals(setOf("docs.txt", "Photos"), items.map { it.name }.toSet())
        val file = items.first { it.name == "docs.txt" }
        assertEquals(5L, file.size)
        assertEquals("${root}docs.txt", file.path)
        assertNotNull(file.etag)
        assertEquals("1", file.fileId)
        assertTrue(file.canDelete)
        assertTrue(items.first { it.name == "Photos" }.isDirectory)
    }

    @Test fun statReturnsNullForMissing() {
        assertNull(api.stat("${root}nope.txt"))
    }

    // ── mutations ───────────────────────────────────────────────────────────────────────────

    @Test fun deleteIsIdempotent() {
        dav.files["${root}a.txt"] = byteArrayOf(1)
        api.delete("${root}a.txt")
        api.delete("${root}a.txt") // already gone: must not fail (offline replay after a lost answer)
        assertFalse(dav.files.containsKey("${root}a.txt"))
    }

    @Test fun mkdirReportsExistingFolderInsteadOfFailing() {
        assertTrue(api.mkdir("${root}New"))
        assertFalse("second MKCOL (405) means already there", api.mkdir("${root}New"))
    }

    @Test fun mkdirCreatesMissingParents() {
        assertTrue(api.mkdir("${root}a/b/c", createParents = true))
        assertTrue("${root}a/b/c/" in dav.dirs)
    }

    @Test fun moveRefusesToMoveIntoItself() {
        dav.dirs.add("${root}Dir/")
        try { api.move("${root}Dir/", "${root}Dir/Sub/"); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun moveWithoutOverwriteSurfacesPreconditionFailure() {
        dav.files["${root}a.txt"] = byteArrayOf(1)
        dav.files["${root}b.txt"] = byteArrayOf(2)
        try { api.move("${root}a.txt", "${root}b.txt"); fail() } catch (e: HttpStatusException) {
            assertEquals(412, e.code)
        }
        api.move("${root}a.txt", "${root}b.txt", overwrite = true)
        assertArrayEquals(byteArrayOf(1), dav.files["${root}b.txt"])
    }

    @Test fun renameKeepsTheFolderAndEncodesSpecialCharacters() {
        dav.files["${root}old.txt"] = byteArrayOf(1)
        api.rename("${root}old.txt", "new name+1#.txt")
        assertTrue(dav.files.containsKey("${root}new name+1#.txt"))
    }

    // ── simple upload ───────────────────────────────────────────────────────────────────────

    @Test fun uploadSendsLengthMtimeAndCreateOnlyPrecondition() {
        val payload = bytes(2048)
        val result = api.upload(root, "file.bin", UploadSource.ofBytes(payload),
            UploadOptions(policy = CollisionPolicy.FAIL))
        assertEquals("${root}file.bin", result.path)
        assertNotNull(result.etag)
        assertArrayEquals(payload, dav.files["${root}file.bin"])
    }

    @Test fun uploadFailPolicyRaisesConflictWithServerEtag() {
        dav.files["${root}file.bin"] = byteArrayOf(9)
        try {
            api.upload(root, "file.bin", UploadSource.ofBytes(byteArrayOf(1)), UploadOptions(policy = CollisionPolicy.FAIL))
            fail()
        } catch (e: ConflictException) {
            assertEquals(dav.etag("${root}file.bin"), e.serverEtag)
        }
        assertArrayEquals("existing file must be untouched", byteArrayOf(9), dav.files["${root}file.bin"])
    }

    @Test fun uploadRenamePolicyKeepsBothFiles() {
        dav.files["${root}photo.jpg"] = byteArrayOf(9)
        dav.files["${root}photo (2).jpg"] = byteArrayOf(8)
        val result = api.upload(root, "photo.jpg", UploadSource.ofBytes(byteArrayOf(1)), UploadOptions(policy = CollisionPolicy.RENAME))
        assertTrue(result.renamed)
        assertEquals("${root}photo (3).jpg", result.path)
        assertArrayEquals(byteArrayOf(9), dav.files["${root}photo.jpg"])
    }

    @Test fun uploadOverwriteWithStaleEtagIsRejected() {
        dav.files["${root}doc.txt"] = byteArrayOf(1)
        val stale = dav.etag("${root}doc.txt")!!
        dav.files["${root}doc.txt"] = byteArrayOf(2) // somebody else saved in between
        try {
            api.upload(root, "doc.txt", UploadSource.ofBytes(byteArrayOf(3)), UploadOptions(CollisionPolicy.OVERWRITE, expectedEtag = stale))
            fail()
        } catch (e: ConflictException) { }
        assertArrayEquals(byteArrayOf(2), dav.files["${root}doc.txt"])
    }

    @Test fun uploadOverwriteWithCurrentEtagSucceeds() {
        dav.files["${root}doc.txt"] = byteArrayOf(1)
        api.upload(root, "doc.txt", UploadSource.ofBytes(byteArrayOf(3)), UploadOptions(CollisionPolicy.OVERWRITE, expectedEtag = dav.etag("${root}doc.txt")))
        assertArrayEquals(byteArrayOf(3), dav.files["${root}doc.txt"])
    }

    @Test fun uploadCreatesMissingParentFolderOnConflict() {
        api.upload("${root}InstantUpload/2025/", "img.jpg", UploadSource.ofBytes(byteArrayOf(5)), UploadOptions())
        assertTrue("${root}InstantUpload/2025/" in dav.dirs)
        assertArrayEquals(byteArrayOf(5), dav.files["${root}InstantUpload/2025/img.jpg"])
    }

    @Test fun uploadPassesFileMtime() {
        val file = tmp.newFile("m.txt").apply { writeText("x"); setLastModified(1_700_000_000_000L) }
        api.upload(root, "m.txt", UploadSource.ofFile(file), UploadOptions())
        assertEquals("1700000000", dav.mtimes["${root}m.txt"])
    }

    @Test fun uploadRejectsPathTraversalNames() {
        try { api.upload(root, "../evil", UploadSource.ofBytes(byteArrayOf(1)), UploadOptions()); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun uploadDoesNotLoseTheFileWhenServerTruncates() {
        // 400 "expected filesize" = connection dropped mid-body: transient, so the queue retries.
        val e = HttpStatusException(400, davMessage = "expected filesize 100 got 10")
        assertTrue(e.isTransientFailure())
    }

    // ── chunked upload ──────────────────────────────────────────────────────────────────────

    private fun chunkOptions(chunk: Long, policy: CollisionPolicy = CollisionPolicy.FAIL) = UploadOptions(policy, chunkSize = chunk)

    @Test fun chunkedUploadAssemblesTheExactBytes() {
        val payload = bytes(10_500)
        val seen = mutableListOf<Long>()
        val result = api.upload(root, "big.bin", UploadSource.ofBytes(payload),
            UploadOptions(CollisionPolicy.FAIL, chunkSize = 4_000, progress = { done, _ -> seen.add(done) }))
        assertArrayEquals(payload, dav.files["${root}big.bin"])
        assertEquals(payload.size.toLong(), seen.last())
        assertNotNull(result.etag)
        assertTrue("upload folder must be cleaned up by the assembly", dav.files.keys.none { it.startsWith("/remote.php/dav/uploads/") })
        assertEquals(3, dav.log.count { it.startsWith("PUT /remote.php/dav/uploads/") })
    }

    @Test fun chunkedUploadResumesWithoutResendingStoredChunks() {
        val payload = bytes(10_500, seed = 7)
        val source = UploadSource.ofBytes(payload)
        val id = api.uploadIdFor("${root}big.bin", source)
        val dir = "/remote.php/dav/uploads/alice/$id/"
        dav.dirs.add(dir)
        dav.files[dir + "000001"] = payload.copyOfRange(0, 4_000)
        dav.files[dir + "000002"] = payload.copyOfRange(4_000, 8_000)

        api.upload(root, "big.bin", source, chunkOptions(4_000))

        assertArrayEquals(payload, dav.files["${root}big.bin"])
        val puts = dav.log.filter { it.startsWith("PUT ") }
        assertEquals("only the missing chunk is sent: $puts", 1, puts.size)
        assertTrue(puts.single().endsWith("000003"))
    }

    @Test fun chunkedUploadDiscardsInconsistentLeftovers() {
        val payload = bytes(9_000, seed = 3)
        val source = UploadSource.ofBytes(payload)
        val id = api.uploadIdFor("${root}big.bin", source)
        val dir = "/remote.php/dav/uploads/alice/$id/"
        dav.dirs.add(dir)
        dav.files[dir + "000001"] = ByteArray(1234) // written with another chunk size
        api.upload(root, "big.bin", source, chunkOptions(4_000))
        assertArrayEquals(payload, dav.files["${root}big.bin"])
    }

    @Test fun chunkedUploadTreatsLostMoveAnswerAsSuccess() {
        val payload = bytes(9_000, seed = 5)
        var moves = 0
        dav.interceptor = { request, path ->
            if (request.method == "MOVE" && path.endsWith("/.file")) {
                moves++
                // The server assembled the file but the answer never arrived; a retry would see no ".file".
                null
            } else null
        }
        api.upload(root, "big.bin", UploadSource.ofBytes(payload), chunkOptions(4_000))
        // Simulate a re-run after the assembly succeeded: the upload folder is gone, the file is complete.
        dav.interceptor = { request, path -> if (request.method == "MOVE" && path.endsWith("/.file")) MockResponse().setResponseCode(404) else null }
        val again = api.upload(root, "big.bin", UploadSource.ofBytes(payload), UploadOptions(CollisionPolicy.OVERWRITE, chunkSize = 4_000))
        assertEquals("${root}big.bin", again.path)
        assertEquals(1, moves)
    }

    @Test fun chunkedUploadRenamesOnCollision() {
        dav.files["${root}big.bin"] = byteArrayOf(1)
        val result = api.upload(root, "big.bin", UploadSource.ofBytes(bytes(9_000)), chunkOptions(4_000, CollisionPolicy.RENAME))
        assertEquals("${root}big (2).bin", result.path)
        assertArrayEquals(byteArrayOf(1), dav.files["${root}big.bin"])
    }

    @Test fun chunkSizeSelectionMatchesTheOfficialClient() {
        assertEquals(40_960_000L, FileApi.chunkSizeFor(true, null))
        assertEquals(10_240_000L, FileApi.chunkSizeFor(false, null))
        assertEquals(10_240_000L, FileApi.chunkSizeFor(true, 1_000_000L))
        assertEquals(104_857_600L, FileApi.chunkSizeFor(false, 104_857_600L))
    }

    // ── download ────────────────────────────────────────────────────────────────────────────

    @Test fun downloadWritesFileAtomically() {
        val payload = bytes(300_000)
        dav.files["${root}d.bin"] = payload
        val target = tmp.newFolder().resolve("d.bin")
        val result = api.download("${root}d.bin", target, expectedSize = payload.size.toLong())
        assertArrayEquals(payload, target.readBytes())
        assertEquals(payload.size.toLong(), result.bytes)
        assertFalse(target.resolveSibling("d.bin.part").exists())
    }

    @Test fun downloadResumesAfterADroppedConnection() {
        val payload = bytes(400_000, seed = 9)
        dav.files["${root}d.bin"] = payload
        var first = true
        dav.interceptor = { request, path ->
            if (first && request.method == "GET" && path.endsWith("d.bin")) {
                first = false
                MockResponse().setResponseCode(200).setHeader("ETag", "\"${dav.etag(path)}\"")
                    .setBody(Buffer().write(payload)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else null
        }
        val target = tmp.newFolder().resolve("d.bin")
        api.download("${root}d.bin", target, expectedSize = payload.size.toLong())
        assertArrayEquals("resumed download must be byte-identical", payload, target.readBytes())
        val ranges = dav.log.filter { it.startsWith("RANGE ") }
        assertEquals("exactly one resumed request: ${dav.log}", 1, ranges.size)
        assertTrue("resumed with the validator of the first response: $ranges", ranges.single().contains("ifRange=\""))
    }

    @Test fun downloadRestartsWhenTheFileChangedOnTheServer() {
        val v1 = bytes(100_000, seed = 1)
        val v2 = bytes(120_000, seed = 2)
        val dir = tmp.newFolder()
        val target = dir.resolve("d.bin")
        // Leftover from an earlier attempt at v1.
        dir.resolve("d.bin.part").writeBytes(v1.copyOf(40_000))
        dir.resolve("d.bin.part.etag").writeText("stale-etag")
        dav.files["${root}d.bin"] = v2
        api.download("${root}d.bin", target)
        assertArrayEquals(v2, target.readBytes())
    }

    @Test fun downloadFailsCleanlyOnMissingFile() {
        try { api.download("${root}missing.bin", tmp.newFolder().resolve("x")); fail() } catch (e: HttpStatusException) {
            assertEquals(404, e.code)
        }
    }

    @Test fun readBytesEnforcesTheMemoryCap() {
        dav.files["${root}huge.bin"] = bytes(5_000)
        try { api.readBytes("${root}huge.bin", 1_000); fail() } catch (e: LocalIoException) { }
        assertEquals(5_000, api.readBytes("${root}huge.bin", 10_000).size)
    }

    @Test fun parseEtagHandlesGzipAndWeakValidators() {
        assertEquals("abc", FileApi.parseEtag("\"abc\""))
        assertEquals("abc", FileApi.parseEtag("\"abc-gzip\""))
        assertEquals("abc", FileApi.parseEtag("W/\"abc\""))
        assertNull(FileApi.parseEtag(""))
        assertNull(FileApi.parseEtag(null))
    }
}

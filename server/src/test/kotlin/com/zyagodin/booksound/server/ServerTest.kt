package com.zyagodin.booksound.server

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.ChangeSetDto
import com.zyagodin.booksound.api.DeviceKind
import com.zyagodin.booksound.api.LibraryEntryDto
import com.zyagodin.booksound.api.LoginRequest
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.api.MetadataPatch
import com.zyagodin.booksound.api.PlaybackDto
import com.zyagodin.booksound.api.PullResponse
import com.zyagodin.booksound.api.PushRequest
import com.zyagodin.booksound.api.PushResponse
import com.zyagodin.booksound.api.RefreshRequest
import com.zyagodin.booksound.api.SessionDto
import com.zyagodin.booksound.api.SetupRequest
import com.zyagodin.booksound.api.StampDto
import com.zyagodin.booksound.api.StatusDto
import com.zyagodin.booksound.api.TokenResponse
import com.zyagodin.booksound.api.UploadCreateRequest
import com.zyagodin.booksound.api.UploadDto
import com.zyagodin.booksound.api.UploadKind
import com.zyagodin.booksound.api.UploadResultDto
import com.zyagodin.booksound.api.UserCreateRequest
import com.zyagodin.booksound.api.WebLoginRequest
import com.zyagodin.booksound.core.TestMedia
import com.zyagodin.booksound.core.io.ByteArraySource
import com.zyagodin.booksound.core.io.StreamSink
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagWriter
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ServerTest {
    private lateinit var dataDir: File
    private lateinit var ctx: ServerContext

    @Before
    fun setUp() {
        dataDir = Files.createTempDirectory("booksound-test").toFile()
        val config = ServerConfig.fromEnvironment(mapOf("BOOKSOUND_DATA" to dataDir.absolutePath, "BOOKSOUND_TORRENTS" to "off"))
        config.createDirectories()
        ctx = ServerContext(config)
        ctx.auth.init()
        ctx.imports.start()
    }

    @After
    fun tearDown() {
        ctx.close()
        dataDir.deleteRecursively()
    }

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { booksound(ctx) }
        block()
    }

    private suspend inline fun <reified T> HttpResponse.body(serializer: KSerializer<T>): T = ApiJson.decodeFromString(serializer, bodyAsText())

    private fun <T> HttpRequestBuilder.json(serializer: KSerializer<T>, value: T) {
        contentType(ContentType.Application.Json)
        setBody(ApiJson.encodeToString(serializer, value))
    }

    private suspend fun HttpClient.setupAndLogin(): TokenResponse {
        val token = ctx.auth.setupToken!!
        val setup = post(ApiPaths.SETUP) { json(SetupRequest.serializer(), SetupRequest(token, "admin", "correct horse")) }
        assertEquals(HttpStatusCode.Created, setup.status)
        val login = post(ApiPaths.LOGIN) { json(LoginRequest.serializer(), LoginRequest("admin", "correct horse", "Pixel", DeviceKind.PHONE)) }
        assertEquals(HttpStatusCode.OK, login.status)
        return login.body(TokenResponse.serializer())
    }

    private fun HttpRequestBuilder.bearer(tokens: TokenResponse) = header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")

    /** A small BookSound M4B, as the phone writes them. */
    private fun bookFile(id: String, title: String): ByteArray {
        val out = ByteArrayOutputStream()
        val chapters = listOf(Chapter(0, "One", 0, 400), Chapter(1, "Two", 400, 928))
        Mp4TagWriter.write(ByteArraySource(TestMedia.aacMp4()), StreamSink(out), Mp4TagSpec(id, BookMetadata(title, author = "Author"), chapters, null))
        return out.toByteArray()
    }

    @Test
    fun `everything but the status needs a login`() = server {
        val status = client.get(ApiPaths.STATUS).body(StatusDto.serializer())
        assertTrue(status.setupRequired)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.BOOKS).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.BOOKS) { header(HttpHeaders.Authorization, "Bearer nonsense") }.status)
        val wrongSetup = client.post(ApiPaths.SETUP) { json(SetupRequest.serializer(), SetupRequest("wrong", "admin", "correct horse")) }
        assertEquals(HttpStatusCode.Unauthorized, wrongSetup.status)
    }

    @Test
    fun `login, refresh and revoke`() = server {
        val tokens = client.setupAndLogin()
        assertEquals(HttpStatusCode.OK, client.get(ApiPaths.BOOKS) { bearer(tokens) }.status)

        val refreshed = client.post(ApiPaths.REFRESH) { json(RefreshRequest.serializer(), RefreshRequest(tokens.refreshToken)) }.body(TokenResponse.serializer())
        // The old access token ends with the refresh.
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.BOOKS) { bearer(tokens) }.status)
        assertEquals(HttpStatusCode.OK, client.get(ApiPaths.BOOKS) { bearer(refreshed) }.status)

        // Retrying the replaced token right away (the answer got lost) gives a new pair; the unseen one stops working.
        val retried = client.post(ApiPaths.REFRESH) { json(RefreshRequest.serializer(), RefreshRequest(tokens.refreshToken)) }
        assertEquals(HttpStatusCode.OK, retried.status)
        val again = retried.body(TokenResponse.serializer())
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.BOOKS) { bearer(refreshed) }.status)
        assertEquals(HttpStatusCode.OK, client.get(ApiPaths.BOOKS) { bearer(again) }.status)
    }

    @Test
    fun `wrong passwords lock the account`() = server {
        client.setupAndLogin()
        repeat(5) {
            assertEquals(HttpStatusCode.Unauthorized, client.post(ApiPaths.LOGIN) { json(LoginRequest.serializer(), LoginRequest("admin", "wrong", "x", DeviceKind.PHONE)) }.status)
        }
        val locked = client.post(ApiPaths.LOGIN) { json(LoginRequest.serializer(), LoginRequest("admin", "correct horse", "x", DeviceKind.PHONE)) }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
    }

    @Test
    fun `web session needs the csrf header to change things`() = server {
        client.setupAndLogin()
        val login = client.post(ApiPaths.WEB_LOGIN) { json(WebLoginRequest.serializer(), WebLoginRequest("admin", "correct horse")) }
        val cookie = login.headers.getAll(HttpHeaders.SetCookie)!!.first { it.startsWith("bs_session=") }
        assertTrue(cookie.contains("HttpOnly"))
        val session = cookie.substringBefore(';')
        assertEquals(HttpStatusCode.OK, client.get(ApiPaths.ME) { header(HttpHeaders.Cookie, session) }.status)
        val push = PushRequest(ChangeSetDto())
        assertEquals(HttpStatusCode.Unauthorized, client.post(ApiPaths.SYNC_PUSH) { header(HttpHeaders.Cookie, session); json(PushRequest.serializer(), push) }.status)
        assertEquals(HttpStatusCode.OK, client.post(ApiPaths.SYNC_PUSH) { header(HttpHeaders.Cookie, session); header(ApiPaths.CSRF_HEADER, "1"); json(PushRequest.serializer(), push) }.status)
    }

    @Test
    fun `uploaded book is served with ranges, synced per user and retagged`() = server {
        val tokens = client.setupAndLogin()
        val id = "11111111-2222-3333-4444-555555555555"
        val bytes = bookFile(id, "My Book")

        val upload = client.post(ApiPaths.UPLOADS) { bearer(tokens); json(UploadCreateRequest.serializer(), UploadCreateRequest("book.m4b", bytes.size.toLong(), UploadKind.BOOK)) }
            .body(UploadDto.serializer())
        val half = bytes.size / 2
        val first = client.put(ApiPaths.upload(upload.id)) { bearer(tokens); header(HttpHeaders.ContentRange, "bytes 0-${half - 1}/${bytes.size}"); setBody(bytes.copyOfRange(0, half)) }
        assertEquals(HttpStatusCode.OK, first.status)
        // A chunk that doesn't continue where the server is gets told where to resume.
        val wrong = client.put(ApiPaths.upload(upload.id)) { bearer(tokens); header(HttpHeaders.ContentRange, "bytes 0-9/${bytes.size}"); setBody(bytes.copyOfRange(0, 10)) }
        assertEquals(HttpStatusCode.Conflict, wrong.status)
        assertEquals(half.toString(), wrong.headers["Upload-Offset"])
        client.put(ApiPaths.upload(upload.id)) { bearer(tokens); header(HttpHeaders.ContentRange, "bytes $half-${bytes.size - 1}/${bytes.size}"); setBody(bytes.copyOfRange(half, bytes.size)) }
        val result = client.post(ApiPaths.uploadComplete(upload.id)) { bearer(tokens) }.body(UploadResultDto.serializer())
        val book = result.book!!
        assertEquals(id, book.id)
        assertEquals("Author/My Book.m4b", book.relativePath)
        assertEquals(2, book.chapters.size)

        val whole = client.get(ApiPaths.bookFile(id)) { bearer(tokens) }
        assertArrayEquals(bytes, whole.bodyAsBytes())
        val part = client.get(ApiPaths.bookFile(id)) { bearer(tokens); header(HttpHeaders.Range, "bytes=10-19") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertArrayEquals(bytes.copyOfRange(10, 20), part.bodyAsBytes())

        // Positions belong to a user; a second user starts from nothing.
        val playback = PlaybackDto(id, 300, 1.2f, false, 1_000, StampDto(1, 1_000, "phone"))
        val pushed = client.post(ApiPaths.SYNC_PUSH) { bearer(tokens); json(PushRequest.serializer(), PushRequest(ChangeSetDto(playback = listOf(playback)))) }
            .body(PushResponse.serializer())
        assertTrue("playback:$id" in pushed.accepted)
        val pull = client.get(ApiPaths.SYNC_PULL + "?since=0") { bearer(tokens) }.body(PullResponse.serializer())
        assertEquals(listOf(id), pull.changes.books.map { it.id })
        assertEquals(300L, pull.changes.playback.single().positionMs)
        val again = client.get(ApiPaths.SYNC_PULL + "?since=${pull.cursor}") { bearer(tokens) }.body(PullResponse.serializer())
        assertTrue(again.changes.books.isEmpty() && again.changes.playback.isEmpty())

        client.post(ApiPaths.ADMIN_USERS) { bearer(tokens); json(UserCreateRequest.serializer(), UserCreateRequest("guest", "guest password")) }
        val guest = client.post(ApiPaths.LOGIN) { json(LoginRequest.serializer(), LoginRequest("guest", "guest password", "Web", DeviceKind.PHONE)) }.body(TokenResponse.serializer())
        val guestPull = client.get(ApiPaths.SYNC_PULL + "?since=0") { bearer(guest) }.body(PullResponse.serializer())
        assertEquals(1, guestPull.changes.books.size)
        assertTrue(guestPull.changes.playback.isEmpty())
        assertEquals(HttpStatusCode.Forbidden, client.get(ApiPaths.ADMIN_USERS) { bearer(guest) }.status)

        // Someone else's book in the shared library can't be replaced by an upload.
        val other = bookFile(id, "Hijacked")
        val guestUpload = client.post(ApiPaths.UPLOADS) { bearer(guest); json(UploadCreateRequest.serializer(), UploadCreateRequest("x.m4b", other.size.toLong(), UploadKind.BOOK)) }
            .body(UploadDto.serializer())
        client.put(ApiPaths.upload(guestUpload.id)) { bearer(guest); header(HttpHeaders.ContentRange, "bytes 0-${other.size - 1}/${other.size}"); setBody(other) }
        assertEquals(HttpStatusCode.Forbidden, client.post(ApiPaths.uploadComplete(guestUpload.id)) { bearer(guest) }.status)

        // A metadata edit is written into the file, which moves to its new place.
        val entry = client.get(ApiPaths.book(id)) { bearer(tokens) }.body(LibraryEntryDto.serializer())
        val patched = client.patch(ApiPaths.book(id)) {
            bearer(tokens)
            json(MetadataPatch.serializer(), MetadataPatch(MetadataDto("Renamed", author = "Writer", series = "Saga", seriesIndex = "2"), entry.book.stamp.revision))
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val file = File(dataDir, "library/Writer/Saga/02 - Renamed.m4b")
        assertTrue(file.isFile)
        assertTrue(!File(dataDir, "library/Author").exists())
        val parsed = AudioProbe.probe(ByteArraySource(file.readBytes()), file.name)
        assertEquals(id, parsed.bookId)
        assertEquals("Renamed", parsed.tags.title)
        assertNotNull(parsed.stream)
    }

    @Test
    fun `books copied into the folder are found by a scan`() = server {
        val tokens = client.setupAndLogin()
        val dir = File(dataDir, "library/Someone/Series").apply { mkdirs() }
        File(dir, "Found.m4b").writeBytes(bookFile("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "Found"))
        val result = ctx.library.scan()
        assertEquals(1, result.added)
        val books = client.get(ApiPaths.BOOKS) { bearer(tokens) }.bodyAsText()
        assertTrue(books.contains("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        // Moved by hand: the same book at its new path.
        File(dir, "Found.m4b").renameTo(File(dataDir, "library/Moved.m4b"))
        assertEquals(1, ctx.library.scan().relinked)
        assertEquals("Moved.m4b", ctx.library.repo.book("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")!!.relativePath)
    }
}

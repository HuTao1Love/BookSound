package com.zyagodin.booksound.api

import com.zyagodin.booksound.api.ts.generateTs
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.PlaybackRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ApiTest {

    @Test
    fun `nulls are left out and unknown fields ignored`() {
        val json = ApiJson.encodeToString(MetadataDto.serializer(), MetadataDto("T"))
        assertFalse(json.contains("author"))
        val back = ApiJson.decodeFromString(MetadataDto.serializer(), """{"title":"T","future":1}""")
        assertEquals(MetadataDto("T"), back)
    }

    @Test
    fun `playback round trip keeps the record but not the dirty flag`() {
        val record = PlaybackRecord(BookId("b"), 1_000, 1.25f, false, 5, SyncStamp(3, 5, DeviceId("phone"), dirty = true))
        val back = ApiJson.decodeFromString(PlaybackDto.serializer(), ApiJson.encodeToString(PlaybackDto.serializer(), record.toDto())).toModel()
        assertEquals(record.copy(stamp = record.stamp.copy(dirty = false)), back)
    }

    @Test
    fun `web types are up to date`() {
        val committed = File("../web/src/api/types.gen.ts").readText().replace("\r\n", "\n")
        assertEquals("Run ./gradlew :api:generateTs", generateTs(), committed)
    }
}

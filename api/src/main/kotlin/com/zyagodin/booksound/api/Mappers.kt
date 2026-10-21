package com.zyagodin.booksound.api

import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.BookRecord
import com.zyagodin.booksound.core.sync.ChapterListRecord
import com.zyagodin.booksound.core.sync.FileDescriptorRecord
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.sync.PreferencesRecord

/* Conversions between the API's wire types and :core's records. */

fun BookMetadata.toDto() = MetadataDto(title, author, narrator, series, seriesIndex, year, genre, description, language)

fun MetadataDto.toModel() = BookMetadata(title, author, narrator, series, seriesIndex, year, genre, description, language)

fun Chapter.toDto() = ChapterDto(index, title, startMs, endMs)

fun ChapterDto.toModel() = Chapter(index, title, startMs, endMs)

fun SyncStamp.toDto() = StampDto(revision, updatedAt, updatedBy.value, deleted)

/** A stamp received from the other side is never dirty here. */
fun StampDto.toModel() = SyncStamp(revision, updatedAt, DeviceId(updatedBy), deleted, dirty = false)

fun PlaybackRecord.toDto() = PlaybackDto(bookId.value, positionMs, speed, finished, lastPlayedAt, stamp.toDto())

fun PlaybackDto.toModel() = PlaybackRecord(BookId(bookId), positionMs, speed, finished, lastPlayedAt, stamp.toModel())

fun BookRecord.toDto(chapters: List<Chapter>) = BookDto(
    id = id.value,
    metadata = metadata.toDto(),
    durationMs = durationMs,
    sizeBytes = file.sizeBytes,
    sha256 = file.sha256,
    fileRevision = file.revision,
    coverSha256 = coverSha256,
    addedAt = addedAt,
    stamp = stamp.toDto(),
    chapters = chapters.map { it.toDto() },
)

fun BookDto.toRecord() = BookRecord(
    id = BookId(id),
    metadata = metadata.toModel(),
    durationMs = durationMs,
    file = FileDescriptorRecord(sizeBytes, sha256, fileRevision),
    coverSha256 = coverSha256,
    addedAt = addedAt,
    stamp = stamp.toModel(),
)

fun BookDto.chapterRecord() = ChapterListRecord(BookId(id), chapters.map { it.toModel() }, stamp.toModel())

fun PreferencesRecord.toDto(voicePresets: Map<String, String> = emptyMap(), watchProfileId: String? = null) =
    PreferencesDto(defaultSpeed, sleepTimerMinutes, skipBackSeconds, skipForwardSeconds, voicePresets, watchProfileId, stamp.toDto())

fun PreferencesDto.toRecord() = PreferencesRecord(defaultSpeed, sleepTimerMinutes, skipBackSeconds, skipForwardSeconds, stamp.toModel())

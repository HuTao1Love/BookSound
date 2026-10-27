package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.io.ByteArraySource
import com.zyagodin.booksound.core.io.StreamSink
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagWriter
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.naming.LibraryLayout
import java.io.File
import java.util.UUID

/** A book of the demo library: public-domain titles, invented narrators, covers drawn by tools/screenshots/covers.py. */
private class DemoBook(
    val cover: String,
    val metadata: BookMetadata,
    val hours: Double,
    val chapters: List<String>,
)

private fun numbered(count: Int) = (1..count).map { "Chapter $it" }

private const val DOYLE = "Arthur Conan Doyle"
private const val HOLMES = "Sherlock Holmes"

private val books = listOf(
    DemoBook(
        "pride",
        BookMetadata(
            "Pride and Prejudice", "Jane Austen", "Clara Whitfield", year = "1813", genre = "Classics",
            description = "Elizabeth Bennet, the second of five daughters, meets the proud Mr Darcy at a country ball, and neither " +
                "of them likes what they see. A comedy of manners about first impressions and second thoughts.",
        ),
        11.6, numbered(61),
    ),
    DemoBook(
        "holmes1",
        BookMetadata(
            "A Study in Scarlet", DOYLE, "Edmund Gray", HOLMES, "1", "1887", "Mystery",
            "Dr Watson, back from Afghanistan, takes rooms at 221B Baker Street with a strange young man who reads a " +
                "stranger's life from his boots. Their first case: a body in an empty house and a word written in blood.",
        ),
        4.8, numbered(14),
    ),
    DemoBook(
        "holmes2",
        BookMetadata(
            "The Sign of the Four", DOYLE, "Edmund Gray", HOLMES, "2", "1890", "Mystery",
            "Every year Miss Morstan receives a pearl from an unknown friend. This year comes a letter, a meeting at " +
                "the Lyceum Theatre and a treasure brought back from India.",
        ),
        4.5, numbered(12),
    ),
    DemoBook(
        "holmes3",
        BookMetadata(
            "The Adventures of Sherlock Holmes", DOYLE, "Edmund Gray", HOLMES, "3", "1892", "Mystery",
            "Twelve cases from Baker Street, from a king's indiscretion to a band of red-headed men.",
        ),
        10.3,
        listOf(
            "A Scandal in Bohemia", "The Red-Headed League", "A Case of Identity", "The Boscombe Valley Mystery",
            "The Five Orange Pips", "The Man with the Twisted Lip", "The Adventure of the Blue Carbuncle",
            "The Adventure of the Speckled Band", "The Adventure of the Engineer's Thumb",
            "The Adventure of the Noble Bachelor", "The Adventure of the Beryl Coronet", "The Adventure of the Copper Beeches",
        ),
    ),
    DemoBook(
        "holmes4",
        BookMetadata("The Memoirs of Sherlock Holmes", DOYLE, "Edmund Gray", HOLMES, "4", "1894", "Mystery", "Eleven more cases, ending at the Reichenbach Falls."),
        9.2, numbered(11),
    ),
    DemoBook(
        "holmes5",
        BookMetadata(
            "The Hound of the Baskervilles", DOYLE, "Edmund Gray", HOLMES, "5", "1902", "Mystery",
            "Sir Charles Baskerville is found dead on the moor, a look of horror on his face and the footprints of a " +
                "gigantic hound nearby.",
        ),
        6.3, numbered(15),
    ),
    DemoBook(
        "time",
        BookMetadata("The Time Machine", "H. G. Wells", "Thomas Reed", year = "1895", genre = "Science fiction", description = "A Victorian inventor travels to the year 802,701 and finds the future divided between the gentle Eloi and the Morlocks below."),
        3.4, numbered(12),
    ),
    DemoBook(
        "frankenstein",
        BookMetadata("Frankenstein", "Mary Shelley", "Anna Morley", year = "1818", genre = "Horror", description = "Victor Frankenstein gives life to a creature and flees from it. The creature does not forget."),
        8.5, numbered(24),
    ),
    DemoBook(
        "dracula",
        BookMetadata("Dracula", "Bram Stoker", "Thomas Reed", year = "1897", genre = "Horror", description = "A young solicitor travels to Transylvania to help a count buy a house in England."),
        15.5, numbered(27),
    ),
    DemoBook(
        "moby",
        BookMetadata("Moby-Dick", "Herman Melville", "Samuel Price", year = "1851", genre = "Adventure", description = "Call me Ishmael. Captain Ahab hunts the white whale that took his leg."),
        21.4, numbered(40),
    ),
    DemoBook(
        "alice",
        BookMetadata("Alice's Adventures in Wonderland", "Lewis Carroll", "Clara Whitfield", year = "1865", genre = "Fantasy", description = "Alice follows a white rabbit down a hole and into a world where nothing is what it seems."),
        2.9, numbered(12),
    ),
    DemoBook(
        "dorian",
        BookMetadata("The Picture of Dorian Gray", "Oscar Wilde", "Samuel Price", year = "1890", genre = "Classics", description = "A beautiful young man wishes that his portrait would grow old instead of him."),
        8.0, numbered(20),
    ),
    DemoBook(
        "leagues",
        BookMetadata("Twenty Thousand Leagues Under the Seas", "Jules Verne", "Anna Morley", year = "1870", genre = "Adventure", description = "Professor Aronnax sets out to hunt a sea monster and becomes a guest of Captain Nemo aboard the Nautilus."),
        13.0, numbered(47),
    ),
)

/**
 * Writes the demo library of the README's screenshots into OUT_DIR in the library layout
 * (`Author/Series/NN - Title.m4b`): silent AAC of each book's real length (a few hundred KB an hour), tagged,
 * chaptered and with its cover by [Mp4TagWriter], so the app reads them like books it imported itself.
 *
 * usage: OUT_DIR COVERS_DIR [ffmpeg]
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val covers = File(args[1])
    val ffmpeg = args.getOrElse(2) { "ffmpeg" }
    val work = File(out, ".work").apply { mkdirs() }
    val hour = File(work, "hour.m4a")
    if (!hour.isFile) run(ffmpeg, "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono", "-t", "3600", "-c:a", "aac", "-b:a", "8k", hour.path)
    for (book in books) {
        val durationMs = (book.hours * 3_600_000).toLong()
        val audio = File(work, "${book.cover}.m4a")
        run(ffmpeg, "-stream_loop", "-1", "-i", hour.path, "-t", (durationMs / 1000.0).toString(), "-c", "copy", audio.path)
        val bytes = File(covers, "${book.cover}.jpg").readBytes()
        val spec = Mp4TagSpec(
            bookId = UUID.nameUUIDFromBytes(book.metadata.title.toByteArray()).toString(),
            metadata = book.metadata,
            chapters = chapters(book.chapters, durationMs),
            cover = EmbeddedPicture(bytes, "image/jpeg"),
        )
        val path = LibraryLayout.pathFor(book.metadata)
        val target = File(out, (path.directories + path.fileName).joinToString("/")).apply { parentFile.mkdirs() }
        target.outputStream().buffered().use { Mp4TagWriter.write(ByteArraySource(audio.readBytes()), StreamSink(it), spec) }
        audio.delete()
        println("${target.relativeTo(out)}  ${book.chapters.size} chapters, ${book.hours} h")
    }
    work.deleteRecursively()
}

/** Chapters of slightly different lengths (as real ones are) filling [durationMs]. */
private fun chapters(titles: List<String>, durationMs: Long): List<Chapter> {
    val weights = titles.indices.map { 1.0 + 0.35 * kotlin.math.sin(it * 2.7) }
    val total = weights.sum()
    var start = 0L
    return titles.mapIndexed { i, title ->
        val end = if (i == titles.lastIndex) durationMs else start + (durationMs * weights[i] / total).toLong()
        Chapter(i, title, start, end).also { start = end }
    }
}

private fun run(vararg command: String) {
    val process = ProcessBuilder(command[0], "-hide_banner", "-loglevel", "error", "-y", *command.drop(1).toTypedArray())
        .redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
}

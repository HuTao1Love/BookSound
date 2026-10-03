package com.zyagodin.booksound.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.zyagodin.booksound.core.model.Chapter
import java.util.IdentityHashMap

/**
 * Player wrapper that turns "next/previous" (notification, headset, car, watch) into chapter
 * navigation inside the single m4b file, uses the user's skip intervals, and rewinds a little
 * when resuming after a pause ("smart rewind").
 */
@OptIn(UnstableApi::class)
class AudiobookPlayer(
    player: Player,
    private val skipBackMs: () -> Long,
    private val skipForwardMs: () -> Long,
    private val smartRewindEnabled: () -> Boolean,
) : ForwardingPlayer(player) {

    @Volatile
    var chapters: List<Chapter> = emptyList()

    private var pausedAt: Long = 0L
    private val wrappers = IdentityHashMap<Player.Listener, Player.Listener>()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && playbackState == STATE_READY) pausedAt = SystemClock.elapsedRealtime()
            }
        })
    }

    // ---------------------------------------------------------------- commands

    override fun getAvailableCommands(): Player.Commands = augment(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        command in EXTRA_COMMANDS && super.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || super.isCommandAvailable(command)

    private fun augment(commands: Player.Commands): Player.Commands {
        if (!commands.contains(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return commands
        return commands.buildUpon().addAll(*EXTRA_COMMANDS.toIntArray()).build()
    }

    override fun addListener(listener: Player.Listener) {
        // Forward events with the augmented command set so sessions show chapter buttons.
        val wrapper = CommandAugmentingListener(listener, ::augment)
        synchronized(wrappers) { wrappers[listener] = wrapper }
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappers) { wrappers.remove(listener) } ?: listener
        super.removeListener(wrapper)
    }

    // ---------------------------------------------------------------- chapters

    fun currentChapterIndex(position: Long = currentPosition): Int {
        val list = chapters
        if (list.isEmpty()) return -1
        val i = list.indexOfLast { it.startMs <= position }
        return i.coerceAtLeast(0)
    }

    override fun seekToNext() = nextChapter()
    override fun seekToNextMediaItem() = nextChapter()
    override fun seekToPrevious() = previousChapter()
    override fun seekToPreviousMediaItem() = previousChapter()
    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    fun nextChapter() {
        val list = chapters
        val i = currentChapterIndex()
        if (i >= 0 && i + 1 < list.size) seekTo(list[i + 1].startMs)
    }

    /** Restarts the current chapter, or goes to the previous one when near its start. */
    fun previousChapter() {
        val list = chapters
        val position = currentPosition
        val i = currentChapterIndex(position)
        if (i < 0) {
            seekTo(0)
            return
        }
        val target = if (position - list[i].startMs > RESTART_THRESHOLD_MS || i == 0) list[i].startMs else list[i - 1].startMs
        seekTo(target)
    }

    // ---------------------------------------------------------------- skipping

    override fun getSeekBackIncrement(): Long = skipBackMs()
    override fun getSeekForwardIncrement(): Long = skipForwardMs()

    override fun seekBack() {
        seekTo((currentPosition - skipBackMs()).coerceAtLeast(0))
    }

    override fun seekForward() {
        val d = duration
        val target = currentPosition + skipForwardMs()
        seekTo(if (d > 0) target.coerceAtMost(d - 500).coerceAtLeast(0) else target)
    }

    // ---------------------------------------------------------------- smart rewind

    override fun play() {
        applySmartRewind()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && !this.playWhenReady) applySmartRewind()
        super.setPlayWhenReady(playWhenReady)
    }

    private fun applySmartRewind() {
        if (!smartRewindEnabled() || pausedAt == 0L || isPlaying) return
        val pausedFor = SystemClock.elapsedRealtime() - pausedAt
        pausedAt = 0L
        val rewind = when {
            pausedFor > 60 * 60_000L -> 30_000L
            pausedFor > 5 * 60_000L -> 10_000L
            pausedFor > 30_000L -> 3_000L
            else -> 0L
        }
        if (rewind > 0) {
            // Never rewind past the start of the current chapter.
            val chapterStart = chapters.getOrNull(currentChapterIndex())?.startMs ?: 0L
            seekTo(maxOf(chapterStart, currentPosition - rewind).coerceAtMost(currentPosition))
        }
    }

    companion object {
        private const val RESTART_THRESHOLD_MS = 3_000L
        private val EXTRA_COMMANDS = listOf(
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD,
        )
    }
}

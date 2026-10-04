package com.zyagodin.booksound.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi

/**
 * Forwards every [Player.Listener] callback explicitly. (Kotlin's `by` delegation does not forward
 * Java default methods, and all Listener methods are defaults, so a delegating wrapper would
 * silently drop events.) Only [onAvailableCommandsChanged] is altered.
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class CommandAugmentingListener(
    private val target: Player.Listener,
    private val augment: (Player.Commands) -> Player.Commands,
) : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) = target.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = target.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = target.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = target.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = target.onMediaMetadataChanged(mediaMetadata)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = target.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = target.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = target.onLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = target.onAvailableCommandsChanged(augment(availableCommands))
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = target.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = target.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = target.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = target.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(reason: Int) = target.onPlaybackSuppressionReasonChanged(reason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = target.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = target.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = target.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = target.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = target.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(reason: Int) = target.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        target.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = target.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = target.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = target.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = target.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = target.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = target.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = target.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = target.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = target.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = target.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = target.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = target.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = target.onRenderedFirstFrame()
    override fun onCues(cues: List<Cue>) = target.onCues(cues)
    override fun onCues(cueGroup: CueGroup) = target.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = target.onMetadata(metadata)
}

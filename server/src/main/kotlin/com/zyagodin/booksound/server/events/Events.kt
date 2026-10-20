package com.zyagodin.booksound.server.events

import com.zyagodin.booksound.api.EventDto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Progress of imports and torrents, and library changes, for the web app's live views. */
class Events {
    private val flow = MutableSharedFlow<EventDto>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<EventDto> = flow

    fun emit(event: EventDto) {
        flow.tryEmit(event)
    }
}

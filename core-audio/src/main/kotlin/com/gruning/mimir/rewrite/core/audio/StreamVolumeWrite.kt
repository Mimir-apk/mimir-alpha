package com.gruning.mimir.rewrite.core.audio

/**
 * When a drag of a volume slider reaches the system. A slider reports every pointer event (every few milliseconds), and many of them land on
 * the step the stream is already at; each `setStreamVolume` is a binder call into AudioService that also wakes every volume listener the
 * vendor has registered, so a write that changes nothing is a flood and not a no-op. The stream is written only when its step differs.
 */
object StreamVolumeWrite {
    /** [current] is the stream's present step (null when it could not be read: then write, as before); [target] is the step the slider asks for. */
    fun shouldWrite(current: Int?, target: Int): Boolean = current == null || current != target
}

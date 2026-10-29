package androidx.metrics.performance

/**
 * Data holders of androidx.metrics JankStats, which the desktop does not have. They carry no behavior: upstream's
 * RuntimeVisualGuardTracker reads `states` and `isJank`; a desktop frame source constructs them. Nothing here measures
 * frames, so without such a source the tracker simply receives no frames.
 */
open class FrameData(
    val frameStartNanos: Long,
    val frameDurationUiNanos: Long,
    val isJank: Boolean,
    val states: List<StateInfo>,
)

class StateInfo(val key: String, val value: String)

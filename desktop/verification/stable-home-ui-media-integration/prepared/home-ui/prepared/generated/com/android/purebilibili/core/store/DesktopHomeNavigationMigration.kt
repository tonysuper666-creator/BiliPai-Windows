package com.android.purebilibili.core.store
internal data class BottomTabMigrationResult(
    val order: List<String>,
    val visible: Set<String>,
    val markComplete: Boolean
)

internal fun resolveListenVideoBottomTabMigration(
    order: List<String>,
    visible: Set<String>,
    migrationComplete: Boolean
): BottomTabMigrationResult {
    if (migrationComplete) {
        return BottomTabMigrationResult(order, visible, markComplete = false)
    }
    if ("LISTEN_VIDEO" in order || "LISTEN_VIDEO" in visible || visible.size >= 5) {
        return BottomTabMigrationResult(order, visible, markComplete = true)
    }
    val insertionIndex = order.indexOf("PROFILE").takeIf { it >= 0 } ?: order.size
    val migratedOrder = order.toMutableList().apply {
        add(insertionIndex, "LISTEN_VIDEO")
    }
    return BottomTabMigrationResult(
        order = migratedOrder,
        visible = visible + "LISTEN_VIDEO",
        markComplete = true
    )
}

package com.bilipai.desktop.appearance

import com.bilipai.desktop.plugins.DesktopPreferenceKey
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Pure model of the windows_ux flags store (spec 5.2). No Compose, AWT or Windows dependency.
 *
 * The store is one JSON object namespace of the application preference file. A missing key means
 * "follow the default", so a default that flips later reaches every user who never chose; an explicit
 * choice is written as an explicit key and survives such a flip. Decoding never throws: any value that
 * is not understood is replaced by its default and its key is listed in invalidKeys.
 *
 * The numbers, key names, value lists and defaults here are mirrored by desktop/tools/ux/desktop-ux-contract.json.
 * The Python parity suite and DesktopWindowsUxModelTest check that they agree.
 */

internal const val WINDOWS_UX_NAMESPACE = "windows_ux"
internal const val WINDOWS_UX_DETAILS_PANEL_MIN_WIDTH_DP = 320
internal const val WINDOWS_UX_DETAILS_PANEL_MAX_WIDTH_DP = 560

/** Sanity limits for saved window bounds; clamping to a visible monitor is the window slice's job. */
internal const val WINDOWS_UX_WINDOW_MIN_EXTENT = 100
internal const val WINDOWS_UX_WINDOW_MAX_EXTENT = 32768
internal const val WINDOWS_UX_WINDOW_MAX_ORIGIN = 100000

// UX-FLAG-KEYS-BEGIN
internal const val UX_KEY_RAIL_MODE = "rail_mode"
internal const val UX_KEY_PLAYER_LAYOUT = "player_layout"
internal const val UX_KEY_HOME_LAYOUT = "home_layout"
internal const val UX_KEY_CARD_INTERACTIONS = "card_interactions"
internal const val UX_KEY_DETAILS_PANEL = "details_panel"
internal const val UX_KEY_DETAILS_PANEL_WIDTH = "details_panel_width"
internal const val UX_KEY_WHEEL_ACTION = "wheel_action"
internal const val UX_KEY_CLICK_ACTION = "click_action"
internal const val UX_KEY_DOUBLE_CLICK_ACTION = "double_click_action"
internal const val UX_KEY_WINDOW_X = "window_x"
internal const val UX_KEY_WINDOW_Y = "window_y"
internal const val UX_KEY_WINDOW_WIDTH = "window_width"
internal const val UX_KEY_WINDOW_HEIGHT = "window_height"
internal const val UX_KEY_WINDOW_MAXIMIZED = "window_maximized"
// UX-FLAG-KEYS-END

/** The layout flags that the reset command removes; saved window bounds are not a layout flag. */
internal val UX_LAYOUT_KEYS: List<String> = listOf(
    UX_KEY_RAIL_MODE, UX_KEY_PLAYER_LAYOUT, UX_KEY_HOME_LAYOUT, UX_KEY_CARD_INTERACTIONS, UX_KEY_DETAILS_PANEL,
    UX_KEY_DETAILS_PANEL_WIDTH, UX_KEY_WHEEL_ACTION, UX_KEY_CLICK_ACTION, UX_KEY_DOUBLE_CLICK_ACTION,
)

internal val UX_WINDOW_KEYS: List<String> = listOf(
    UX_KEY_WINDOW_X, UX_KEY_WINDOW_Y, UX_KEY_WINDOW_WIDTH, UX_KEY_WINDOW_HEIGHT, UX_KEY_WINDOW_MAXIMIZED,
)

internal val UX_ALL_KEYS: List<String> = UX_LAYOUT_KEYS + UX_WINDOW_KEYS

// UX-FLAG-ENUMS-BEGIN
internal enum class DesktopWindowsUxRailMode { AUTO, EXPANDED, COLLAPSED, CLASSIC_TEXT }
internal enum class DesktopWindowsUxPlayerLayout { LEGACY, DESKTOP }
internal enum class DesktopWindowsUxHomeLayout { DESKTOP, CLASSIC }
internal enum class DesktopWindowsUxCardInteractions { ON, OFF }
internal enum class DesktopWindowsUxDetailsPanel { AUTO, OPEN, CLOSED }
internal enum class DesktopWindowsUxWheelAction { VOLUME, SEEK, NONE }
internal enum class DesktopWindowsUxClickAction { PAUSE, NONE }
internal enum class DesktopWindowsUxDoubleClickAction { FULLSCREEN, NONE }
// UX-FLAG-ENUMS-END

/** AWT frame bounds in the units java.awt.Window reports, plus the maximised state. */
internal data class DesktopWindowsUxWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
) {
    fun isSane(): Boolean =
        width in WINDOWS_UX_WINDOW_MIN_EXTENT..WINDOWS_UX_WINDOW_MAX_EXTENT &&
            height in WINDOWS_UX_WINDOW_MIN_EXTENT..WINDOWS_UX_WINDOW_MAX_EXTENT &&
            x in -WINDOWS_UX_WINDOW_MAX_ORIGIN..WINDOWS_UX_WINDOW_MAX_ORIGIN &&
            y in -WINDOWS_UX_WINDOW_MAX_ORIGIN..WINDOWS_UX_WINDOW_MAX_ORIGIN
}

// UX-FLAG-DEFAULTS-BEGIN
internal data class DesktopWindowsUxSettings(
    val railMode: DesktopWindowsUxRailMode = DesktopWindowsUxRailMode.AUTO,
    val playerLayout: DesktopWindowsUxPlayerLayout = DesktopWindowsUxPlayerLayout.LEGACY,
    val homeLayout: DesktopWindowsUxHomeLayout = DesktopWindowsUxHomeLayout.DESKTOP,
    val cardInteractions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON,
    val detailsPanel: DesktopWindowsUxDetailsPanel = DesktopWindowsUxDetailsPanel.AUTO,
    val detailsPanelWidthDp: Int? = null,
    val wheelAction: DesktopWindowsUxWheelAction = DesktopWindowsUxWheelAction.VOLUME,
    val clickAction: DesktopWindowsUxClickAction = DesktopWindowsUxClickAction.PAUSE,
    val doubleClickAction: DesktopWindowsUxDoubleClickAction = DesktopWindowsUxDoubleClickAction.FULLSCREEN,
    val windowBounds: DesktopWindowsUxWindowBounds? = null,
    /** Storage keys whose stored value was not understood and was replaced by the default. */
    val invalidKeys: List<String> = emptyList(),
    /** User-facing problem text for a read problem, or null. */
    val error: String? = null,
)
// UX-FLAG-DEFAULTS-END

internal sealed interface DesktopWindowsUxCommand {
    data class SetRailMode(val value: DesktopWindowsUxRailMode) : DesktopWindowsUxCommand
    data class SetPlayerLayout(val value: DesktopWindowsUxPlayerLayout) : DesktopWindowsUxCommand
    data class SetHomeLayout(val value: DesktopWindowsUxHomeLayout) : DesktopWindowsUxCommand
    data class SetCardInteractions(val value: DesktopWindowsUxCardInteractions) : DesktopWindowsUxCommand
    data class SetDetailsPanel(val value: DesktopWindowsUxDetailsPanel) : DesktopWindowsUxCommand

    /** A null width removes the saved width; any other width is coerced into the allowed range. */
    data class SetDetailsPanelWidth(val widthDp: Int?) : DesktopWindowsUxCommand
    data class SetWheelAction(val value: DesktopWindowsUxWheelAction) : DesktopWindowsUxCommand
    data class SetClickAction(val value: DesktopWindowsUxClickAction) : DesktopWindowsUxCommand
    data class SetDoubleClickAction(val value: DesktopWindowsUxDoubleClickAction) : DesktopWindowsUxCommand

    /** Null forgets the saved bounds; bounds that are not sane are ignored. */
    data class SetWindowBounds(val bounds: DesktopWindowsUxWindowBounds?) : DesktopWindowsUxCommand

    /** Removes every layout flag so each follows its default again. Saved window bounds stay. */
    data object ResetLayout : DesktopWindowsUxCommand
}

/** Settings from raw stored values. Never throws. */
internal fun decodeDesktopWindowsUxValues(values: Map<String, JsonElement>): DesktopWindowsUxSettings {
    val invalid = linkedSetOf<String>()

    fun <E : Enum<E>> enumOf(key: String, entries: List<E>, default: E): E {
        val raw = values[key] ?: return default
        val primitive = raw as? JsonPrimitive
        val match = if (primitive != null && primitive.isString) entries.firstOrNull { it.name == primitive.content } else null
        if (match == null) invalid += key
        return match ?: default
    }

    fun intOf(key: String): Int? {
        val raw = values[key] ?: return null
        val primitive = raw as? JsonPrimitive
        val value = if (primitive != null && !primitive.isString) primitive.intOrNull else null
        if (value == null) invalid += key
        return value
    }

    val panelWidth = intOf(UX_KEY_DETAILS_PANEL_WIDTH)
    val panelWidthOk = panelWidth?.takeIf {
        it in WINDOWS_UX_DETAILS_PANEL_MIN_WIDTH_DP..WINDOWS_UX_DETAILS_PANEL_MAX_WIDTH_DP
    }
    if (panelWidth != null && panelWidthOk == null) invalid += UX_KEY_DETAILS_PANEL_WIDTH

    val x = intOf(UX_KEY_WINDOW_X)
    val y = intOf(UX_KEY_WINDOW_Y)
    val width = intOf(UX_KEY_WINDOW_WIDTH)
    val height = intOf(UX_KEY_WINDOW_HEIGHT)
    val maximizedRaw = values[UX_KEY_WINDOW_MAXIMIZED]
    val maximized = (maximizedRaw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    if (maximizedRaw != null && maximized == null) invalid += UX_KEY_WINDOW_MAXIMIZED
    val candidate = if (x != null && y != null && width != null && height != null) {
        DesktopWindowsUxWindowBounds(x, y, width, height, maximized ?: false)
    } else null
    val bounds = candidate?.takeIf { it.isSane() }
    if (bounds == null) {
        // The four numbers are one group: a partial or insane group is ignored as a whole.
        val groupKeys = listOf(UX_KEY_WINDOW_X, UX_KEY_WINDOW_Y, UX_KEY_WINDOW_WIDTH, UX_KEY_WINDOW_HEIGHT)
        if (groupKeys.any { it in values }) invalid.addAll(groupKeys.filter { it in values })
    }

    val railMode = enumOf(UX_KEY_RAIL_MODE, DesktopWindowsUxRailMode.entries, DesktopWindowsUxRailMode.AUTO)
    val playerLayout = enumOf(UX_KEY_PLAYER_LAYOUT, DesktopWindowsUxPlayerLayout.entries, DesktopWindowsUxPlayerLayout.LEGACY)
    val homeLayout = enumOf(UX_KEY_HOME_LAYOUT, DesktopWindowsUxHomeLayout.entries, DesktopWindowsUxHomeLayout.DESKTOP)
    val cardInteractions = enumOf(UX_KEY_CARD_INTERACTIONS, DesktopWindowsUxCardInteractions.entries, DesktopWindowsUxCardInteractions.ON)
    val detailsPanel = enumOf(UX_KEY_DETAILS_PANEL, DesktopWindowsUxDetailsPanel.entries, DesktopWindowsUxDetailsPanel.AUTO)
    val wheelAction = enumOf(UX_KEY_WHEEL_ACTION, DesktopWindowsUxWheelAction.entries, DesktopWindowsUxWheelAction.VOLUME)
    val clickAction = enumOf(UX_KEY_CLICK_ACTION, DesktopWindowsUxClickAction.entries, DesktopWindowsUxClickAction.PAUSE)
    val doubleClickAction = enumOf(UX_KEY_DOUBLE_CLICK_ACTION, DesktopWindowsUxDoubleClickAction.entries,
        DesktopWindowsUxDoubleClickAction.FULLSCREEN)

    val invalidKeys = invalid.toList()
    return DesktopWindowsUxSettings(
        railMode = railMode,
        playerLayout = playerLayout,
        homeLayout = homeLayout,
        cardInteractions = cardInteractions,
        detailsPanel = detailsPanel,
        detailsPanelWidthDp = panelWidthOk,
        wheelAction = wheelAction,
        clickAction = clickAction,
        doubleClickAction = doubleClickAction,
        windowBounds = bounds,
        invalidKeys = invalidKeys,
        error = if (invalidKeys.isEmpty()) null else DesktopWindowsUxStrings.INVALID_VALUES,
    )
}

/** Settings from a preference snapshot of the windows_ux namespace. Never throws. */
internal fun decodeDesktopWindowsUx(snapshot: DesktopPreferenceSnapshot): DesktopWindowsUxSettings {
    val values = LinkedHashMap<String, JsonElement>()
    for (key in UX_ALL_KEYS) {
        val raw = snapshot[DesktopPreferenceKey<JsonElement>(key) { it }]
        if (raw != null) values[key] = raw
    }
    return decodeDesktopWindowsUxValues(values)
}

private fun windowBoundsValues(bounds: DesktopWindowsUxWindowBounds): Map<String, JsonElement> = mapOf(
    UX_KEY_WINDOW_X to JsonPrimitive(bounds.x),
    UX_KEY_WINDOW_Y to JsonPrimitive(bounds.y),
    UX_KEY_WINDOW_WIDTH to JsonPrimitive(bounds.width),
    UX_KEY_WINDOW_HEIGHT to JsonPrimitive(bounds.height),
    UX_KEY_WINDOW_MAXIMIZED to JsonPrimitive(bounds.maximized),
)

/** Every key of settings written out explicitly. Used for the in-memory flow, not for the file. */
internal fun encodeDesktopWindowsUx(settings: DesktopWindowsUxSettings): Map<String, JsonElement> {
    val values = LinkedHashMap<String, JsonElement>()
    values[UX_KEY_RAIL_MODE] = JsonPrimitive(settings.railMode.name)
    values[UX_KEY_PLAYER_LAYOUT] = JsonPrimitive(settings.playerLayout.name)
    values[UX_KEY_HOME_LAYOUT] = JsonPrimitive(settings.homeLayout.name)
    values[UX_KEY_CARD_INTERACTIONS] = JsonPrimitive(settings.cardInteractions.name)
    values[UX_KEY_DETAILS_PANEL] = JsonPrimitive(settings.detailsPanel.name)
    settings.detailsPanelWidthDp?.let { values[UX_KEY_DETAILS_PANEL_WIDTH] = JsonPrimitive(it) }
    values[UX_KEY_WHEEL_ACTION] = JsonPrimitive(settings.wheelAction.name)
    values[UX_KEY_CLICK_ACTION] = JsonPrimitive(settings.clickAction.name)
    values[UX_KEY_DOUBLE_CLICK_ACTION] = JsonPrimitive(settings.doubleClickAction.name)
    settings.windowBounds?.let { values.putAll(windowBoundsValues(it)) }
    return values
}

/** The keys a command changes: a null value removes the key. Pure; the same edits feed the file and the in-memory flow. */
internal fun desktopWindowsUxEdits(command: DesktopWindowsUxCommand): Map<String, JsonElement?> = when (command) {
    is DesktopWindowsUxCommand.SetRailMode -> mapOf(UX_KEY_RAIL_MODE to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetPlayerLayout -> mapOf(UX_KEY_PLAYER_LAYOUT to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetHomeLayout -> mapOf(UX_KEY_HOME_LAYOUT to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetCardInteractions -> mapOf(UX_KEY_CARD_INTERACTIONS to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetDetailsPanel -> mapOf(UX_KEY_DETAILS_PANEL to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetDetailsPanelWidth -> mapOf(
        UX_KEY_DETAILS_PANEL_WIDTH to command.widthDp?.let {
            JsonPrimitive(it.coerceIn(WINDOWS_UX_DETAILS_PANEL_MIN_WIDTH_DP, WINDOWS_UX_DETAILS_PANEL_MAX_WIDTH_DP))
        })
    is DesktopWindowsUxCommand.SetWheelAction -> mapOf(UX_KEY_WHEEL_ACTION to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetClickAction -> mapOf(UX_KEY_CLICK_ACTION to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetDoubleClickAction -> mapOf(UX_KEY_DOUBLE_CLICK_ACTION to JsonPrimitive(command.value.name))
    is DesktopWindowsUxCommand.SetWindowBounds -> {
        val bounds = command.bounds
        if (bounds == null) UX_WINDOW_KEYS.associateWith { null }
        else if (bounds.isSane()) windowBoundsValues(bounds)
        else emptyMap()
    }
    DesktopWindowsUxCommand.ResetLayout -> UX_LAYOUT_KEYS.associateWith { null }
}

/** The edits that would actually change the stored values; a command that changes nothing writes nothing. */
internal fun desktopWindowsUxEffectiveEdits(
    stored: Map<String, JsonElement>,
    edits: Map<String, JsonElement?>,
): Map<String, JsonElement?> = edits.filter { (key, value) -> stored[key] != value }

/**
 * Settings after a command, derived from the same edits that the file receives.
 *
 * The encoding of the current settings is all valid, so decoding it alone would forget what the file still holds
 * that was not understood. A command only rewrites its own keys: every other key that was reported invalid stays
 * reported (the file still has that value, and the next read reports it again), and a key the command rewrites or
 * removes is repaired. The message of a read problem follows the keys that remain; the message for a namespace that
 * could not be read at all is never cleared by a command, because no command can repair it.
 */
internal fun desktopWindowsUxAfter(current: DesktopWindowsUxSettings, command: DesktopWindowsUxCommand): DesktopWindowsUxSettings {
    val edits = desktopWindowsUxEdits(command)
    val merged = LinkedHashMap<String, JsonElement>(encodeDesktopWindowsUx(current))
    edits.forEach { (key, value) -> if (value == null) merged.remove(key) else merged[key] = value }
    val stillInvalid = current.invalidKeys.filter { it !in edits.keys }
    val error = when {
        current.error == DesktopWindowsUxStrings.UNREADABLE -> current.error
        stillInvalid.isEmpty() -> null
        else -> DesktopWindowsUxStrings.INVALID_VALUES
    }
    return decodeDesktopWindowsUxValues(merged).copy(invalidKeys = stillInvalid, error = error)
}

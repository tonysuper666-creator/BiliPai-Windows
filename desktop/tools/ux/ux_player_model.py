"""Reference and mirror model of the player policy layer (desktop UX program, slice X1b). No JVM, no network.

The Kotlin files of the slice (ui/DesktopPlayerLayoutPolicy.kt, DesktopPlayerChromePolicy.kt, DesktopPlayerShortcutPolicy.kt,
DesktopPlayerSeekModel.kt, DesktopPlayerQualityRows.kt, DesktopPlayerStrings.kt and the idleNanos parameter of
DesktopWindowsFullscreenChromeState.kt) have no Compose, AWT or Windows dependency, so their logic can be mirrored exactly.
Like ux_shell_model.py this module holds three kinds of code, all reading the shared contract through ux_model:

  * parsers for the marked blocks, enum tables and constants of those Kotlin files, so a test compares the Kotlin numbers
    and tables with the contract instead of repeating them;
  * mirrors: line-by-line Python transliterations of the Kotlin functions with Kotlin integer semantics (floor division only
    on non-negative operands, Long arithmetic without overflow because Python ints do not overflow and the Kotlin input
    limits keep every product below 2**63), fed with the constants parsed from the Kotlin text. The suite pins the
    transliterated Kotlin lines as source needles, so editing either side without the other fails;
  * oracles: the same rules written from the spec prose, in a different shape from the Kotlin (ux_model.player_layout in
    exact rational arithmetic is the oracle of the layout; the vendored QualityMenu is the oracle of the quality gate).

The suite compares mirror and oracle over sweeps. That proves the Kotlin algorithm, as far as it is mirrored faithfully, and
says nothing about Kotlin compilation: the JVM tests are the proof of the real code.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

UI = source.K / "ui"
LAYOUT_KT = UI / "DesktopPlayerLayoutPolicy.kt"
CHROME_KT = UI / "DesktopPlayerChromePolicy.kt"
SHORTCUT_KT = UI / "DesktopPlayerShortcutPolicy.kt"
SEEK_KT = UI / "DesktopPlayerSeekModel.kt"
QUALITY_KT = UI / "DesktopPlayerQualityRows.kt"
STRINGS_KT = UI / "DesktopPlayerStrings.kt"
CHROME_STATE_KT = UI / "DesktopWindowsFullscreenChromeState.kt"
X1B_MAIN_FILES = (LAYOUT_KT, CHROME_KT, SHORTCUT_KT, SEEK_KT, QUALITY_KT, STRINGS_KT)
VENDORED_QUALITY_MENU = source.REPO / "app/src/main/java/com/android/purebilibili/feature/video/ui/components/QualityMenu.kt"
VENDORED_QUALITY_MANAGER = source.REPO / "app/src/main/java/com/android/purebilibili/feature/video/controller/QualityManager.kt"
VENDORED_PLAYBACK_USE_CASE = source.REPO / "app/src/main/java/com/android/purebilibili/feature/video/usecase/VideoPlaybackUseCase.kt"
VENDORED_KEY_POLICY = source.REPO / "app/src/main/java/com/android/purebilibili/feature/video/player/PlayerKeyboardPolicy.kt"

read = source.read
squash = shell.squash
marked_block = shell.marked_block


# --------------------------------------------------------------------------------------
# Kotlin parsers
# --------------------------------------------------------------------------------------

def constants(text):
    """{name: int or float} of every `const val NAME = literal` (an Int, a Long with L and underscores, or a Double)."""
    found = {}
    for name, value in re.findall(r"const val (\w+) = (-?[\d_]+(?:\.\d+)?)L?(?![\w.])", text):
        value = value.replace("_", "")
        found[name] = float(value) if "." in value else int(value)
    return found


def enum_entries(text, name):
    """[entry names] of `internal enum class NAME { A, B, ... }` (entries without constructor arguments)."""
    match = re.search(r"internal enum class %s \{(.*?)\n?\}" % re.escape(name), text, re.S)
    if match is None:
        raise AssertionError("no enum " + name)
    return re.findall(r"[A-Z][A-Z_0-9]*", match.group(1))


GENERATED_DIGITS = (') + (0..9).map { digit -> desktopPlayerKey("$digit", DesktopPlayerActionKind.SEEK_PERCENT, digit * 10) } '
                    '+ listOf(')
_ROW = re.compile(r'desktopPlayerKey\("([^"]+)", DesktopPlayerActionKind\.([A-Z_]+)(?:, (-?\d+))?'
                  r'(?:, DesktopPlayerShortcutStatus\.([A-Z_]+))?(?:, status = DesktopPlayerShortcutStatus\.([A-Z_]+))?'
                  r'(?:, whenPaused = (true))?\)')


def player_rows(text):
    """[(chord, kind, arg, status, when_paused)] of the Kotlin player table with the generated digit rows expanded."""
    block = squash(marked_block(text, "PLAYER-SHORTCUTS"))
    before, marker, after = block.partition(squash(GENERATED_DIGITS))
    if not marker:
        raise AssertionError("the generated digit rows are not in the expected form")

    def plain(part):
        return [(chord, kind, int(arg or 0), positional or named or "ACTIVE", paused == "true")
                for chord, kind, arg, positional, named, paused in _ROW.findall(part)]

    digits = [(str(digit), "SEEK_PERCENT", digit * 10, "ACTIVE", False) for digit in range(10)]
    return plain(before) + digits + plain(after)


def string_constants(text):
    return dict(re.findall(r'const val (\w+) = "([^"]*)"', text))


# --------------------------------------------------------------------------------------
# Layout (DesktopPlayerLayoutPolicy.kt)
# --------------------------------------------------------------------------------------

def round_half_even_kt(numerator, denominator):
    """desktopPlayerRoundHalfEven for a non-negative numerator and a positive denominator."""
    quotient, remainder = divmod(numerator, denominator)
    twice = 2 * remainder
    if twice < denominator:
        return quotient
    if twice > denominator:
        return quotient + 1
    return quotient if quotient % 2 == 0 else quotient + 1


def panel_default_open_kt(c, entry_width):
    """desktopPlayerPanelDefaultOpen: decided once from the width at page entry and remembered by the caller."""
    return entry_width >= c["DESKTOP_PLAYER_PANEL_DEFAULT_OPEN_FROM_WIDTH_DP"]


def panel_wanted_kt(panel, default_open):
    """desktopPlayerPanelWanted; panel is OPEN, CLOSED or AUTO, default_open the remembered page-entry decision."""
    if panel == "OPEN":
        return True
    if panel == "CLOSED":
        return False
    return default_open


EMPTY_LAYOUT = {"docked": False, "panel": 0, "video": [0, 0], "tier": "S", "bars": {"axis": "none", "size": 0}, "dock": 0}


def layout_kt(c, width, height, default_open, fullscreen=False, panel="AUTO", user_panel=None, aspect_w=None, aspect_h=None):
    """resolveDesktopPlayerLayout with the constants c parsed from the Kotlin text. default_open is required, as panelDefaultOpen
    is in Kotlin: the page-entry decision of panel_default_open_kt. Returns the dict shape of a contract "expect" (docked, panel,
    video, tier, bars, dock) so it compares directly with ux_model.player_expect."""
    aspect_w = c["DESKTOP_PLAYER_ASPECT_WIDTH"] if aspect_w is None else aspect_w
    aspect_h = c["DESKTOP_PLAYER_ASPECT_HEIGHT"] if aspect_h is None else aspect_h
    limit = c["DESKTOP_PLAYER_MAX_EXTENT_DP"]
    if not (1 <= width <= limit and 1 <= height <= limit):
        return {**EMPTY_LAYOUT, "video": [0, 0], "bars": {"axis": "none", "size": 0}}
    part = c["DESKTOP_PLAYER_MAX_ASPECT_PART"]
    sane = 1 <= aspect_w <= part and 1 <= aspect_h <= part
    aw = aspect_w if sane else c["DESKTOP_PLAYER_ASPECT_WIDTH"]
    ah = aspect_h if sane else c["DESKTOP_PLAYER_ASPECT_HEIGHT"]
    w, h = width, height
    d = 100 * ah
    if fullscreen:
        allowed = w >= c["DESKTOP_PLAYER_FULLSCREEN_MIN_ASPECT"] * h
    else:
        allowed = w >= c["DESKTOP_PLAYER_DOCK_MIN_WINDOW_WIDTH_DP"]
    panel_n = 0
    if allowed and panel_wanted_kt(panel, default_open):
        if user_panel is not None:
            candidate = min(max(user_panel, c["DESKTOP_PLAYER_PANEL_MIN_DP"]), c["DESKTOP_PLAYER_PANEL_MAX_DP"]) * d
        else:
            share = c["DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT"] * w * ah
            share = min(max(share, c["DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP"] * d), c["DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP"] * d)
            candidate = min(c["DESKTOP_PLAYER_PANEL_MAX_DP"] * d, max(share, w * d - h * aw * 100))
        fitted = min(candidate, (w - c["DESKTOP_PLAYER_MIN_STAGE_DP"]) * d)
        if fitted >= c["DESKTOP_PLAYER_PANEL_MIN_DP"] * d:
            panel_n = fitted
    stage_n = w * d - panel_n
    height_bound = stage_n * ah >= h * aw * d
    video_width_n = h * aw if height_bound else stage_n
    video_width_d = ah if height_bound else d
    video_height_n = h if height_bound else stage_n * ah
    video_height_d = 1 if height_bound else d * aw
    side_n = stage_n * ah - h * aw * d if height_bound else 0
    vertical_n = 0 if height_bound else h * d * aw - stage_n * ah
    if side_n > 0:
        axis, bar = "left_right", round_half_even_kt(side_n, 2 * d * ah)
    elif vertical_n > 0:
        axis, bar = "top_bottom", round_half_even_kt(vertical_n, 2 * d * aw)
    else:
        axis, bar = "none", 0
    if video_width_n >= c["DESKTOP_PLAYER_TIER_L_MIN_DP"] * video_width_d:
        tier = "L"
    elif video_width_n >= c["DESKTOP_PLAYER_TIER_M_MIN_DP"] * video_width_d:
        tier = "M"
    else:
        tier = "S"
    dock_n = max(min(c["DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP"] * video_width_d,
                     video_width_n - c["DESKTOP_PLAYER_DOCK_CAPSULE_MARGIN_DP"] * video_width_d), 0)
    return {"docked": panel_n > 0, "panel": round_half_even_kt(panel_n, d),
            "video": [round_half_even_kt(video_width_n, video_width_d), round_half_even_kt(video_height_n, video_height_d)],
            "tier": tier, "bars": {"axis": axis, "size": bar}, "dock": round_half_even_kt(dock_n, video_width_d)}


def oracle_layout(contract, width, height, fullscreen=False, panel="AUTO", entry_width=None, user_panel=None, aspect=None):
    """The exact rational layout of ux_model (the oracle), as a contract "expect" dict. The dock is held at 0 from below, as
    the Kotlin does, so a video narrower than the dock margin does not produce a negative width."""
    exact = model.player_layout(contract, width, height, "fullscreen" if fullscreen else "windowed", panel, user_panel, aspect,
                                entry_width)
    expect = model.player_expect(exact)
    expect["dock"] = max(0, expect["dock"])
    return expect


# --------------------------------------------------------------------------------------
# Chrome (DesktopPlayerChromePolicy.kt, DesktopWindowsFullscreenChromeState.kt)
# --------------------------------------------------------------------------------------

STATE_FIELDS = ("ready", "loading", "paused", "ended", "audio_only", "first_frame", "paused_for_cache", "has_error")


def default_state(**overrides):
    """A PlayerState reduced to the fields of the rule: a playing video unless overridden. native_paused is True, False or None."""
    state = {"ready": True, "loading": False, "paused": False, "ended": False, "audio_only": False, "first_frame": True,
             "paused_for_cache": False, "has_error": False, "native_paused": False}
    state.update(overrides)
    return state


def playback_holds_kt(state, problem):
    """desktopPlayerPlaybackHolds: the set of hold names."""
    holds = []
    if state["paused"] or state["native_paused"] is True:
        holds.append("PAUSED")
    if state["loading"] or state["paused_for_cache"] or not state["ready"] or not state["first_frame"] or state["native_paused"] is None:
        holds.append("BUFFERING")
    if state["ended"]:
        holds.append("ENDED")
    if state["has_error"] or problem:
        holds.append("ERRORED")
    if state["audio_only"]:
        holds.append("AUDIO_ONLY")
    return holds


def chrome_holds_kt(hold_order, state, problem, interaction):
    """desktopPlayerChromeHolds: the holds in enum order. interaction is a dict of the five interaction facts."""
    playback = playback_holds_kt(state, problem)
    flags = {"FLYOUT_OPEN": interaction.get("flyout_open", False), "SCRUBBING": interaction.get("scrubbing", False),
             "DRAGGING_VOLUME": interaction.get("dragging_volume", False), "DOCK_HOVERED": interaction.get("dock_hovered", False),
             "TOP_BAR_HOVERED": interaction.get("top_bar_hovered", False)}
    return [hold for hold in hold_order if hold in playback or flags.get(hold) is True]


def chrome_can_auto_hide_kt(active, focused, held, state, problem):
    """desktopPlayerChromeCanAutoHide."""
    return active and focused and not held and not playback_holds_kt(state, problem)


def existing_can_auto_hide(fullscreen, active, focused, held, state, problem):
    """The existing desktopWindowsFullscreenChromeCanAutoHide, transliterated (test_ux_player_policy pins its text)."""
    is_playing = (state["ready"] and state["first_frame"] and state["native_paused"] is False and not state["audio_only"]
                  and not state["loading"] and not state["paused"] and not state["ended"] and not state["paused_for_cache"]
                  and not state["has_error"])
    return bool(fullscreen and active and focused and not held and not problem and is_playing)


def oracle_holds(state, problem):
    """The spec 4.7 rule from prose: the controls stay while the video is not definitely playing, and the set of names says
    why. Playing means ready, first frame shown, an unpaused native readback and no pause, loading, cache stall, end, error
    or audio-only. Returned as an unordered set."""
    why = set()
    if state["paused"] or state["native_paused"] is True:
        why.add("PAUSED")
    if state["loading"] or state["paused_for_cache"] or not (state["ready"] and state["first_frame"]) or state["native_paused"] is None:
        why.add("BUFFERING")
    if state["ended"]:
        why.add("ENDED")
    if state["has_error"] or problem:
        why.add("ERRORED")
    if state["audio_only"]:
        why.add("AUDIO_ONLY")
    return why


def remaining_idle_millis_kt(idle_nanos, elapsed_nanos):
    """DesktopWindowsFullscreenChromeState.remainingIdleMillis: the idle time left, in milliseconds rounded up."""
    elapsed = max(elapsed_nanos, 0)
    remaining = max(idle_nanos - elapsed, 0)
    return (remaining + 999_999) // 1_000_000


def remaining_idle_ms_kt(c, remaining_ms, pointer_left):
    """desktopPlayerChromeRemainingIdleMs."""
    return min(remaining_ms, c["DESKTOP_PLAYER_CHROME_LEAVE_IDLE_MS"]) if pointer_left else remaining_ms


def move_counts_kt(c, dx, dy):
    """desktopPlayerPointerMoveCounts."""
    threshold = c["DESKTOP_PLAYER_CHROME_MOVE_THRESHOLD_PX"]
    return dx * dx + dy * dy >= threshold * threshold


def cursor_hidden_kt(chrome_visible, pointer_over_video):
    return (not chrome_visible) and pointer_over_video


def buffering_kt(c, buffering_ms):
    """desktopPlayerBufferingPresentation."""
    if buffering_ms >= c["DESKTOP_PLAYER_BUFFERING_TEXT_AFTER_MS"]:
        return "SPINNER_WITH_TEXT"
    if buffering_ms >= c["DESKTOP_PLAYER_BUFFERING_SPINNER_AFTER_MS"]:
        return "SPINNER"
    return "NONE"


# --------------------------------------------------------------------------------------
# Shortcuts (DesktopPlayerShortcutPolicy.kt)
# --------------------------------------------------------------------------------------

def player_table(rows):
    """{chord: (kind, arg, status, when_paused)} from player_rows(); a duplicate chord is a defect."""
    table = {}
    for chord, kind, arg, status, paused in rows:
        if chord in table:
            raise AssertionError("duplicate chord " + chord)
        table[chord] = (kind, arg, status, paused)
    return table


def resolve_player_key_kt(table, reserved, zoom_keys, key, ctrl=False, alt=False, shift=False, meta=False, key_down=True,
                          text_editor=False, paused=False):
    """resolveDesktopPlayerKey. Returns (kind, arg) or None."""
    if not key_down or text_editor:
        return None
    if meta or (ctrl and alt):
        return None
    keypad_digit = not ctrl and len(key) == 7 and key.startswith("Numpad") and key[6].isdigit()
    key = key[6:] if keypad_digit else key
    if ctrl and key in zoom_keys:
        return None
    chord = shell.chord_kt(key, ctrl, alt, shift, meta)
    if chord in reserved:
        return None
    row = table.get(chord)
    if row is None:
        return None
    kind, arg, status, when_paused = row
    if status != "ACTIVE" or (when_paused and not paused):
        return None
    return kind, arg


def oracle_player_resolve(contract, key, ctrl=False, alt=False, shift=False, meta=False, key_down=True, text_editor=False,
                          paused=False):
    """Spec 6.5 from prose over the contract rows only: what the router claims, as (kind, arg) or None."""
    table = contract["shortcuts"]
    if not key_down or text_editor:
        return None  # nothing while a text editor owns focus; only a key press is read
    if meta or (ctrl and alt):
        return None  # no Windows-key chords; Ctrl+Alt is AltGr
    if not ctrl and re.fullmatch(r"Numpad[0-9]", key):
        key = key[-1]  # the keypad digits are the digits
    mods = [name for name, held in (("Ctrl", ctrl), ("Alt", alt), ("Shift", shift)) if held]
    chord = "+".join(mods + [key])
    if chord in table["reserved"] or chord in table["not_taken"]:
        return None
    if ctrl and key in table["zoom_decoder_keys"]:
        return None
    rows = [row for row in table["rows"] if row["scope"] == "player" and row["chord"] == chord]
    if not rows:
        return None
    row = rows[0]
    if row.get("status") in ("existing-at-root", "omitted-until-mpv-api"):
        return None  # F11 stays with the shell root; the frame step waits for an mpv API
    if row.get("when") == "paused" and not paused:
        return None
    return row["action"], row.get("arg", 0)


def esc_step_kt(popup_open, fullscreen, layout):
    """resolveDesktopPlayerEscStep."""
    if popup_open:
        return "CLOSE_POPUP"
    if fullscreen:
        return "LEAVE_FULLSCREEN"
    return "LEAVE_PAGE" if layout == "LEGACY" else "NONE"


# --------------------------------------------------------------------------------------
# Seek rail (DesktopPlayerSeekModel.kt)
# --------------------------------------------------------------------------------------

def finite(value):
    return value is not None and value == value and value not in (float("inf"), float("-inf"))


def buffered_fraction_kt(buffered, position, duration):
    """desktopPlayerBufferedFraction. None is a Kotlin null."""
    if buffered is None or not finite(buffered) or not finite(position):
        return None
    if not finite(duration) or duration <= 0.0:
        return None
    return min(max((max(position, 0.0) + max(buffered, 0.0)) / duration, 0.0), 1.0)


def fine_scrub_kt(knots, distance):
    """desktopPlayerFineScrubFactor with the knots [(distance, factor), ...] of the Kotlin text."""
    distance = abs(distance)
    if distance != distance or distance <= knots[0][0]:
        return knots[0][1]
    for index in range(1, len(knots)):
        x1, y1 = knots[index]
        if distance <= x1:
            x0, y0 = knots[index - 1]
            t = (distance - x0) / (x1 - x0)
            if t >= 1.0:
                return y1
            return min(max(y0 + (y1 - y0) * t, knots[-1][1]), knots[0][1])
    return knots[-1][1]


def chapter_gaps_kt(starts_ms, duration_ms, rail_width, gap):
    """desktopPlayerChapterGapFractions."""
    if duration_ms <= 0 or not finite(rail_width) or rail_width <= 0.0:
        return []
    gaps = []
    for start in sorted(set(s for s in starts_ms if 0 < s < duration_ms)):
        fraction = start / duration_ms
        if not gaps or (fraction - gaps[-1]) * rail_width >= gap:
            gaps.append(fraction)
    return gaps


# --------------------------------------------------------------------------------------
# Quality rows (DesktopPlayerQualityRows.kt)
# --------------------------------------------------------------------------------------

def quality_auto_allowed_kt(c, quality, logged_in, vip, hdr, dolby_vision):
    """desktopPlayerQualityAutoAllowed: below the account ceiling and device-safe (126 needs Dolby Vision, 125 needs HDR)."""
    if vip:
        ceiling = c["DESKTOP_PLAYER_QUALITY_AUTO_CEILING_VIP_ID"]
    elif logged_in:
        ceiling = c["DESKTOP_PLAYER_QUALITY_AUTO_CEILING_LOGGED_IN_ID"]
    else:
        ceiling = c["DESKTOP_PLAYER_QUALITY_AUTO_CEILING_GUEST_ID"]
    if quality == c["DESKTOP_PLAYER_QUALITY_DOLBY_VISION_ID"]:
        device_safe = dolby_vision
    elif quality == c["DESKTOP_PLAYER_QUALITY_HDR_ID"]:
        device_safe = hdr
    else:
        device_safe = True
    return device_safe and quality <= ceiling


def quality_menu_kt(c, ids, labels, current, switchable, logged_in, vip, hdr, dolby_vision, switching, auto_preferred):
    """resolveDesktopPlayerQualityMenu. Returns a dict: rows [(id, label, state, enabled)], resolved_label, auto_selected,
    auto_target, switching, auto_enabled."""
    rows = []
    for index, quality in enumerate(ids):
        if quality == current:
            state = "CURRENT"
        elif (quality == c["DESKTOP_PLAYER_QUALITY_SMART_REPAIR_ID"] or quality >= c["DESKTOP_PLAYER_QUALITY_VIP_FROM_ID"]) and not vip:
            state = "NEEDS_VIP"
        elif quality >= c["DESKTOP_PLAYER_QUALITY_LOGIN_FROM_ID"] and not logged_in:
            state = "NEEDS_LOGIN"
        elif switchable and quality not in switchable:
            state = "UNAVAILABLE"
        else:
            state = "SELECTABLE"
        label = labels[index] if index < len(labels) else str(quality)
        rows.append((quality, label, state, state == "SELECTABLE" and not switching))
    better = [row for row in rows if row[2] == "SELECTABLE" and (current is None or row[0] > current)
              and quality_auto_allowed_kt(c, row[0], logged_in, vip, hdr, dolby_vision)]
    resolved = next((row[1] for row in rows if row[2] == "CURRENT"), None)
    return {"rows": rows, "resolved_label": resolved, "auto_selected": auto_preferred,
            "auto_target": max((row[0] for row in better), default=None), "switching": switching, "auto_enabled": not switching}


def vendored_quality_hint(ids_gate, quality, logged_in, vip, switchable):
    """The hint of the vendored QualitySelectionMenu for one id (the oracle of the gate). ids_gate: the thresholds read from the
    vendored text as (smart_repair, vip_from, login_from)."""
    smart, vip_from, login_from = ids_gate
    if (quality == smart or quality >= vip_from) and not vip:
        return "VIP"
    if quality >= login_from and not logged_in:
        return "LOGIN"
    if switchable and quality not in switchable:
        return "UNSWITCHABLE"
    return None


def vendored_gate_thresholds():
    """(smart_repair, vip_from, login_from) parsed from the vendored QualityMenu.kt."""
    text = squash(read(VENDORED_QUALITY_MENU))
    vip = re.search(r"\(qualityId == (\d+) \|\| qualityId >= (\d+)\) && !isVip -> ", text)
    login = re.search(r"qualityId >= (\d+) && !isLoggedIn -> ", text)
    if vip is None or login is None:
        raise AssertionError("the vendored quality gate has a new shape")
    return int(vip.group(1)), int(vip.group(2)), int(login.group(1))


def vendored_auto_rules():
    """The rules of the vendored resolveAutoHighestTargetQuality, parsed from VideoPlaybackUseCase.kt: {"ceiling": (vip, logged in,
    guest), "device": {id: parameter name}}. The oracle of the automatic target; a new shape of the function fails loudly."""
    text = squash(read(VENDORED_PLAYBACK_USE_CASE))
    start = text.index("internal fun resolveAutoHighestTargetQuality(")
    body = text[start:text.index("internal fun buildPlaybackSelectionSummary(")]
    ceiling = re.search(r"val capabilityCeiling = when \{ isVip -> (\d+) isLoggedIn -> (\d+) else -> (\d+) \}", body)
    device = re.search(r"deviceSafeQualities = acceptQualities\.filter \{ qn -> when \(qn\) \{ (\d+) -> (\w+) (\d+) -> (\w+) else -> true \} \}", body)
    tail = "val playable = deviceSafeQualities.filter { it <= capabilityCeiling } return playable.maxOrNull() ?: if (isLoggedIn) 80 else 64"
    if ceiling is None or device is None or tail not in body:
        raise AssertionError("the vendored automatic quality resolver has a new shape")
    return {"ceiling": tuple(int(group) for group in ceiling.groups()),
            "device": {int(device.group(1)): device.group(2), int(device.group(3)): device.group(4)}}


def vendored_auto_playable(rules, accepted, logged_in, vip, hdr, dolby_vision):
    """resolveAutoHighestTargetQuality without its fallback (80 or 64 when nothing is playable): the best of accepted, or None."""
    vip_ceiling, login_ceiling, guest_ceiling = rules["ceiling"]
    ceiling = vip_ceiling if vip else login_ceiling if logged_in else guest_ceiling
    supported = {"isHdrSupported": hdr, "isDolbyVisionSupported": dolby_vision}
    safe = [q for q in accepted if q not in rules["device"] or supported[rules["device"][q]]]
    playable = [q for q in safe if q <= ceiling]
    return max(playable, default=None)


def oracle_quality_state(thresholds, quality, current, logged_in, vip, switchable):
    """The row state from the vendored hint: the playing quality is the current row, any other id follows its hint."""
    if quality == current:
        return "CURRENT"
    hint = vendored_quality_hint(thresholds, quality, logged_in, vip, switchable)
    return {"VIP": "NEEDS_VIP", "LOGIN": "NEEDS_LOGIN", "UNSWITCHABLE": "UNAVAILABLE", None: "SELECTABLE"}[hint]

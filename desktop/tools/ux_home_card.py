"""Desktop Home card interaction layer on the generated VideoCard text (desktop UX program, slice H2, spec 3.6 and 6.7).

The Home card producer (extract-upstream-home-full-card.py) calls apply_home_card once per emitted file, right after the
recommendation badge layer, through one hook line (the v043_home_recommendation_badges pattern). The layer touches one generated
file and nothing else, VideoCard.kt (the grid card of the Home feed; its ElegantVideoCard function), with seven exact-once
replacements:

  * the two imports of the vendored dropdown menu become import aliases of the desktop-owned menu, so the call text of the card is
    the vendored call text (named arguments expanded, onDismissRequest, offset, and the ColumnScope trailing lambda):
        import com.bilipai.desktop.ui.DesktopCardContextMenu as AppDropdownMenu
        import com.bilipai.desktop.ui.DesktopCardContextMenuItem as AppDropdownMenuItem
  * two imports and two modifier calls after the hover effect of the card box: desktopCardSecondaryClick (the right click opens the
    same menu as the overflow button, enabled only for a card that has a menu) and desktopCardHandCursor;
  * the size of the overflow box, anchored on its neighbouring contentDescription and menuButtonCoordsRef lines because the token
    AppChromeSizeTokens.MinimumTouchTarget is used twice in the card (the unfavorite box and the overflow box): only the overflow
    box asks desktopCardOverflowTarget for its size (32 dp while card_interactions is ON, the vendored size while it is OFF);
  * three emoji-free labels, each through desktopCardMenuLabel(original, plain): the default of dismissMenuText and the literals of
    the watch later and unfavorite menu rows. The original text (with its emoji) stays in the call and is what the OFF flag shows. No
    caller passes its own dismissMenuText (checked by the adaptation suite), so changing the default is enough.

Not covered by this layer: the single-column display mode (AppWindowActionMenu), StoryVideoCard and DynamicCard. This is a
desktop-owned layer: there is no upstream archive, no manifest and no pinned predecessor hash. Every anchor is counted on the text
it receives and must occur exactly once, so a change of an earlier layer (the v041 status labels, the v043 recommendation badges,
the v046 tint policy and cascade definers) that moves one of these lines fails closed with AssertionError instead of editing nothing
or editing twice. Each edit is recorded as an audit row (the v043 shape) and is reversible: reverse() restores the received text
byte for byte.

Gradle input tracking (hunk RH3b): the module is an input of the extractUpstreamHomeFullCard task in desktop/build.gradle.kts,
because the generated text changes when this file does.
"""
import hashlib

CARD = 'VideoCard.kt'
UI = 'com.bilipai.desktop.ui.'

# The three vendored labels as they stand in the generated text (the emoji prefix, a space, the plain label) and the plain labels.
# The first two are literal characters in the vendored source, the third is written as a Kotlin escape pair there.
WATCH_LATER_PLAIN = '稍后再看'
UNFAVORITE_PLAIN = '取消收藏'
DISMISS_PLAIN = '不感兴趣'
WATCH_LATER_ORIGINAL = '\U0001F550 ' + WATCH_LATER_PLAIN
UNFAVORITE_ORIGINAL = '\U0001F494 ' + UNFAVORITE_PLAIN
DISMISS_ORIGINAL_KOTLIN = '\\uD83D\\uDEAB ' + DISMISS_PLAIN  # backslash-u escapes, exactly as the generated Kotlin text has them

# (id, text received from the earlier layers, replacement). Each `before` occurs exactly once in VideoCard.kt.
EDITS = (
    ('ux-card-menu-imports',
     'import com.android.purebilibili.core.ui.components.AppDropdownMenu\n'
     'import com.android.purebilibili.core.ui.components.AppDropdownMenuItem\n',
     'import ' + UI + 'DesktopCardContextMenu as AppDropdownMenu\n'
     'import ' + UI + 'DesktopCardContextMenuItem as AppDropdownMenuItem\n'),
    ('ux-card-modifier-imports',
     'import com.android.purebilibili.core.ui.adaptive.adaptiveCardHoverEffect\n',
     'import com.android.purebilibili.core.ui.adaptive.adaptiveCardHoverEffect\n'
     'import ' + UI + 'desktopCardHandCursor\n'
     'import ' + UI + 'desktopCardSecondaryClick\n'),
    ('ux-card-secondary-click-and-hand-cursor',
     '            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n',
     '            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n'
     '            .desktopCardSecondaryClick(enabled = hasOverflowMenu) { pressOffset ->\n'
     '                openDismissMenu(cardCoordsRef.value, pressOffset)\n'
     '            }\n'
     '            .desktopCardHandCursor()\n'),
    ('ux-card-overflow-target',
     '                            .size(AppChromeSizeTokens.MinimumTouchTarget)\n'
     '                            .semantics { contentDescription = "更多操作" }\n'
     '                            .onGloballyPositioned { coordinates ->\n'
     '                                menuButtonCoordsRef.value = coordinates\n'
     '                            }\n',
     '                            .size(' + UI + 'desktopCardOverflowTarget(AppChromeSizeTokens.MinimumTouchTarget))\n'
     '                            .semantics { contentDescription = "更多操作" }\n'
     '                            .onGloballyPositioned { coordinates ->\n'
     '                                menuButtonCoordsRef.value = coordinates\n'
     '                            }\n'),
    ('ux-card-dismiss-label-default',
     '    dismissMenuText: String = "' + DISMISS_ORIGINAL_KOTLIN + '", //  [新增] 自定义长按菜单删除文案\n',
     '    dismissMenuText: String = ' + UI + 'desktopCardMenuLabel(original = "' + DISMISS_ORIGINAL_KOTLIN + '", plain = "'
     + DISMISS_PLAIN + '"), //  [新增] 自定义长按菜单删除文案\n'),
    ('ux-card-watch-later-label',
     '                                "' + WATCH_LATER_ORIGINAL + '",\n',
     '                                ' + UI + 'desktopCardMenuLabel(original = "' + WATCH_LATER_ORIGINAL + '", plain = "'
     + WATCH_LATER_PLAIN + '"),\n'),
    ('ux-card-unfavorite-label',
     '                                "' + UNFAVORITE_ORIGINAL + '",\n',
     '                                ' + UI + 'desktopCardMenuLabel(original = "' + UNFAVORITE_ORIGINAL + '", plain = "'
     + UNFAVORITE_PLAIN + '"),\n'),
)

# After the layer none of these vendored lines may remain in the file: the menu imports and the overflow box size line.
GONE = (
    'import com.android.purebilibili.core.ui.components.AppDropdownMenu\n',
    'import com.android.purebilibili.core.ui.components.AppDropdownMenuItem\n',
    '                            .size(AppChromeSizeTokens.MinimumTouchTarget)\n                            .semantics { contentDescription = "更多操作" }\n',
)
# Names the layer introduces into the generated text. None may exist in the text it receives.
INTRODUCED = ('DesktopCardContextMenu', 'DesktopCardContextMenuItem', 'desktopCardSecondaryClick', 'desktopCardHandCursor',
              'desktopCardMenuLabel', 'desktopCardOverflowTarget')


def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def need(condition, message):
    if not condition:
        raise AssertionError(message)


def _once(text, before, after, label):
    need(text.count(before) == 1, 'Home card anchor count changed: %s (%d)' % (label, text.count(before)))
    result = text.replace(before, after, 1)
    need(result.count(after) == 1 and result.replace(after, before, 1) == text, 'Home card inverse failed: ' + label)
    return result


def apply_home_card(repo, name, text, audits):
    """The text of one emitted file with the Home card edits, or the text itself for any file the layer does not own."""
    if name != CARD:
        return text
    for introduced in INTRODUCED:
        need(introduced not in text, 'Home card layer already applied or name taken: %s in %s' % (introduced, name))
    received = text
    for label, before, after in EDITS:
        text = _once(text, before, after, label)
        audits.append(dict(id=label, beforeSha256=sha(before), afterSha256=sha(after), inverseRestoresInput=True))
    for vendored in GONE:
        need(vendored not in text, 'A vendored Home card line remains in %s: %s' % (name, vendored.strip().splitlines()[0]))
    need(reverse(name, text) == received, 'Home card layer is not reversible: ' + name)
    return text


def reverse(name, text):
    """The text before the layer: the edits undone in the opposite order, each found exactly once."""
    need(name == CARD, 'Home card layer does not own ' + name)
    for label, before, after in reversed(EDITS):
        need(text.count(after) == 1, 'Home card replacement count changed: %s (%d)' % (label, text.count(after)))
        text = text.replace(after, before, 1)
    return text

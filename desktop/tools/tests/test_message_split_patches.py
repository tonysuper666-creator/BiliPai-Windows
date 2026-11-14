"""K7 message list-detail: the build-time patches on the merged upstream message pages apply once and fail closed."""
import os
import re
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = Path(os.environ.get("BILIPAI_SOURCE_REPO", str(TOOLS.parents[1])))
MESSAGE = "com/android/purebilibili/feature/message"
# Gradle applies these patches to mergedRepositoryRoot (the live tree); inside the gate read point, whose app/ tree
# is the older legacy commit, that tree is BILIPAI_LIVE_ROOT.
UPSTREAM = Path(os.environ.get("BILIPAI_LIVE_ROOT") or REPO) / "app/src/main/java" / MESSAGE
PATCHES = REPO / "desktop/upstream-core/patches/app/src/main/java" / MESSAGE


class PatchError(Exception):
    pass


def hunks(patch):
    body = patch.replace("\r\n", "\n").split("\n")
    body = body[next(i for i, line in enumerate(body) if line.startswith("--- ")) + 2:]
    result, index = [], 0
    while index < len(body):
        header = body[index]; index += 1
        if header == "" and index == len(body):
            break
        if not header.startswith("@@ "):
            raise PatchError(f"malformed hunk header: {header}")
        before, after = [], []
        while index < len(body) and not body[index].startswith("@@ "):
            line = body[index]; index += 1
            if line == "" and index == len(body):
                break
            if line[:1] == " ":
                before.append(line[1:]); after.append(line[1:])
            elif line[:1] == "-":
                before.append(line[1:])
            elif line[:1] == "+":
                after.append(line[1:])
            else:
                raise PatchError(f"unexpected patch line: {line}")
        result.append((header, before, after))
    return result


def apply_patch(original, patch):
    """The semantics of applyUnifiedPatch in desktop/build.gradle.kts: every hunk matches exactly once."""
    lines = original.replace("\r\n", "\n").split("\n")
    for header, before, after in hunks(patch):
        matches = [s for s in range(len(lines) - len(before) + 1) if lines[s:s + len(before)] == before]
        if len(matches) != 1:
            raise PatchError(f"hunk {header} matches {len(matches)} times")
        lines = lines[:matches[0]] + after + lines[matches[0] + len(before):]
    return "\n".join(lines)


def read(path):
    return path.read_text(encoding="utf8").replace("\r\n", "\n")


def patched(name):
    return apply_patch(read(UPSTREAM / name), read(PATCHES / f"{name}.patch"))


def k7_hunks(name, marker):
    return [h for h in hunks(read(PATCHES / f"{name}.patch")) if any(marker in line for line in h[2])]


def function_body(source, name):
    start = re.search(r"(?m)^(?:private |internal )?fun " + re.escape(name) + r"\(", source)
    if start is None:
        raise AssertionError(f"missing function {name}")
    end = re.search(r"(?m)^}\n", source[start.start():])
    return source[start.start():start.start() + end.end()]


class MessageSplitPatchesTest(unittest.TestCase):
    def test_message_center_hosts_list_and_chat_in_the_k7_container_with_the_original_owner_seams(self):
        screen = patched("MessageCenterScreen.kt")
        body = function_body(screen, "MessageCenterScreen")
        self.assertEqual(body.count("com.bilipai.desktop.ui.DesktopMessageListDetail("), 1)
        self.assertNotIn("SupportingPaneScaffold(", body)
        # The container's available width is the only size decision: no window-size-class branch returns
        # early (a 1200x479dp window still splits) and the selection is remembered on the single path.
        for gone in ("useTwoPane", "LocalWindowSizeClass.current", "return\n", "keepPaneChat(0L, 0)", "= onOpenSessionFullScreen"):
            self.assertNotIn(gone, body.split(") {\n", 1)[1])
        wide = body
        self.assertLess(wide.index("rememberSaveable(accountIdentity) { mutableLongStateOf(0L) }"),
                        wide.index("com.bilipai.desktop.ui.DesktopMessageListDetail("))
        # The host keeps the real owner admission and retention, and mounts each page exactly once.
        self.assertEqual(wide.count("pageOwner.selectPaneChat(talkerId, sessionType) {"), 1)
        self.assertEqual(wide.count("pageOwner.retainPaneChat(activeTalkerId, activeSessionType)"), 1)
        self.assertEqual(wide.count("pageOwner.keepPaneChat(activeTalkerId, activeSessionType)"), 1)
        self.assertIn('chatKey = if (activeTalkerId != 0L && retainedPaneChat) "$activeTalkerId:$activeSessionType" else null', wide)
        self.assertEqual(wide.count("InboxScreen("), 1)
        self.assertEqual(wide.count("ChatScreen("), 1)
        self.assertLess(wide.index("list = {"), wide.index("InboxScreen("))
        self.assertLess(wide.index("chat = {"), wide.index("ChatScreen("))
        self.assertIn("BackHandler(enabled = activeTalkerId != 0L)", wide)

    def test_notification_entries_are_one_tab_row_with_the_original_click_routing(self):
        inbox = patched("InboxScreen.kt")
        row = function_body(inbox, "MessageCenterTopShortcutRow")
        self.assertIn("com.bilipai.desktop.ui.DesktopMessageNotificationTabs(items = items, onItemClick = onItemClick)", row)
        self.assertNotIn("chunked(", row)
        call = inbox[inbox.index('item(key = "message_center_shortcut_row")'):]
        call = call[:call.index("\n                        }\n")]
        self.assertIn("else onTopItemClick(destination)", call)
        self.assertEqual(inbox.count("MessageCenterTopShortcutRow("), 2)

    def test_send_shortcut_wraps_only_the_private_message_input(self):
        chat = patched("ChatScreen.kt")
        self.assertEqual(chat.count("rememberDesktopMessageSendShortcut("), 1)
        bar = function_body(chat, "ChatInputBar")
        shortcut = bar.index("com.bilipai.desktop.ui.rememberDesktopMessageSendShortcut(text, isBusy, onSend)")
        self.assertLess(bar.index("BottomBarMatchedReusableLiquidDock("), shortcut)
        self.assertLess(shortcut, bar.index("BasicTextField("))
        self.assertLess(shortcut, bar.index("AppCompactInputField("))
        self.assertNotIn(".desktopChatImePadding()", chat[chat.index("fun ChatInputBar("):])

    def test_missing_or_duplicated_k7_anchor_fails_closed(self):
        cases = [
            ("MessageCenterScreen.kt", "DesktopMessageListDetail"),
            ("InboxScreen.kt", "DesktopMessageNotificationTabs"),
            ("ChatScreen.kt", "rememberDesktopMessageSendShortcut"),
        ]
        for name, marker in cases:
            original = read(UPSTREAM / name)
            patch = read(PATCHES / f"{name}.patch")
            found = k7_hunks(name, marker)
            self.assertEqual(len(found), 1, name)
            _, before, _ = found[0]
            anchor = "\n".join(before)
            self.assertEqual(original.count(anchor), 1, name)
            with self.subTest(name=name, case="missing"):
                with self.assertRaisesRegex(PatchError, "matches 0 times"):
                    apply_patch(original.replace(anchor, anchor.replace(before[-1], before[-1] + " // drift"), 1), patch)
            with self.subTest(name=name, case="duplicated"):
                with self.assertRaisesRegex(PatchError, "matches 2 times"):
                    apply_patch(original + "\n" + anchor + "\n", patch)


if __name__ == "__main__":
    unittest.main()

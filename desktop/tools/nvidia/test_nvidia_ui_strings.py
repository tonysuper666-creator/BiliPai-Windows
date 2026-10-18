"""Source checks for the NVIDIA enhancement strings (slice S1: the single owner file).

Later slices extend this file with the Controls and Session sources. The checks read the string
literals of the Kotlin source as data: the tier word and the forbidden words are lexical rules."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

FORBIDDEN_WORDS = ("DLSS", "Tensor", "帧生成", "插帧", "补帧", "HDR10+", "支持 HDR", "已应用")
TIER_WORDS = ("极致", "高", "均衡", "轻量")

# Design section 11.3. Each row is the static text that must appear exactly once in the owner file;
# lines of the form "{GPU} · text" keep the text after the separator, which the owner composes.
SECTION_11_3 = {
    "OFF, GPU known": ["自动增强已关闭，可随时开启"],
    "OFF, GPU unknown": ["自动增强已关闭，使用原画播放。"],
    "PROBING": ["正在检测显卡…"],
    "NO_NVIDIA": ["未检测到可用的 NVIDIA RTX 显卡，使用原画播放。设置会保留，插入或启用显卡后自动生效。"],
    "PRE_RTX": [" 不支持 RTX 视频增强（需要 GeForce RTX 20 系列或更新），使用原画播放。"],
    "DRIVER_OLD": [" 需要较新的显卡驱动（建议 572.16 或更新），请更新驱动后重启应用。"],
    "driver advisory": ["驱动版本较旧，增强可能不可用，建议更新驱动。"],
    "HDR driver old": ["驱动版本较旧，SDR 转 HDR 暂不可用，已仅使用清晰度增强。"],
    "NOT_PHYSICAL": ["只检测到虚拟显示适配器，未启用增强。"],
    "PIN_UNSAFE / PHYSICAL_UNVERIFIED": [
        "无法确认播放使用的是实体 NVIDIA 显卡（系统中存在同名虚拟适配器），为保证显示正确，使用原画。"],
    "VRAM_LOW": [" 显存不足，使用原画播放。"],
    "UNIDENTIFIED": ["未能读取显卡详情，将按保守档位处理。"],
    "STANDBY": ["播放视频时自动增强"],
    "WAITING": ["等待视频画面"],
    "DIRECT": ["当前画面尺寸无需放大，原画直出"],
    "OVER_BUDGET": ["片源分辨率或帧率超出当前显卡的实时处理范围，原画直出"],
    "PENDING": ["正在准备增强…"],
    "SUBMITTED": ["已提交增强，等待画面反馈"],
    "PROOF_DELAYED": ["增强已提交，但暂时无法确认画面已显示（多显示器、窗口被遮挡或全屏切换时会出现）。"],
    "ACTIVE, VEYRA": ["增强画面已显示", "；SDR 转 HDR 已显示"],
    "ACTIVE, DRIVER": ["已向 NVIDIA 驱动请求增强；是否生效取决于驱动或控制面板设置，本程序无法确认。"],
    "DRIVER route, not active": ["使用驱动增强路径，质量与强度不可调。"],
    "requested detail": ["已请求：质量 ", "/4 · 强度 "],
    "STEPPED_DOWN": ["已自动降至质量 ", "/4 以保持流畅（本视频内保持）"],
    "GUARD_BYPASS": ["已为保证流畅停止增强，本视频保持原画。"],
    "PAUSED_GAME": ["检测到其他全屏应用（可能是游戏），已暂停增强让出显卡。"],
    "PAUSED_BATTERY": ["正在使用电池供电，已暂停增强（播放仍使用独立显卡）。"],
    "PAUSED_REMOTE": ["当前为远程或虚拟显示输出，已暂停增强。"],
    "CROSS_ADAPTER suffix": ["（视频显示在另一块显卡的屏幕上，已降低档位）"],
    "FAILED_REMEMBERED": ["此显卡与驱动组合上增强曾失败，已改用原画；更新驱动或组件后自动重试。"],
    "FAILED_THIS_VIDEO": ["这段视频暂时无法增强，已使用原画。"],
    "FAILED_EXTENDED_RANGE": ["这段视频的分辨率超出已验证范围，已使用原画。"],
    "COLOR_UNSUPPORTED": ["当前视频色彩格式不支持增强，可关闭增强。"],
    "LUID mismatch": ["播放器实际使用的显卡与检测到的物理 NVIDIA 显卡不一致，继续原画播放。"],
    "LUID unknown": ["无法确认播放器实际使用的显卡，继续原画播放。"],
    "non-D3D11 decode": ["当前不是 D3D11 硬件解码，无法进行 NVIDIA 增强。"],
    "tier denies SR+HDR": ["为保持流畅，当前档位仅启用清晰度增强。"],
    "switch subtitle, capable": ["检测到支持的 NVIDIA RTX 显卡时自动开启，并按显卡性能选择配置。不会修改显卡或系统设置。"],
    "switch subtitle, not capable": ["未检测到可用的 NVIDIA RTX 显卡，此功能不会启用。"],
    "quality ceiling helper": ["默认「最高」。自动模式按显卡性能选择实际等级，不会超过此上限。"],
}


def function_body(code, name):
    """Source text of the top-level function 'name', from its signature to its closing brace at column 0."""
    start = code.index("fun %s(" % name)
    end = code.index("\n}\n", start)
    return code[start:end + 3]


class StatusTextSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(source.STATUS_TEXT)
        cls.code, cls.literals = source.lex_kotlin(cls.text)

    def test_every_documented_string_appears_exactly_once(self):
        for row, fragments in SECTION_11_3.items():
            for fragment in fragments:
                owners = [literal for literal in self.literals if fragment in literal]
                self.assertEqual(1, len(owners), "%s: %r appears in %d literals" % (row, fragment, len(owners)))

    def test_the_owner_file_is_the_only_place_a_documented_string_may_live_in_nvidia_sources(self):
        for path in source.KN.glob("*.kt"):
            if path == source.STATUS_TEXT:
                continue
            _, literals = source.lex_kotlin(source.read(path))
            for row, fragments in SECTION_11_3.items():
                for fragment in fragments:
                    self.assertFalse([item for item in literals if fragment in item], "%s repeats %s" % (path.name, row))

    def test_no_forbidden_word_appears_in_any_string_literal(self):
        for literal in self.literals:
            for word in FORBIDDEN_WORDS:
                self.assertNotIn(word, literal)

    def test_a_request_is_never_worded_as_applied(self):
        for literal in self.literals:
            self.assertNotIn("已应用", literal)
        requested = [literal for literal in self.literals if "已请求" in literal]
        self.assertEqual(1, len(requested))

    def test_requested_detail_exists_only_for_the_shared_core_route(self):
        body = function_body(self.code, "desktopNvidiaRequestedDetail")
        self.assertIn("route != NvidiaVideoBackend.VEYRA_CORE", body)
        self.assertIn("return null", body)
        self.assertEqual(1, self.code.count("DesktopNvidiaStatusText.requestedDetail("))

    def test_hdr_appears_only_as_conversion_wording(self):
        hdr_literals = [literal for literal in self.literals if "HDR" in literal]
        self.assertEqual(2, len(hdr_literals))
        for literal in hdr_literals:
            self.assertIn("SDR 转 HDR", literal)

    def test_driver_version_numbers_appear_only_in_the_blackwell_text(self):
        numbered = [literal for literal in self.literals if re.search(r"\d{3}\.\d{2}", literal)]
        self.assertEqual(1, len(numbered))
        self.assertIn("572.16", numbered[0])
        self.assertIn("需要较新的显卡驱动", numbered[0])

    def test_blackwell_floor_text_matches_the_classification_constant(self):
        capability = source.read(source.CAPABILITY)
        match = re.search(r"NVIDIA_BLACKWELL_MIN_DRIVER_X100: Int = ([\d_]+)", capability)
        self.assertIsNotNone(match)
        value = int(match.group(1).replace("_", ""))
        self.assertEqual("572.16", "%d.%02d" % (value // 100, value % 100))

    def test_tier_word_switch_is_off_in_the_shipped_source(self):
        self.assertEqual(1, len(re.findall(r"internal const val TIER_WORD_ENABLED: Boolean = false\b", self.code)))
        self.assertNotRegex(self.code, r"TIER_WORD_ENABLED: Boolean = true")

    def test_tier_words_live_only_inside_the_gated_function(self):
        body = function_body(self.code, "desktopNvidiaTierWord")
        self.assertIn("if (!enabled || tier == null || route != NvidiaVideoBackend.VEYRA_CORE) return null", body)
        gated = set()
        for word in TIER_WORDS:
            quoted = '"%s"' % word
            self.assertEqual(1, self.code.count(quoted), word)
            self.assertIn(quoted, body, word)
            gated.add(quoted)
        outside = self.code.replace(body, "")
        for word in TIER_WORDS:
            self.assertNotIn('"%s"' % word, outside, word)
        self.assertEqual(1, self.code.count("极致"), "the top tier name appears once")

    def test_the_tier_suffix_is_only_built_from_the_gated_word(self):
        uses = [line for line in self.code.splitlines() if "desktopNvidiaTierWord(" in line and "fun " not in line]
        self.assertEqual(1, len(uses))
        self.assertIn("tierWordEnabled", uses[0])
        suffix = [line for line in self.code.splitlines() if "档位\"" in line and "tierWord" in line]
        self.assertEqual(1, len(suffix))
        self.assertIn("if (tierWord == null)", suffix[0])

    def test_driver_route_texts_never_claim_a_confirmed_result(self):
        owners = [literal for literal in self.literals if "已向 NVIDIA 驱动请求增强" in literal]
        self.assertEqual(1, len(owners))
        self.assertIn("无法确认", owners[0])
        self.assertIn("质量与强度不可调", "".join(self.literals))
        driver_tails = [literal for literal in self.literals if "使用驱动增强路径" in literal]
        self.assertEqual(1, len(driver_tails))

    def test_the_first_person_status_never_mentions_greatest_use_of_the_gpu(self):
        for literal in self.literals:
            self.assertNotIn("最大化利用显卡", literal)

    def test_status_kinds_cover_every_documented_state(self):
        enum = re.search(r"enum class DesktopNvidiaStatusKind \{(.*?)\n\}", self.code, re.S)
        self.assertIsNotNone(enum)
        kinds = set(re.findall(r"[A-Z0-9_]+", enum.group(1)))
        expected = {"OFF", "PROBING", "NO_NVIDIA", "UNSUPPORTED", "UNIDENTIFIED", "STANDBY", "WAITING", "DIRECT",
                    "OVER_BUDGET", "PENDING", "SUBMITTED", "PROOF_DELAYED", "ACTIVE", "STEPPED_DOWN", "GUARD_BYPASS",
                    "PAUSED_GAME", "PAUSED_BATTERY", "PAUSED_REMOTE", "FAILED_REMEMBERED", "FAILED_THIS_VIDEO",
                    "FAILED_EXTENDED_RANGE", "COLOR_UNSUPPORTED", "LUID_MISMATCH", "LUID_UNKNOWN", "NON_D3D11_DECODE"}
        self.assertEqual(expected, kinds)

    def test_string_templates_use_braces_so_chinese_text_cannot_extend_an_identifier(self):
        for literal in self.literals:
            self.assertNotRegex(literal, r"\$[A-Za-z_]")


if __name__ == "__main__":
    unittest.main()

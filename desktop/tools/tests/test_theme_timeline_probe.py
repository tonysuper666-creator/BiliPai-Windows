"""Synthetic positive/negative cases for the TON-1210 probe; no app, VM or Gradle involved."""
import argparse
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

PROBE = Path(__file__).resolve().parents[3] / "desktop/tools/diagnostics/theme-switch"
FIXTURES = PROBE / "fixtures"
_spec = importlib.util.spec_from_file_location("theme_timeline_probe", PROBE / "theme_timeline_probe.py")
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)


def run(*argv):
    return run_captured(*argv)[0]


def run_captured(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        return probe.main([str(a) for a in argv]), out.getvalue(), err.getvalue()


def analyse(trace=None, samples=(), presentmon=None, require_present=False):
    findings = probe.Findings()
    args = argparse.Namespace(trace=trace, samples=list(samples), presentmon=presentmon,
                              require_present=require_present, json=None)
    return probe.analyse(args, findings), findings


class KnownOrder(unittest.TestCase):
    def test_segments_present_and_pixels_are_exact(self):
        report, findings = analyse(FIXTURES / "ordered.trace", [FIXTURES / "ordered.samples.csv"],
                                   FIXTURES / "ordered.presentmon.csv", require_present=True)
        self.assertEqual(findings.code, probe.EXIT_OK, findings.notes)
        switch, = report["switches"]
        segments = {s["to"]: s["ms"] for s in switch["segments_ms"]}
        self.assertEqual(segments["store.forced"], 180.0)
        self.assertEqual(segments["theme.applied"], 399.0)
        self.assertEqual(segments["frame.draw"], 60.0)
        self.assertEqual(switch["click_to_first_draw_ms"], 680.3)
        self.assertEqual(switch["origin"], "input.release")
        # The carrying frame's CPUStart is 10 ms before the draw; only CPUStart + MsCPUBusy is the Present() call.
        self.assertEqual(switch["present"], {"draw_to_present_call_ms": 1.2, "draw_to_present_return_ms": 2.0,
                                             "draw_to_displayed_ms": 18.7})
        merged, = switch["samples"]
        self.assertEqual(merged["client"]["after_origin_ms"], [670.0, 720.0])
        self.assertEqual(merged["attr20"]["after_ref_ms"], [-0.4, 49.6])
        self.assertEqual([d["target"] for d in switch["dwm_sets"]], [True])
        self.assertEqual(report["clock_sync"], {"offset_ns": 1999999000, "error_ns": 1000, "qpc_freq": 10000000})

    def test_startup_palette_and_draw_are_not_attributed_to_the_switch(self):
        report, _ = analyse(FIXTURES / "ordered.trace")
        marks = report["switches"][0]["marks_ms"]
        self.assertGreater(marks["frame.draw"], 0)
        self.assertGreater(marks["theme.palette_built"], 0)

    def test_cli_exit_code(self):
        self.assertEqual(run("--trace", FIXTURES / "ordered.trace", "--samples", FIXTURES / "ordered.samples.csv",
                             "--presentmon", FIXTURES / "ordered.presentmon.csv"), 0)


class OutOfOrder(unittest.TestCase):
    def test_force_before_write_is_a_causal_violation(self):
        self.assertEqual(run("--trace", FIXTURES / "out-of-order.trace"), probe.EXIT_ORDER)

    def test_file_order_does_not_decide_causal_order(self):
        # theme.applied reached the file after frame.draw although its timestamp is earlier.
        report, findings = analyse(FIXTURES / "arrival-reordered.trace")
        self.assertEqual(findings.code, probe.EXIT_OK, findings.notes)
        self.assertEqual(report["switches"][0]["click_to_first_draw_ms"], 680.3)

    def test_target_dwm_set_before_new_palette_is_reported(self):
        report, findings = analyse(FIXTURES / "dwm-before-palette.trace")
        self.assertEqual(findings.code, probe.EXIT_ORDER)
        self.assertTrue(any("DWM 设置早于新 palette 构建" in text for _, text in findings.notes), findings.notes)
        self.assertEqual([d["target"] for d in report["switches"][0]["dwm_sets"]], [True])

    def test_stale_dwm_set_is_listed_but_not_the_switch_stage(self):
        report, findings = analyse(FIXTURES / "dwm-stale-set.trace")
        self.assertEqual(findings.code, probe.EXIT_OK, findings.notes)
        switch, = report["switches"]
        self.assertEqual([(d["light"], d["target"]) for d in switch["dwm_sets"]], [(False, False), (True, True)])
        self.assertEqual(switch["marks_ms"]["dwm.set_start"], 620.4)

    def test_pixel_change_before_first_frame_is_rejected(self):
        _, findings = analyse(FIXTURES / "ordered.trace", [FIXTURES / "pixel-before-draw.samples.csv"])
        self.assertEqual(findings.code, probe.EXIT_ORDER)
        self.assertIn("早于应用首帧", findings.notes[-1][1])


class CrossClock(unittest.TestCase):
    def test_relative_sampler_is_not_merged(self):
        report, findings = analyse(FIXTURES / "ordered.trace", [FIXTURES / "relative-clock.samples.csv"])
        self.assertEqual(findings.code, probe.EXIT_CLOCK)
        self.assertNotIn("samples", report["switches"][0])

    def test_trace_without_sync_cannot_join_qpc_sources(self):
        self.assertEqual(run("--trace", FIXTURES / "no-clock-sync.trace", "--samples",
                             FIXTURES / "ordered.samples.csv"), probe.EXIT_CLOCK)
        self.assertEqual(run("--trace", FIXTURES / "no-clock-sync.trace", "--presentmon",
                             FIXTURES / "ordered.presentmon.csv"), probe.EXIT_CLOCK)

    def test_clock_beats_order_when_both_fail(self):
        self.assertEqual(run("--trace", FIXTURES / "out-of-order.trace", "--samples",
                             FIXTURES / "relative-clock.samples.csv"), probe.EXIT_CLOCK)


class MissingFirstFrame(unittest.TestCase):
    def test_no_root_draw_after_apply(self):
        _, findings = analyse(FIXTURES / "missing-first-frame.trace")
        self.assertEqual(findings.code, probe.EXIT_MISSING)
        self.assertIn("缺首帧", findings.notes[0][1])

    def test_missing_present_only_fails_when_required(self):
        trace, present = FIXTURES / "ordered.trace", FIXTURES / "no-present-after-draw.presentmon.csv"
        self.assertEqual(run("--trace", trace, "--presentmon", present), probe.EXIT_OK)
        self.assertEqual(run("--trace", trace, "--presentmon", present, "--require-present"), probe.EXIT_MISSING)
        report, _ = analyse(trace, presentmon=present)
        self.assertNotIn("present", report["switches"][0])

    def test_a_later_switch_present_is_not_borrowed(self):
        trace, present = FIXTURES / "two-switches.trace", FIXTURES / "only-second-present.presentmon.csv"
        self.assertEqual(run("--trace", trace, "--presentmon", present, "--require-present"), probe.EXIT_MISSING)
        report, findings = analyse(trace, presentmon=present)
        self.assertEqual(findings.code, probe.EXIT_OK, findings.notes)
        first, second = report["switches"]
        self.assertNotIn("present", first)
        self.assertEqual(second["present"], {"draw_to_present_call_ms": 1.2, "draw_to_present_return_ms": 2.0,
                                             "draw_to_displayed_ms": 18.7})

    def test_marks_dropped_by_the_app_make_the_timeline_incomplete(self):
        report, findings = analyse(FIXTURES / "dropped.trace")
        self.assertEqual(findings.code, probe.EXIT_MISSING)
        self.assertEqual(report["trace"], {"dropped": 3, "ended": True})

    def test_missing_end_line_is_only_a_note(self):
        _, findings = analyse(FIXTURES / "ordered.trace")
        self.assertIn((probe.EXIT_OK, True), [(code, "没有 '# end' 结束行" in text) for code, text in findings.notes])
        report, findings = analyse(FIXTURES / "ended.trace")
        self.assertEqual((findings.code, findings.notes), (probe.EXIT_OK, []))
        self.assertEqual(report["trace"], {"dropped": 0, "ended": True})


class Usage(unittest.TestCase):
    def test_parse_and_usage_errors(self):
        self.assertEqual(run("--trace", FIXTURES / "unparseable.trace"), probe.EXIT_USAGE)
        self.assertEqual(run(), probe.EXIT_USAGE)
        self.assertEqual(run("--presentmon", FIXTURES / "ordered.presentmon.csv"), probe.EXIT_USAGE)
        self.assertEqual(run("--trace", FIXTURES / "ordered.trace", "--presentmon",
                             FIXTURES / "missing-cpu-busy.presentmon.csv"), probe.EXIT_USAGE)

    def test_malformed_field_values_are_parse_errors_without_a_report(self):
        ordered = (FIXTURES / "ordered.trace").read_text(encoding="utf-8")
        for case, text in (("dur_ns", ordered.replace("dur_ns=24000000", "dur_ns=invalid")),
                           ("hr", ordered.replace("hr=0", "hr=0x0")),
                           ("dark", ordered.replace("dark=false", "dark=maybe", 1)),
                           ("end", ordered + "# end dropped=x\n")):
            with self.subTest(case), tempfile.TemporaryDirectory() as scratch:
                trace, report = Path(scratch) / "bad.trace", Path(scratch) / "report.json"
                trace.write_text(text, encoding="utf-8")
                code, out, err = run_captured("--trace", trace, "--json", report)
                self.assertEqual((code, out), (probe.EXIT_USAGE, ""))
                self.assertIn("解析失败", err)
                self.assertNotIn("Traceback", err)
                self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()

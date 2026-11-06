#!/usr/bin/env python3
"""TON-1210 theme-switch timeline probe: offline, read-only, never starts the app.

Inputs (at least one):
  --trace       in-app marks written by -Dbilipai.trace.themeSwitch=<file> (JVM System.nanoTime)
  --samples     external pixel/DWM sampler CSV, repeatable (t_s relative to the sampler base)
  --presentmon  PresentMon 2.x CSV with CPUStartQPC; ticks are converted with the trace's QPC frequency.
                CPUStart is when the CPU began the frame, so the Present() call is CPUStart + MsCPUBusy;
                return = call + MsInPresentAPI, displayed = call + MsUntilDisplayed

Exit codes: 0 complete and causally ordered; 1 usage/parse error; 2 causal order violated;
3 a required stage is missing (e.g. no first frame); 4 two clock domains without a sync point.
When several apply, the most severe wins: 1 > 4 > 2 > 3.

A pixel change is only ever reported as the interval (previous sample, changing sample]:
the sampler reads the DWM-composed desktop, not the panel scan-out.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import dataclass
from pathlib import Path

EXIT_OK, EXIT_USAGE, EXIT_ORDER, EXIT_MISSING, EXIT_CLOCK = 0, 1, 2, 3, 4
_RANK = {EXIT_OK: 0, EXIT_MISSING: 1, EXIT_ORDER: 2, EXIT_CLOCK: 3, EXIT_USAGE: 4}

# One switch must traverse these marks in this order; False = optional (entry-specific).
CHAIN = (
    ("input.press", False), ("input.release", False),
    ("pref.request", True), ("pref.io_start", True),
    ("pref.admission_wait", False), ("pref.admission_acquired", False),
    ("store.lock_request", True), ("store.lock_acquired", True),
    ("store.written", True), ("store.forced", True), ("store.moved", True), ("store.published", True),
    ("prefs.settings_emit", True), ("theme.palette_built", True), ("theme.applied", True),
    ("frame.draw", True),
)
SEGMENTS = (
    ("input.release", "pref.request", "抬起→写请求（EDT 分发+协程派发）"),
    ("pref.request", "pref.io_start", "切到 IO 线程"),
    ("pref.admission_wait", "pref.admission_acquired", "个人页 gate 锁等待"),
    ("store.lock_request", "store.lock_acquired", "store backing 锁等待"),
    ("store.lock_acquired", "store.written", "编码+临时文件+写入"),
    ("store.written", "store.forced", "force(true)"),
    ("store.forced", "store.moved", "准入检查+原子替换"),
    ("store.moved", "store.published", "发布 snapshot"),
    ("store.published", "prefs.settings_emit", "收集新设置（EDT 派发+解码）"),
    ("prefs.settings_emit", "theme.palette_built", "到 palette 构建完成"),
    ("theme.palette_built", "theme.applied", "整树重组+apply"),
    ("theme.applied", "frame.draw", "布局+根绘制录制完成"),
)
# Marks only meaningful once the new palette exists (startup/older applies are not this switch).
_AFTER_APPLY = {"frame.draw", "frame.next"}
_AFTER_PALETTE = {"dwm.set_start", "dwm.set_end", "awt.window_background"}


class ProbeError(Exception):
    """Unreadable input: exit 1, never a partial timeline."""


@dataclass
class Event:
    t: int
    name: str
    fields: dict
    line: int


class Findings:
    def __init__(self) -> None:
        self.code = EXIT_OK
        self.notes: list[tuple[int, str]] = []

    def add(self, code: int, text: str) -> None:
        self.notes.append((code, text))
        if _RANK[code] > _RANK[self.code]:
            self.code = code


def _pairs(tokens, where):
    out = {}
    for token in tokens:
        key, sep, value = token.partition("=")
        if not sep or not key:
            raise ProbeError(f"{where}: 不是 key=value：{token!r}")
        out[key] = value
    return out


def _int(value, where):
    try:
        return int(value)
    except (TypeError, ValueError):
        raise ProbeError(f"{where}: 需要整数，得到 {value!r}") from None


def parse_trace(path: Path):
    header, sync, events = None, None, []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        where = f"{path.name}:{number}"
        if not line:
            continue
        if line.startswith("#"):
            parts = line[1:].split()
            if parts[:1] == ["ton1210-trace"]:
                if parts[1:2] != ["v1"]:
                    raise ProbeError(f"{where}: 只认 ton1210-trace v1")
                header = _pairs(parts[2:], where)
            elif parts[:1] == ["clock_sync"]:
                sync = {k: _int(v, where) for k, v in _pairs(parts[1:], where).items()}
                for key in ("jvm_nano_before", "qpc_ticks", "jvm_nano_after", "qpc_freq"):
                    if key not in sync:
                        raise ProbeError(f"{where}: clock_sync 缺 {key}")
                if sync["qpc_freq"] <= 0 or sync["jvm_nano_after"] < sync["jvm_nano_before"]:
                    raise ProbeError(f"{where}: clock_sync 数值无效")
            continue
        parts = line.split()
        if len(parts) < 2:
            raise ProbeError(f"{where}: 打点行至少要有 <nanoTime> <event>")
        events.append(Event(_int(parts[0], where), parts[1], _pairs(parts[2:], where), number))
    if header is None:
        raise ProbeError(f"{path.name}: 缺 '# ton1210-trace v1' 表头")
    return header, sync, events


def parse_samples(path: Path):
    meta, rows, columns = {}, [], None
    with path.open(encoding="utf-8", newline="") as handle:
        for number, record in enumerate(csv.reader(handle), 1):
            where = f"{path.name}:{number}"
            if not record or not "".join(record).strip():
                continue
            if record[0].lstrip().startswith("#"):
                meta.update(_pairs(",".join(record).lstrip()[1:].split(), where))
                continue
            if columns is None:
                columns = {name.strip(): index for index, name in enumerate(record)}
                for name in ("t_s", "dwm_attr20", "client_r", "client_g", "client_b"):
                    if name not in columns:
                        raise ProbeError(f"{where}: 采样表头缺 {name}")
                continue
            try:
                t = float(record[columns["t_s"]])
                attr = int(record[columns["dwm_attr20"]])
                client = tuple(int(record[columns[c]]) for c in ("client_r", "client_g", "client_b"))
                caption = tuple(int(record[columns[c]]) for c in ("caption_r", "caption_g", "caption_b")
                                if c in columns) or None
            except (ValueError, IndexError):
                raise ProbeError(f"{where}: 采样行无法解析") from None
            if rows and t <= rows[-1][0]:
                raise ProbeError(f"{where}: 采样时间不递增")
            rows.append((t, attr, client, caption))
    if not rows:
        raise ProbeError(f"{path.name}: 没有采样行")
    for key in ("mouse_down_t_s", "mouse_up_t_s"):
        if key in meta:
            try:
                meta[key] = float(meta[key])
            except ValueError:
                raise ProbeError(f"{path.name}: {key} 不是数字") from None
    return meta, rows


def first_change(rows, pick):
    """(prev_t, t] of the first sample differing from the first one, and the settle time."""
    values = [pick(row) for row in rows]
    if values[0] is None:
        return None
    for index in range(1, len(values)):
        if values[index] != values[0]:
            settle = len(values) - 1
            while settle > index and values[settle - 1] == values[-1]:
                settle -= 1
            return {"prev_t_s": rows[index - 1][0], "t_s": rows[index][0],
                    "settle_t_s": rows[settle][0], "from": values[0], "to": values[-1]}
    return None


def _ms(ns):
    return None if ns is None else round(ns / 1e6, 3)


def analyse_samples_only(path: Path, findings: Findings):
    meta, rows = parse_samples(path)
    down, up = meta.get("mouse_down_t_s"), meta.get("mouse_up_t_s")
    anchor = up if up is not None else down
    report = {"file": path.name, "clock": meta.get("clock", "relative"), "mouse_down_t_s": down,
              "mouse_up_t_s": up, "anchor": "mouse_up" if up is not None else "mouse_down"}
    if anchor is None:
        findings.add(EXIT_MISSING, f"{path.name}: 没有 mouse_down/mouse_up 时间，无法求点击后延迟")
        return report
    if up is None:
        findings.add(EXIT_OK, f"{path.name}: 只有 mouse_down；Compose onClick 在抬起时触发，按下锚点会多算按住时长")
    report["first_sample_after_anchor_ms"] = round((rows[0][0] - anchor) * 1000, 3)
    for key, pick in (("client", lambda r: r[2]), ("attr20", lambda r: r[1]), ("caption", lambda r: r[3])):
        change = first_change(rows, pick)
        if change is None:
            if key == "client":
                findings.add(EXIT_MISSING, f"{path.name}: 客户区像素在采样窗口内没有变化（缺首帧）")
            continue
        change["after_anchor_ms"] = [round((change["prev_t_s"] - anchor) * 1000, 3),
                                     round((change["t_s"] - anchor) * 1000, 3)]
        report[key] = change
    if "client" in report and "attr20" in report:
        report["attr20_minus_client_ms"] = [round((report["attr20"]["prev_t_s"] - report["client"]["t_s"]) * 1000, 3),
                                            round((report["attr20"]["t_s"] - report["client"]["prev_t_s"]) * 1000, 3)]
    return report


def _bool(value):
    return {"true": True, "false": False}.get(str(value).lower())


def split_switches(events):
    requests = [index for index, event in enumerate(events) if event.name == "pref.request"]
    switches = []
    for order, index in enumerate(requests):
        start = requests[order - 1] + 1 if order else 0
        end = requests[order + 1] if order + 1 < len(requests) else len(events)
        request = events[index]
        target_dark = {"DARK": True, "LIGHT": False}.get(request.fields.get("mode"))
        stages = {"pref.request": request}
        for event in events[start:index]:
            if event.name in ("input.press", "input.release"):
                stages[event.name] = event
        dwm_sets, decodes = [], []
        for event in events[index + 1:end]:
            if event.name in ("theme.palette_built", "theme.applied") and target_dark is not None \
                    and _bool(event.fields.get("dark")) != target_dark:
                continue
            if event.name in _AFTER_APPLY and "theme.applied" not in stages:
                continue
            if event.name in _AFTER_PALETTE and "theme.palette_built" not in stages:
                continue
            if event.name == "dwm.set_end":
                dwm_sets.append(event)
            if event.name == "prefs.decode" and "frame.draw" not in stages:
                decodes.append(event)
            stages.setdefault(event.name, event)
        switches.append({"index": order, "target_dark": target_dark, "stages": stages,
                         "dwm_sets": dwm_sets, "decodes": decodes})
    return switches


def _qpc_ns(ticks, freq):
    return ticks * 1_000_000_000 // freq


def clock_offset(sync):
    """jvm_nano = qpc_ns - offset; error bound is half the bracketing window."""
    middle = (sync["jvm_nano_before"] + sync["jvm_nano_after"]) // 2
    return _qpc_ns(sync["qpc_ticks"], sync["qpc_freq"]) - middle, (sync["jvm_nano_after"] - sync["jvm_nano_before"]) // 2


def check_switch(switch, findings: Findings, label: str):
    stages = switch["stages"]
    present = [(name, stages[name]) for name, _ in CHAIN if name in stages]
    for name, required in CHAIN:
        if required and name not in stages:
            text = "缺首帧（新 palette apply 后没有根绘制）" if name == "frame.draw" else f"缺必需阶段 {name}"
            findings.add(EXIT_MISSING, f"{label}: {text}")
    for (a_name, a), (b_name, b) in zip(present, present[1:]):
        if b.t < a.t:
            findings.add(EXIT_ORDER, f"{label}: 因果乱序 {b_name}(行{b.line}) 早于 {a_name}(行{a.line}) "
                                     f"{_ms(a.t - b.t)} ms")
    start, end = stages.get("dwm.set_start"), stages.get("dwm.set_end")
    if start and end and end.t < start.t:
        findings.add(EXIT_ORDER, f"{label}: dwm.set_end 早于 dwm.set_start")
    palette = stages.get("theme.palette_built")
    if start and palette and start.t < palette.t:
        findings.add(EXIT_ORDER, f"{label}: DWM 设置早于新 palette 构建")
    nxt, applied = stages.get("frame.next"), stages.get("theme.applied")
    if nxt and applied and nxt.t < applied.t:
        findings.add(EXIT_ORDER, f"{label}: frame.next 早于 theme.applied")


def summarise_switch(switch):
    stages = switch["stages"]
    t = {name: event.t for name, event in stages.items()}
    origin = t.get("input.release", t.get("pref.request"))
    out = {"index": switch["index"], "target_dark": switch["target_dark"],
           "entry": stages["pref.request"].fields.get("entry"),
           "threads": {name: event.fields.get("thread") for name, event in stages.items()},
           "marks_ms": {name: _ms(value - origin) for name, value in sorted(t.items(), key=lambda kv: kv[1])},
           "segments_ms": [], "dwm_sets": []}
    for a, b, label in SEGMENTS:
        if a in t and b in t:
            out["segments_ms"].append({"from": a, "to": b, "label": label, "ms": _ms(t[b] - t[a])})
    palette = stages.get("theme.palette_built")
    if palette and "dur_ns" in palette.fields:
        out["palette_build_ms"] = _ms(int(palette.fields["dur_ns"]))
    edt = [e for e in switch["decodes"] if "AWT-EventQueue" in e.fields.get("thread", "")]
    out["edt_decode"] = {"count": len(edt), "ms": _ms(sum(int(e.fields.get("dur_ns", 0)) for e in edt))}
    target_light = None if switch["target_dark"] is None else not switch["target_dark"]
    for event in switch["dwm_sets"]:
        out["dwm_sets"].append({"ms": _ms(event.t - origin), "light": _bool(event.fields.get("light")),
                                "target": _bool(event.fields.get("light")) == target_light,
                                "thread": event.fields.get("thread")})
    if "frame.draw" in t:
        out["click_to_first_draw_ms"] = _ms(t["frame.draw"] - origin)
        out["origin"] = "input.release" if "input.release" in t else "pref.request"
    return out


def parse_presentmon(path: Path):
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(row for row in handle if not row.startswith("//"))
        if not reader.fieldnames:
            raise ProbeError(f"{path.name}: PresentMon CSV 没有表头")
        names = {name.strip(): name for name in reader.fieldnames}
        for need in ("ProcessID", "CPUStartQPC", "MsCPUBusy", "MsInPresentAPI"):
            if need not in names:
                raise ProbeError(f"{path.name}: PresentMon 缺列 {need}（需 2.x 默认指标加 --qpc_time，不要 --v1_metrics）")
        rows = []
        for number, row in enumerate(reader, 2):
            try:
                displayed = row.get(names.get("MsUntilDisplayed", ""), "")
                rows.append({"pid": int(row[names["ProcessID"]]), "qpc": int(row[names["CPUStartQPC"]]),
                             "cpu_busy_ms": float(row[names["MsCPUBusy"]]),
                             "in_present_ms": float(row[names["MsInPresentAPI"]]),
                             "until_displayed_ms": float(displayed) if displayed not in ("", "NA", None) else None})
            except (TypeError, ValueError):
                raise ProbeError(f"{path.name}:{number}: PresentMon 行无法解析") from None
    return rows


def analyse(args, findings: Findings):
    report = {"samples_only": [], "switches": []}
    if not args.trace:
        for sample in args.samples:
            report["samples_only"].append(analyse_samples_only(sample, findings))
        return report
    header, sync, events = parse_trace(args.trace)
    switches = split_switches(events)
    if not switches:
        findings.add(EXIT_MISSING, f"{args.trace.name}: 没有 pref.request，无法切出主题切换")
    offset = error = None
    if sync:
        offset, error = clock_offset(sync)
        report["clock_sync"] = {"offset_ns": offset, "error_ns": error, "qpc_freq": sync["qpc_freq"]}
    summaries = []
    for switch in switches:
        label = f"切换#{switch['index']}"
        check_switch(switch, findings, label)
        summaries.append(summarise_switch(switch))
    if args.presentmon:
        if not sync:
            findings.add(EXIT_CLOCK, "PresentMon 是 QPC 时钟，但 trace 没有 clock_sync，无法对齐")
        else:
            pid = _int(header.get("pid"), f"{args.trace.name}: pid")
            rows = [row for row in parse_presentmon(args.presentmon) if row["pid"] == pid]
            # A frame whose CPU work began before the draw can still carry it: select by the Present() call.
            calls = sorted(((_qpc_ns(row["qpc"], sync["qpc_freq"]) - offset + round(row["cpu_busy_ms"] * 1e6), row)
                            for row in rows), key=lambda item: item[0])
            for switch, summary in zip(switches, summaries):
                draw = switch["stages"].get("frame.draw")
                if not draw:
                    continue
                after = [(call, row) for call, row in calls if call >= draw.t]
                if not after:
                    code = EXIT_MISSING if args.require_present else EXIT_OK
                    findings.add(code, f"切换#{switch['index']}: PresentMon 里该 pid 在首帧后没有 present 调用（未观测）")
                    continue
                call, first = after[0]
                to_call = _ms(call - draw.t)
                summary["present"] = {"draw_to_present_call_ms": to_call,
                                      "draw_to_present_return_ms": round(to_call + first["in_present_ms"], 3),
                                      "draw_to_displayed_ms": None if first["until_displayed_ms"] is None
                                      else round(to_call + first["until_displayed_ms"], 3)}
    for sample in args.samples:
        meta, rows = parse_samples(sample)
        if meta.get("clock") != "qpc" or not sync:
            missing = "采样器" if meta.get("clock") != "qpc" else "trace"
            findings.add(EXIT_CLOCK, f"{sample.name}: 跨时钟且无同步点（{missing}缺 QPC 基准），不合并到应用时间线")
            continue
        freq, base = _int(meta.get("qpc_freq"), sample.name), _int(meta.get("qpc_base_ticks"), sample.name)
        if freq != sync["qpc_freq"]:
            findings.add(EXIT_CLOCK, f"{sample.name}: 采样器 QPC 频率 {freq} 与 trace {sync['qpc_freq']} 不一致")
            continue
        to_jvm = lambda t_s: _qpc_ns(base, freq) + round(t_s * 1e9) - offset
        anchor = meta.get("mouse_up_t_s", meta.get("mouse_down_t_s"))
        if anchor is None:
            findings.add(EXIT_MISSING, f"{sample.name}: 没有鼠标时间，无法关联到切换")
            continue
        anchor_jvm = to_jvm(anchor)
        match = next((s for s in zip(switches, summaries)
                      if s[0]["stages"]["pref.request"].t >= anchor_jvm - error), None)
        if match is None:
            findings.add(EXIT_MISSING, f"{sample.name}: 鼠标时间之后没有 pref.request")
            continue
        switch, summary = match
        stages = switch["stages"]
        origin = stages.get("input.release", stages["pref.request"]).t
        merged = {"file": sample.name, "anchor_minus_origin_ms": _ms(anchor_jvm - origin)}
        for key, pick in (("client", lambda r: r[2]), ("attr20", lambda r: r[1])):
            change = first_change(rows, pick)
            if change is None:
                if key == "client":
                    findings.add(EXIT_MISSING, f"{sample.name}: 客户区像素无变化（缺首帧）")
                continue
            low, high = to_jvm(change["prev_t_s"]), to_jvm(change["t_s"])
            merged[key] = {"after_origin_ms": [_ms(low - origin), _ms(high - origin)]}
            ref = stages.get("frame.draw") if key == "client" else stages.get("dwm.set_start")
            if ref and high + error < ref.t:
                what = "应用首帧" if key == "client" else "DWM 设置"
                findings.add(EXIT_ORDER, f"{sample.name}: {key} 采样变化早于{what} {_ms(ref.t - high)} ms"
                                         "（关联/时钟错误，或变化来自别的表面）")
            elif ref:
                merged[key]["after_ref_ms"] = [_ms(low - ref.t), _ms(high - ref.t)]
        summary.setdefault("samples", []).append(merged)
    report["switches"] = summaries
    return report


def render(report, findings: Findings):
    lines = []
    for item in report["samples_only"]:
        lines.append(f"[采样] {item['file']} 锚点={item['anchor']} 时钟={item['clock']}")
        for key in ("client", "attr20", "caption"):
            if key in item:
                low, high = item[key]["after_anchor_ms"]
                lines.append(f"  {key:<7} 变化于锚点后 ({low}, {high}] ms，稳定于 t_s={item[key]['settle_t_s']}")
        if "attr20_minus_client_ms" in item:
            lines.append(f"  attr20 相对客户区 {item['attr20_minus_client_ms']} ms")
    if "clock_sync" in report:
        sync = report["clock_sync"]
        lines.append(f"[时钟] jvm↔QPC 偏移 {sync['offset_ns']} ns，误差 ±{sync['error_ns']} ns")
    for item in report["switches"]:
        lines.append(f"[切换#{item['index']}] entry={item['entry']} dark={item['target_dark']} "
                     f"起点={item.get('origin', 'pref.request')} 首帧={item.get('click_to_first_draw_ms')} ms")
        for segment in item["segments_ms"]:
            lines.append(f"  {segment['ms']:>10} ms  {segment['label']}  [{segment['from']}→{segment['to']}]")
        if "palette_build_ms" in item:
            lines.append(f"  palette 构建自测 {item['palette_build_ms']} ms；EDT 解码 {item['edt_decode']}")
        for dwm in item["dwm_sets"]:
            lines.append(f"  DWM attr20 light={dwm['light']} 于 {dwm['ms']} ms target={dwm['target']} 线程={dwm['thread']}")
        if "present" in item:
            lines.append(f"  present {item['present']}")
        for merged in item.get("samples", []):
            lines.append(f"  采样 {merged}")
    for code, text in findings.notes:
        lines.append(f"[{'提示' if code == EXIT_OK else f'退出{code}'}] {text}")
    lines.append(f"exit={findings.code}")
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--samples", type=Path, action="append", default=[])
    parser.add_argument("--presentmon", type=Path)
    parser.add_argument("--require-present", action="store_true", help="没有 present 记录时按缺阶段（3）处理")
    parser.add_argument("--json", type=Path, help="同时写出机器可读报告")
    try:
        args = parser.parse_args(argv)
    except SystemExit as error:
        return EXIT_OK if error.code == 0 else EXIT_USAGE
    if not args.trace and not args.samples:
        print("需要 --trace 或 --samples", file=sys.stderr)
        return EXIT_USAGE
    if args.presentmon and not args.trace:
        print("--presentmon 需要 --trace 提供 pid 与 QPC 频率", file=sys.stderr)
        return EXIT_USAGE
    findings = Findings()
    try:
        report = analyse(args, findings)
    except (OSError, ProbeError) as error:
        print(f"解析失败：{error}", file=sys.stderr)
        return EXIT_USAGE
    report["exit"] = findings.code
    report["findings"] = [{"code": code, "text": text} for code, text in findings.notes]
    print(render(report, findings))
    if args.json:
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return findings.code


if __name__ == "__main__":
    raise SystemExit(main())

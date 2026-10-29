# Windows real-machine measurements (TON-998 U1)

Scripts that turn the "体验完美" acceptance table into numbers on the real machine
(winbox: 5120×2160, 165 Hz, 150 % scaling, RTX 5080). Everything runs over `ssh winbox`
and writes only under `C:\Users\TONYS\bp-mac-verify\`.

| Acceptance item | Script | What it produces |
|---|---|---|
| 4 — scroll frame time | `Measure-FrameTime.ps1` | PresentMon CSV + p50/p95/p99 frame time, dropped-frame share |
| 21 — click card → picture | `Measure-ClickToFrame.ps1` | 20 launches, click → first changed frame on screen |
| 22 — cold start | `Measure-ColdStart.ps1` | 5 launches, launch → window focus / first presented frame |
| 23 — long-run stability | `Measure-Soak.ps1` | crash / hang detection, private-memory growth |
| 23 — idle private memory | `Measure-Idle.ps1` | 60 s idle sampling of private bytes |
| 24 — minimized CPU, PAUSE | `Measure-Idle.ps1` | CPU after minimizing; effect of `tools\PAUSE` |
| — | `Test-PauseGate.ps1` | proves the scripts stop when `tools\PAUSE` exists |
| — | `Run-All.ps1` | runs the set, one summary per step |
| — | `Build-BaselinePackage.ps1` | builds the portable Windows package and stages it under `apps\` |

## Two rules that shape the design

**1. `tools\PAUSE` wins.** Every script checks it before starting and again inside each
loop; when it appears the script stops and exits `3`, and the client it started is stopped.
`Test-PauseGate.ps1` demonstrates exactly this.

**2. The measurement must run inside the desktop session.** ssh lands in session 0
(services); the desktop is session 1, and window enumeration plus input only reach windows
of the session the process runs in. A measurement started from ssh sees no window at all.
So the ssh side only writes `measure\job.json` and starts the `BiliPai-U1Measure` scheduled
task, which runs `Invoke-BpJob.ps1` in the console session:

```
# on winbox, as the desktop user (task: interactive, highest)
schtasks /create /tn BiliPai-U1Measure /tr "powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\TONYS\bp-mac-verify\measure\Invoke-BpJob.ps1" /sc once /st 00:00 /f
# from ssh
#   measure\job.json  {"script":"Run-All.ps1","args":["-Tag","run1"]}
#   Start-ScheduledTask BiliPai-U1Measure
#   measure\job-result.json  { exitCode, ok, childLog }
```

The task is registered `RunLevel Highest` because PresentMon needs an ETW session; the ssh
account is already administrator, so nothing is escalated and no group membership changes.

## Hard rules these scripts enforce

* **The client is only started through the `BiliPai-MacTest` scheduled task**
  (`tools\launch.ps1`, TON-988). No script runs the exe directly.
* **The machine is left alone otherwise.** A foreground fullscreen non-shell window, or
  another process holding the GPU above 30 % for 10 s, makes the run refuse (exit `4`) —
  the same gates as `launch.ps1`.
* **Only the client this run started is stopped.** The staged exe is a launcher stub that
  exits and orphans the JVM, so the client is identified by image name plus "started at or
  after this launch" — a client from an earlier run is never touched.
* **Input reaches the window or the run fails.** Posted window messages are ignored by the
  Compose surface, so input goes through `mouse_event`; the window has to be foreground
  (`GetForegroundWindow` verified, topmost round-trip as the usual trick) and the pointer
  stays inside the feed for the whole scroll. A cursor that keeps leaving drops wheel events
  silently — that produced five runs with zero movement before it was found.
* **A scroll run proves it scrolled.** Three horizontal bands of the card grid are sampled
  every 500 ms; a sample counts when at least two bands changed inside a 1.5 s window. A
  hover preview animates one card and cannot reach two bands. The same-sample version of
  this rule scored 0.65-0.89 on runs that were visibly scrolling, so it was replaced.
* **A picture is proven to be playing.** Click-to-picture and soak require sustained motion
  in the centre of the window (15 of the last 20 one-frame steps), which a page transition
  or a spinner cannot sustain.
* **Failures stay in the numbers.** A launch that never shows a picture is a timeout;
  percentiles are reported over every attempt with the timeout entering as "at least the
  observation bound", and the smaller detected-only view is labelled separately.
* **I/O comes from CIM.** `Get-Process` exposes no I/O counters on this platform and the
  missing values were silently rounded to zero. `Win32_Process` provides them; when they
  cannot be read the pause verdict says "not verified" instead of guessing.

## Requirements

* `measure\bin\PresentMon.exe` — PresentMon 2.6.0 single file
  (`PresentMon-2.6.0-x64.exe` from the GameTechDev/PresentMon release, SHA-256
  `B2A706BC6AD475749E3B7E3409263AA1E6906D45BDCF993F6DBC0F660188F1AF`). No install, no
  service, no Performance Log Users group needed.
* The app under test staged at `C:\Users\TONYS\bp-mac-verify\apps\`
  (`Build-BaselinePackage.ps1` builds it from a clean checkout of the desktop baseline).
* Windows PowerShell 5.1 is enough; the build is run through `cmd` because
  `desktop/tools/build.ps1` is written for `pwsh` and 5.1 turns native stderr into
  terminating errors.

## Running

```
ssh winbox "powershell -NoProfile -Command \"Start-ScheduledTask BiliPai-U1Measure\""
```

`Run-All.ps1 -Quick` shortens every step for a harness self check. The default set is the
real baseline: 5 cold starts, 20 clicks, 30 s scroll, idle + minimized CPU, 30 min soak —
about 50 minutes of machine time, so it needs a free machine.

## Output

`measure\data\<script>-<stamp>\` holds the raw PresentMon CSV plus `summary.json`; logs land
in `measure\logs\`. The summary JSON is what a report quotes; the CSV is what a reviewer
re-computes from.

## Known limits (recorded, not hidden)

* `tools\launch.ps1` clamps `max_seconds` at 3600 (`if ($maxSec -gt 3600) { $maxSec = 3600 }`)
  and stops the client at that point, so one soak cannot exceed ~55 minutes. `Measure-Soak.ps1`
  reads that clamp from the launcher, refuses longer runs in seconds (`exit 6`) and records
  `launcher_clamp_seconds` in the summary; `-PreflightOnly` checks the duration without
  starting the client. Longest delivered run so far: 55 minutes. The 8 hour night run needs
  the clamp raised by the machine owner — after that the same command works unchanged.
* The client itself does not read `tools\PAUSE` (only the launcher does). `Measure-Idle.ps1`
  measures CPU and I/O across foreground / minimized / pause / recovery and reports
  `pause_verdict`; on the current build the background I/O is already near zero when
  minimized, so the verdict is "not verified" rather than a claim either way.
* "Home page usable" is measured as: window visible, keyboard focus held, window answering
  `WM_NULL`. The final human judgement still belongs to the acceptance pass (R1).
* PresentMon occasionally writes no CSV for the per-click captures while it does for the
  scroll capture; those two items therefore use the on-screen change as the primary number
  and treat the frame capture as a cross-check (`method=screen_only`).

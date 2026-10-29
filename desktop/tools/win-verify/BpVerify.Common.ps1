# Shared helpers for the TON-998 U1 real-machine measurements (Windows PowerShell 5.1).
# Every measurement script dot-sources this file.
#
# Rules encoded here, all from TON-998 and the workspace operating rules:
#   * tools\PAUSE present  -> the measurement refuses to run (exit 3) and logs the reason.
#   * the client is started ONLY through the BiliPai-MacTest scheduled task.
#   * the machine is left alone when a fullscreen non-shell window is in front or another
#     process keeps the GPU busy (same gates as tools\launch.ps1).
#   * only the exact PID this run started is ever stopped.

$script:BpRoot = 'C:\Users\TONYS\bp-mac-verify'
if ($env:BP_VERIFY_ROOT) { $script:BpRoot = $env:BP_VERIFY_ROOT }
$script:BpVerify = Join-Path $script:BpRoot 'measure'
$script:BpLogDir = Join-Path $script:BpVerify 'logs'
$script:BpDataDir = Join-Path $script:BpVerify 'data'
$script:BpPresentMon = Join-Path $script:BpVerify 'bin\PresentMon.exe'
$script:BpAppName = 'BiliPai Windows'
$script:BpAppExeName = 'BiliPai Windows.exe'
New-Item -ItemType Directory -Force -Path $script:BpLogDir, $script:BpDataDir | Out-Null

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Text;
using System.Drawing;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class BpMeasure {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct MONITORINFO { public int cbSize; public RECT rcMonitor; public RECT rcWork; public uint dwFlags; }
  delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT p);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; public POINT(int x, int y) { X = x; Y = y; } }
  [DllImport("user32.dll")] public static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
  [DllImport("user32.dll")] public static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO mi);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
  public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1), HWND_NOTOPMOST = new IntPtr(-2);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, IntPtr w, IntPtr l, uint flags, uint timeout, out IntPtr result);
  [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, int dx, int dy, int data, UIntPtr extra);
  public const uint MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004, MOUSEEVENTF_WHEEL = 0x0800;

  // 32x20 greyscale thumbnail of the window, or of a sub-rectangle given as fractions of it.
  // The inner region is used to tell a playing video (large area changes) from a spinner or a
  // hover highlight (small area changes).
  public static byte[] Thumb(IntPtr hwnd) { return ThumbRegion(hwnd, 0.0, 0.0, 1.0, 1.0); }
  public static byte[] ThumbRegion(IntPtr hwnd, double fx0, double fy0, double fx1, double fy1) {
    RECT r; GetWindowRect(hwnd, out r);
    int w = (int)((r.Right - r.Left) * (fx1 - fx0));
    int h = (int)((r.Bottom - r.Top) * (fy1 - fy0));
    int ox = r.Left + (int)((r.Right - r.Left) * fx0);
    int oy = r.Top + (int)((r.Bottom - r.Top) * fy0);
    if (w <= 0 || h <= 0) return null;
    using (Bitmap full = new Bitmap(w, h))
    using (Graphics sg = Graphics.FromImage(full)) {
      sg.CopyFromScreen(ox, oy, 0, 0, new Size(w, h), CopyPixelOperation.SourceCopy);
      using (Bitmap small = new Bitmap(32, 20))
      using (Graphics gg = Graphics.FromImage(small)) {
        gg.DrawImage(full, 0, 0, 32, 20);
        byte[] bytes = new byte[32 * 20];
        for (int y = 0; y < 20; y++) for (int x = 0; x < 32; x++) {
          Color c = small.GetPixel(x, y);
          bytes[y * 32 + x] = (byte)((c.R + c.G + c.B) / 3);
        }
        return bytes;
      }
    }
  }

  public const uint WM_MOUSEMOVE = 0x0200, WM_LBUTTONDOWN = 0x0201, WM_LBUTTONUP = 0x0202, WM_MOUSEWHEEL = 0x020A;
  public static string ClassOf(IntPtr w) { StringBuilder sb = new StringBuilder(256); GetClassName(w, sb, 256); return sb.ToString(); }
  public static List<IntPtr> WindowsOfPid(uint pid) {
    List<IntPtr> res = new List<IntPtr>();
    EnumWindows(delegate(IntPtr h, IntPtr l) { uint p; GetWindowThreadProcessId(h, out p); if (p == pid && IsWindowVisible(h)) res.Add(h); return true; }, IntPtr.Zero);
    return res;
  }
  public static IntPtr LargestWindowOfPid(uint pid) {
    IntPtr best = IntPtr.Zero; long bestArea = 0;
    foreach (IntPtr h in WindowsOfPid(pid)) {
      RECT r; GetWindowRect(h, out r);
      long area = (long)(r.Right - r.Left) * (r.Bottom - r.Top);
      if (area > bestArea) { bestArea = area; best = h; }
    }
    return best;
  }
  public static bool IsFullscreen(IntPtr w) {
    RECT r; GetWindowRect(w, out r);
    IntPtr m = MonitorFromWindow(w, 2);
    MONITORINFO mi = new MONITORINFO(); mi.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
    if (!GetMonitorInfo(m, ref mi)) return false;
    return r.Left <= mi.rcMonitor.Left && r.Top <= mi.rcMonitor.Top && r.Right >= mi.rcMonitor.Right && r.Bottom >= mi.rcMonitor.Bottom;
  }
  // WM_NULL with a timeout: a hung (not responding) window returns 0.
  public static bool Responds(IntPtr h) { IntPtr r; return SendMessageTimeout(h, 0, IntPtr.Zero, IntPtr.Zero, 2, 1000, out r) != IntPtr.Zero; }
}
'@

[void][BpMeasure]::SetProcessDPIAware()

function Get-BpScreenThumb {
    param([IntPtr]$Hwnd)
    return [BpMeasure]::Thumb($Hwnd)
}

# Centre of the window: where a player draws, and where a spinner occupies only a small part.
function Get-BpScreenThumbCenter {
    param([IntPtr]$Hwnd)
    return [BpMeasure]::ThumbRegion($Hwnd, 0.25, 0.25, 0.75, 0.75)
}

# A horizontal strip across the first row of the card grid. Scrolling replaces the cards in
# that strip, while a hover preview only animates the one card under the pointer, so strip
# motion is the part that actually means "the list moved".
function Get-BpScreenThumbStrip {
    param([IntPtr]$Hwnd, [double]$Y0 = 0.30, [double]$Y1 = 0.42)
    return [BpMeasure]::ThumbRegion($Hwnd, 0.22, $Y0, 0.78, $Y1)
}

# Three bands of the grid. The page header and banner do not scroll, so a single band can sit
# on a fixed element and report "no movement" while the list is scrolling fine.
function Get-BpScrollBands {
    param([IntPtr]$Hwnd)
    return @(
        [BpMeasure]::ThumbRegion($Hwnd, 0.22, 0.18, 0.78, 0.30),
        [BpMeasure]::ThumbRegion($Hwnd, 0.22, 0.34, 0.78, 0.46),
        [BpMeasure]::ThumbRegion($Hwnd, 0.22, 0.52, 0.78, 0.64)
    )
}

# Mean absolute difference of two thumbnails, 0 (identical) .. 255.
function Compare-BpThumb {
    param($A, $B)
    if (-not $A -or -not $B -or $A.Length -ne $B.Length) { return $null }
    $sum = 0
    for ($i = 0; $i -lt $A.Length; $i++) { $sum += [math]::Abs([int]$A[$i] - [int]$B[$i]) }
    return [math]::Round($sum / $A.Length, 1)
}

# Number of thumbnail cells that changed. Used next to the mean difference: during a normal
# clip the mean is 19-192 and the cells 400-640, but around a stall the mean collapses to 0
# while the frame is still on screen, so the two together separate "moved a little" from
# "did not move at all".
function Compare-BpThumbCells {
    param($A, $B, [int]$Threshold = 6)
    if (-not $A -or -not $B -or $A.Length -ne $B.Length) { return $null }
    $n = 0
    for ($i = 0; $i -lt $A.Length; $i++) {
        if ([math]::Abs([int]$A[$i] - [int]$B[$i]) -ge $Threshold) { $n++ }
    }
    return $n
}

function Get-BpClientPointOnScreen {
    param([IntPtr]$Hwnd, [int]$X, [int]$Y)
    $pt = New-Object BpMeasure+POINT -ArgumentList $X, $Y
    [void][BpMeasure]::ClientToScreen($Hwnd, [ref]$pt)
    return $pt
}

# Real mouse input. Posted window messages are ignored by the Compose/Skiko surface, so the
# pointer is moved to the target, the event is injected, and the pointer is put back.
# The pointer has to stay inside the window for a scroll run: a wheel event is routed to the
# window under the cursor when it is processed, and a cursor that keeps leaving drops the
# scroll entirely (measured: identical frames for 30 s with the old per-event restore).
function Move-BpCursorIntoWindow {
    param([IntPtr]$Hwnd, [double]$XRatio = 0.55, [double]$YRatio = 0.5)
    $rect = New-Object BpMeasure+RECT
    [void][BpMeasure]::GetClientRect($Hwnd, [ref]$rect)
    $pt = Get-BpClientPointOnScreen -Hwnd $Hwnd -X ([int]($rect.Right * $XRatio)) -Y ([int]($rect.Bottom * $YRatio))
    [void][BpMeasure]::SetCursorPos($pt.X, $pt.Y)
    Start-Sleep -Milliseconds 150
    return $pt
}

# Keyboard injection for the focused window; used to resume a paused player without
# clicking on screen, where a stray click can hit a control that closes the page.
function Send-BpKey {
    param([byte]$VirtualKey = 0x20, [int]$HoldMs = 60)
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class BpKeyNative {
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
}
'@ -ErrorAction SilentlyContinue
    [BpKeyNative]::keybd_event($VirtualKey, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $HoldMs
    [BpKeyNative]::keybd_event($VirtualKey, 0, 2, [UIntPtr]::Zero)
}

function Save-BpCursor {
    $p = New-Object BpMeasure+POINT
    [void][BpMeasure]::GetCursorPos([ref]$p)
    return $p
}

function Restore-BpCursor {
    param($Point)
    if ($Point) { [void][BpMeasure]::SetCursorPos($Point.X, $Point.Y) }
}

# Cursor must already be parked inside the window (Move-BpCursorIntoWindow).
function Send-BpWheelReal {
    param([IntPtr]$Hwnd, [int]$Notches = 3, [switch]$Up, [switch]$KeepCursor)
    $saved = $null
    if (-not $KeepCursor) {
        $saved = Save-BpCursor
        [void](Move-BpCursorIntoWindow -Hwnd $Hwnd)
    }
    # Windows: a positive wheel delta scrolls up, negative scrolls down. The feed starts at the
    # top, so the default has to be "down" - the old positive default scrolled up into nothing
    # and every run looked like "no movement" although the events were delivered.
    $delta = -120 * $Notches
    if ($Up) { $delta = -$delta }
    [BpMeasure]::mouse_event([BpMeasure]::MOUSEEVENTF_WHEEL, 0, 0, $delta, [UIntPtr]::Zero)
    if (-not $KeepCursor) {
        Start-Sleep -Milliseconds 80
        Restore-BpCursor -Point $saved
    }
}

function Send-BpClickReal {
    param([IntPtr]$Hwnd, [int]$X, [int]$Y)
    $pt = Get-BpClientPointOnScreen -Hwnd $Hwnd -X $X -Y $Y
    $saved = New-Object BpMeasure+POINT
    [void][BpMeasure]::GetCursorPos([ref]$saved)
    [void][BpMeasure]::SetCursorPos($pt.X, $pt.Y)
    Start-Sleep -Milliseconds 120
    [BpMeasure]::mouse_event([BpMeasure]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 90
    [BpMeasure]::mouse_event([BpMeasure]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    [void][BpMeasure]::SetCursorPos($saved.X, $saved.Y)
    return (Get-Date).ToUniversalTime()
}

# Real input goes to whatever is in front, so the client has to be foreground and stay there.
# SetForegroundWindow alone is frequently refused; the topmost round-trip is the accepted
# trick, and the result is verified instead of assumed.
function Set-BpForeground {
    param([IntPtr]$Hwnd, [int]$TimeoutMs = 6000)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        if ([BpMeasure]::GetForegroundWindow() -eq $Hwnd) { return $true }
        [void][BpMeasure]::ShowWindow($Hwnd, 9)          # SW_RESTORE
        [void][BpMeasure]::SetWindowPos($Hwnd, [BpMeasure]::HWND_TOPMOST, 0, 0, 0, 0, 0x0001 -bor 0x0002 -bor 0x0040)
        [void][BpMeasure]::SetWindowPos($Hwnd, [BpMeasure]::HWND_NOTOPMOST, 0, 0, 0, 0, 0x0001 -bor 0x0002 -bor 0x0040)
        [void][BpMeasure]::BringWindowToTop($Hwnd)
        [void][BpMeasure]::SetForegroundWindow($Hwnd)
        Start-Sleep -Milliseconds 300
    }
    Write-BpLog ("window 0x{0:x} did not become foreground (foreground=0x{1:x})" -f [int64]$Hwnd, [int64][BpMeasure]::GetForegroundWindow())
    return $false
}

function Test-BpForeground {
    param([IntPtr]$Hwnd)
    return ([BpMeasure]::GetForegroundWindow() -eq $Hwnd)
}

function Write-BpLog {
    param([string]$Message, [string]$Path = $script:BpLog)
    $line = ('{0} {1}' -f (Get-Date).ToString('HH:mm:ss.fff'), $Message)
    Write-Host $line
    if ($Path) { Add-Content -LiteralPath $Path -Value $line -Encoding utf8 }
}

function Initialize-BpRun {
    param([string]$Name)
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $script:BpLog = Join-Path $script:BpLogDir ("$Name-$stamp.log")
    $script:BpRunStamp = $stamp
    $script:BpDataRun = Join-Path $script:BpDataDir ("$Name-$stamp")
    New-Item -ItemType Directory -Force -Path $script:BpDataRun | Out-Null
    Write-BpLog "== $Name run $stamp ==" 
    Write-BpLog ("host=" + $env:COMPUTERNAME + " user=" + $env:USERNAME + " root=" + $script:BpRoot)
    return $script:BpDataRun
}

function Get-BpAppExe {
    $apps = Join-Path $script:BpRoot 'apps'
    $exe = Get-ChildItem -LiteralPath $apps -Filter $script:BpAppExeName -Recurse -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $exe) { throw "no $script:BpAppExeName under $apps" }
    return $exe.FullName
}

function Test-BpPause { return (Test-Path -LiteralPath (Join-Path $script:BpRoot 'tools\PAUSE')) }

# Window enumeration and posted input only reach windows of the session this process runs
# in. ssh lands in session 0 (services), the desktop is session 1, so a measurement started
# from ssh would see no window at all. Refuse instead of reporting zeros.
function Test-BpInteractiveSession {
    $mine = (Get-Process -Id $PID).SessionId
    $console = [BpMeasure]::WTSGetActiveConsoleSessionId()
    if ($mine -ne $console) {
        Write-BpLog ("not in the interactive session (this={0}, console={1}); run the measurement through the BiliPai-U1Measure task" -f $mine, $console)
        return $false
    }
    return $true
}

# The staged exe is a small launcher stub that starts the JVM and then exits, which leaves
# the real client orphaned: walking ParentProcessId from the stub finds nothing. The client
# processes are therefore identified by image name plus "started at or after this launch",
# which never matches a client this run did not start (nothing else on the machine runs it).
function Get-BpAppProcesses {
    param([int]$RootPid = 0, [datetime]$Since = [datetime]::MinValue)
    $list = New-Object System.Collections.ArrayList
    if ($RootPid -gt 0) {
        $root = Get-Process -Id $RootPid -ErrorAction SilentlyContinue
        if ($root) { [void]$list.Add($root) }
    }
    foreach ($p in (Get-Process -Name $script:BpAppName -ErrorAction SilentlyContinue)) {
        if ($list -contains $p) { continue }
        $started = $p.StartTime
        if ($started -ge $Since.AddSeconds(-2)) { [void]$list.Add($p) }
    }
    return $list
}

# Returns $true when it is safe to take the machine for a measured run.
function Test-BpMachineQuiet {
    param([int]$GpuBusySeconds = 10, [int]$GpuThreshold = 30)
    $fg = [BpMeasure]::GetForegroundWindow()
    $fgPid = [uint32]0; [void][BpMeasure]::GetWindowThreadProcessId($fg, [ref]$fgPid)
    $fgProc = 'unknown'; try { $fgProc = (Get-Process -Id $fgPid -ErrorAction Stop).ProcessName } catch { }
    $cls = [BpMeasure]::ClassOf($fg)
    $shell = (@('Progman', 'WorkerW', 'Shell_TrayWnd') -contains $cls) -or
        (@('explorer', 'msedge', 'chrome', 'firefox', 'brave', 'opera') -contains $fgProc)
    if ([BpMeasure]::IsFullscreen($fg) -and -not $shell) {
        Write-BpLog "gate: foreground fullscreen non-shell window ($fgProc) - refusing"
        return $false
    }
    $nvs = 'C:\WINDOWS\system32\nvidia-smi.exe'
    if (Test-Path $nvs) {
        $busy = 0
        for ($i = 0; $i -lt $GpuBusySeconds; $i++) {
            $u = 0
            try { $u = [int](((& $nvs --query-gpu=utilization.gpu --format=csv,noheader,nounits) | Select-Object -First 1).Trim()) } catch { }
            if ($u -gt $GpuThreshold) { $busy++ }
            Start-Sleep -Seconds 1
        }
        $plain = (& $nvs) -join "`n"
        $names = @([regex]::Matches($plain, '([A-Za-z0-9_.\-]+\.exe)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique |
            Where-Object { $_ -notmatch '^BiliPai' })
        if ($busy -eq $GpuBusySeconds -and $names.Count -gt 0) {
            Write-BpLog ("gate: GPU above {0}% for {1}s with other processes: {2} - refusing" -f $GpuThreshold, $GpuBusySeconds, ($names -join ', '))
            return $false
        }
    }
    return $true
}

# Start the client through the BiliPai-MacTest scheduled task and return its PID.
# The request/result contract belongs to tools\launch.ps1 (TON-988); this never
# starts the exe directly.
function Start-BpAppViaTask {
    param([string]$Exe, [string[]]$Arguments = @(), [int]$MaxSeconds = 120)
    $tools = Join-Path $script:BpRoot 'tools'
    $reqPath = Join-Path $tools 'request.json'
    $resPath = Join-Path $tools 'result.json'
    Remove-Item -LiteralPath $resPath -Force -ErrorAction SilentlyContinue
    $req = [ordered]@{ exe = $Exe; max_seconds = $MaxSeconds }
    if ($Arguments.Count -gt 0) { $req.args = $Arguments }
    $req | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -Path $reqPath

    # A previous run must be finished, otherwise Start-ScheduledTask is a no-op. The launcher
    # keeps sleeping for its own max_seconds after we stop the client, so wait for it.
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-ScheduledTask -TaskName 'BiliPai-MacTest').State -eq 'Running') {
        if ((Get-Date) -gt $deadline) { throw 'BiliPai-MacTest is still running from an earlier run' }
        Start-Sleep -Milliseconds 250
    }
    # A client that is still shutting down makes the next instance hand off to it instead of
    # opening its own window (single instance), so wait until the previous one is really gone.
    $clear = 0
    $deadline = (Get-Date).AddSeconds(30)
    while ($clear -lt 2 -and (Get-Date) -lt $deadline) {
        if (Get-Process -Name $script:BpAppName -ErrorAction SilentlyContinue) { $clear = 0 }
        else { $clear++ }
        Start-Sleep -Milliseconds 500
    }
    if ($clear -lt 2) { Write-BpLog 'a previous client is still running; the launch may hand off to it' }
    Start-ScheduledTask -TaskName 'BiliPai-MacTest'
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $resPath) {
            try { $res = Get-Content -LiteralPath $resPath -Raw -Encoding utf8 | ConvertFrom-Json } catch { $res = $null }
            if ($res -and $res.status -eq 'refused') { throw ("launcher refused: " + $res.reason) }
            if ($res -and $res.pid) {
                Write-BpLog ("launcher started pid={0} max_seconds={1}" -f $res.pid, $MaxSeconds)
                return [pscustomobject]@{ Pid = [int]$res.pid; Since = (Get-Date); Result = $res }
            }
            if ($res -and $res.status -eq 'error') { throw ("launcher error: " + $res.reason) }
        }
        Start-Sleep -Milliseconds 100
    }
    throw 'launcher produced no pid within 45 s'
}

function Wait-BpWindow {
    param([int]$ProcessId, [datetime]$Since = [datetime]::MinValue, [int]$TimeoutMs = 30000)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    # The exe takes ~10 s to appear after Start-Process here (the unsigned build is scanned
    # first), so "no process yet" is normal early on. Only a client that appeared and then
    # disappeared counts as an exited client.
    $everSeen = $false
    $goneSince = $null
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $best = $null
        $bestArea = 0
        foreach ($proc in (Get-BpAppProcesses -RootPid $ProcessId -Since $Since)) {
            $child = $proc.Id
            foreach ($h in [BpMeasure]::WindowsOfPid([uint32]$child)) {
                $rect = New-Object BpMeasure+RECT
                [void][BpMeasure]::GetWindowRect($h, [ref]$rect)
                $area = ([int64]$rect.Right - $rect.Left) * ([int64]$rect.Bottom - $rect.Top)
                if ($area -gt $bestArea) { $bestArea = $area; $best = [pscustomobject]@{ Hwnd = $h; Pid = $child; Area = $area } }
            }
        }
        if ($best) { return $best }
        if (@(Get-BpAppProcesses -RootPid $ProcessId -Since $Since).Count -eq 0) {
            if ($everSeen) {
                if (-not $goneSince) { $goneSince = Get-Date }
                elseif (((Get-Date) - $goneSince).TotalSeconds -gt 3) {
                    throw "the client exited before showing a window (single-instance hand-off or a startup failure)"
                }
            }
        } else { $everSeen = $true; $goneSince = $null }
        Start-Sleep -Milliseconds 20
    }
    throw "process $ProcessId (and its children) showed no visible window within $TimeoutMs ms"
}

# Stops the process this run started and its descendants. The staged exe is a launcher, so
# the client itself is a child; nothing is matched by name.
function Stop-BpApp {
    param([int]$ProcessId, [datetime]$Since = [datetime]::MinValue)
    foreach ($p in (Get-BpAppProcesses -RootPid $ProcessId -Since $Since)) {
        Write-BpLog ("stopping pid {0} (client started by this run)" -f $p.Id)
        try { Stop-Process -Id $p.Id -Force -ErrorAction Stop } catch { Write-BpLog ("  stop failed: " + $_.Exception.Message) }
    }
    Start-Sleep -Milliseconds 800
}

function Get-BpProcessSample {
    param([int]$ProcessId)
    $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $p) { return $null }
    # Get-Process has no I/O counters on this platform; reading them off the Process object
    # silently produced zeros. The counters live on the CIM Win32_Process class instead.
    $ioRead = $null; $ioWrite = $null; $ioOther = $null
    $cim = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $ProcessId) -ErrorAction SilentlyContinue
    if ($cim) {
        $ioRead = [int64]$cim.ReadOperationCount
        $ioWrite = [int64]$cim.WriteOperationCount
        $ioOther = [int64]$cim.OtherOperationCount
    }
    [pscustomobject]@{
        TimestampUtc   = (Get-Date).ToUniversalTime().ToString('o')
        PrivateMB      = [math]::Round($p.PrivateMemorySize64 / 1MB, 1)
        WorkingSetMB   = [math]::Round($p.WorkingSet64 / 1MB, 1)
        CpuSeconds     = [math]::Round($p.TotalProcessorTime.TotalSeconds, 3)
        IoReadOps      = $ioRead
        IoWriteOps     = $ioWrite
        IoOtherOps     = $ioOther
        IoAvailable    = ($null -ne $ioRead)
        Threads        = $p.Threads.Count
        Handles        = $p.HandleCount
    }
}

# Frame-time statistics over a list of inter-present intervals (milliseconds).
function Get-BpFrameStats {
    param([double[]]$Intervals, [double]$RefreshHz = 165)
    if (-not $Intervals -or $Intervals.Count -eq 0) { return $null }
    $sorted = $Intervals | Sort-Object
    $n = $sorted.Count
    function Pct([double]$q) {
        $idx = [int][math]::Floor(($n - 1) * $q)
        return [math]::Round($sorted[$idx], 3)
    }
    $budget = 1000.0 / $RefreshHz
    $dropped = @($sorted | Where-Object { $_ -gt 1.5 * $budget }).Count
    $dropped2 = @($sorted | Where-Object { $_ -gt 2.0 * $budget }).Count
    $sum = ($sorted | Measure-Object -Sum).Sum
    [pscustomobject]@{
        frames        = $n
        p50_ms        = (Pct 0.50)
        p95_ms        = (Pct 0.95)
        p99_ms        = (Pct 0.99)
        max_ms        = [math]::Round($sorted[$n - 1], 3)
        mean_ms       = [math]::Round($sum / $n, 3)
        budget_ms     = [math]::Round($budget, 3)
        dropped_pct   = [math]::Round(100.0 * $dropped / $n, 2)
        dropped2_pct  = [math]::Round(100.0 * $dropped2 / $n, 2)
        effective_fps = [math]::Round(1000.0 / ($sum / $n), 1)
    }
}

function ConvertTo-BpDouble {
    param([string]$Value)
    if (-not $Value) { return $null }
    $trimmed = $Value.Trim()
    if (-not $trimmed) { return $null }
    $out = 0.0
    if ([double]::TryParse($trimmed, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$out)) { return $out }
    return $null
}

# Reads a PresentMon CSV and returns rows with parsed timestamps.
function Read-BpPresentMonCsv {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { throw "PresentMon CSV missing: $Path" }
    $lines = Get-Content -LiteralPath $Path
    if ($lines.Count -lt 2) { return [pscustomobject]@{ Header = ($lines -join ' '); Rows = @() } }
    $header = $lines[0]
    $cols = $header.Split(',')
    $index = @{}
    for ($i = 0; $i -lt $cols.Count; $i++) { $index[$cols[$i].Trim()] = $i }
    Write-BpLog ("presentmon header: " + $header)
    $rows = New-Object System.Collections.ArrayList
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if (-not $lines[$i]) { continue }
        $f = $lines[$i].Split(',')
        if ($f.Count -lt $cols.Count) { continue }
        $between = $null
        if ($index.ContainsKey('MsBetweenPresents')) {
            $between = ConvertTo-BpDouble $f[$index['MsBetweenPresents']]
        }
        $timeSeconds = $null
        $wall = $null
        $rawTime = $f[$index['TimeInSeconds']].Trim()
        $asDouble = ConvertTo-BpDouble $rawTime
        if ($null -ne $asDouble) { $timeSeconds = $asDouble }
        else {
            $parsedTime = [datetime]::MinValue
            if ([datetime]::TryParse($rawTime, [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::None, [ref]$parsedTime)) { $wall = $parsedTime }
        }
        $row = New-Object psobject -Property @{
            Application = $f[$index['Application']]
            ProcessId   = $f[$index['ProcessID']]
            SwapChain   = $f[$index['SwapChainAddress']]
            Runtime     = $f[$index['Runtime']]
            TimeSeconds = $timeSeconds
            BetweenMs   = $between
            WallClock   = $wall
            RawTime     = $rawTime
        }
        if (-not $row.WallClock) {
            foreach ($name in @('CPUStartDateTime', 'DateTime')) {
                if ($index.ContainsKey($name)) {
                    $parsed = [datetime]::MinValue
                    if ([datetime]::TryParse($f[$index[$name]].Trim(), [Globalization.CultureInfo]::InvariantCulture,
                            [Globalization.DateTimeStyles]::None, [ref]$parsed)) { $row.WallClock = $parsed }
                    break
                }
            }
        }
        [void]$rows.Add($row)
    }
    return [pscustomobject]@{ Header = $header; Rows = $rows }
}

function Start-BpPresentMon {
    param([string]$OutputCsv, [int]$Seconds, [int]$ProcessId = 0, [string]$ProcessName = $script:BpAppExeName)
    if (-not (Test-Path -LiteralPath $script:BpPresentMon)) { throw "PresentMon missing: $script:BpPresentMon" }
    # A capture that was killed leaves the ETW session behind, which makes the next one fail
    # with "a trace session named PresentMon is already running". Clear it first.
    & logman stop PresentMon -ets 2>&1 | Out-Null
    $argv = New-Object System.Collections.Generic.List[string]
    # Start-Process joins the argument list with spaces, so values that contain a space
    # (the exe name, the CSV path) have to carry their own quotes.
    if ($ProcessId -gt 0) { $argv.Add('--process_id'); $argv.Add([string]$ProcessId) }
    else { $argv.Add('--process_name'); $argv.Add('"' + $ProcessName + '"') }
    # --v1_metrics + --date_time replaces the TimeInSeconds column with a wall clock; the
    # v2 metric set prints a CPUStartDateTime that does not match this machine's clock, so
    # v1 is the one that can be joined with our own timestamps. --timed does not actually
    # stop 2.6.0 here, so Wait-BpPresentMon stops the exact pid instead.
    foreach ($a in @('--output_file', ('"' + $OutputCsv + '"'), '--date_time', '--v1_metrics', '--no_console_stats',
            '--stop_existing_session')) { $argv.Add($a) }
    $errLog = [IO.Path]::ChangeExtension($OutputCsv, '.presentmon.log')
    $errLog2 = [IO.Path]::ChangeExtension($OutputCsv, '.presentmon.err.log')
    $started = Get-Date
    $p = Start-Process -FilePath $script:BpPresentMon -ArgumentList $argv -PassThru -WindowStyle Hidden -RedirectStandardOutput $errLog -RedirectStandardError $errLog2
    Write-BpLog ("presentmon pid={0} csv={1} timed={2}s startedUtc={3:o}" -f $p.Id, $OutputCsv, $Seconds, $started.ToUniversalTime())
    [pscustomobject]@{ Process = $p; Csv = $OutputCsv; StartedUtc = $started.ToUniversalTime() }
}

# PresentMon 2.6.0 keeps recording until it is stopped, so every capture is closed by
# stopping the exact pid it was started with (never by image name).
function Stop-BpPresentMon {
    param($Capture)
    if (-not $Capture.Process.HasExited) {
        Stop-Process -Id $Capture.Process.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 1200
    }
    $Capture.Process.Refresh()
    $csvThere = Test-Path -LiteralPath $Capture.Csv
    Write-BpLog ("presentmon stopped: exit=" + $Capture.Process.ExitCode + " csv_exists=" + $csvThere)
    if (-not $csvThere) {
        $errPath = [IO.Path]::ChangeExtension($Capture.Csv, '.presentmon.err.log')
        if (Test-Path -LiteralPath $errPath) {
            foreach ($line in (Get-Content -LiteralPath $errPath -TotalCount 4)) { Write-BpLog ("  presentmon: " + $line) }
        }
    }
}

function Wait-BpPresentMon {
    param($Capture, [int]$TimeoutMs = 120000)
    if (-not $Capture.Process.HasExited) {
        if (-not $Capture.Process.WaitForExit($TimeoutMs)) {
            Write-BpLog "presentmon did not stop in time; stopping the exact pid $($Capture.Process.Id)"
            Stop-Process -Id $Capture.Process.Id -Force
            Start-Sleep -Milliseconds 800
        }
    }
    $Capture.Process.Refresh()
    Write-BpLog ("presentmon exit=" + $Capture.Process.ExitCode + " csv_exists=" + (Test-Path -LiteralPath $Capture.Csv))
}

function Send-BpWheel {
    param([IntPtr]$Hwnd, [int]$Notches = 3, [switch]$Up)
    $rect = New-Object BpMeasure+RECT
    [void][BpMeasure]::GetClientRect($Hwnd, [ref]$rect)
    $x = [int]($rect.Right * 0.55)
    $y = [int]($rect.Bottom * 0.5)
    $pt = New-Object BpMeasure+POINT -ArgumentList $x, $y
    [void][BpMeasure]::ClientToScreen($Hwnd, [ref]$pt)
    $delta = 120 * $Notches
    if (-not $Up) { $delta = -$delta }
    $wparam = [IntPtr]([int64]($delta -shl 16))
    $lparam = [IntPtr]([int64](($pt.Y -shl 16) -bor ($pt.X -band 0xFFFF)))
    return [BpMeasure]::PostMessage($Hwnd, [BpMeasure]::WM_MOUSEWHEEL, $wparam, $lparam)
}

function Send-BpClick {
    param([IntPtr]$Hwnd, [int]$X, [int]$Y)
    $lparam = [IntPtr]([int64](($Y -shl 16) -bor ($X -band 0xFFFF)))
    [void][BpMeasure]::PostMessage($Hwnd, [BpMeasure]::WM_MOUSEMOVE, [IntPtr]::Zero, $lparam)
    Start-Sleep -Milliseconds 60
    [void][BpMeasure]::PostMessage($Hwnd, [BpMeasure]::WM_LBUTTONDOWN, [IntPtr]1, $lparam)
    Start-Sleep -Milliseconds 80
    [void][BpMeasure]::PostMessage($Hwnd, [BpMeasure]::WM_LBUTTONUP, [IntPtr]0, $lparam)
    return (Get-Date).ToUniversalTime()
}

function Write-BpCsv {
    param($Rows, [string]$Path)
    if (-not $Rows -or @($Rows).Count -eq 0) { return }
    @($Rows) | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding utf8
    Write-BpLog "wrote $Path"
}

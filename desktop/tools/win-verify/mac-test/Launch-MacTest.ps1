# BiliPai Mac test launcher (manual trigger only). Reads tools\request.json and writes tools\result.json.
# Refuses to start when tools\PAUSE exists, when the foreground window is fullscreen (not a shell or browser),
# when a non-BiliPai process keeps the GPU above 30% for 10 s, or when the exe is outside the apps directory.
# Legacy requests retain single-PID behavior. capture_tree opts into measured descendants and a bounded evidence session.
$ErrorActionPreference = 'Stop'
$root   = 'C:\Users\TONYS\bp-mac-verify'
$tools  = Join-Path $root 'tools'
$reqP   = Join-Path $tools 'request.json'
$resP   = Join-Path $tools 'result.json'
$pauseP = Join-Path $tools 'PAUSE'
$shots  = Join-Path $tools 'shots'
$logs   = Join-Path $tools 'logs'
New-Item -ItemType Directory -Force -Path $shots, $logs | Out-Null

# Isolate C# compiler scratch files for opt-in requests as well.
$earlyRequest = Get-Content $reqP -Raw -Encoding UTF8 | ConvertFrom-Json
if ($earlyRequest.capture_tree -eq $true) {
  $env:TEMP = Join-Path $root 'ton1020\profile\Temp'
  $env:TMP = $env:TEMP
  $env:JAVA_TOOL_OPTIONS = "-Duser.home=$root\ton1020\profile -Djava.io.tmpdir=$env:TEMP"
  New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
}
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Text;
using System.Drawing;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class BpWin {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct MONITORINFO { public int cbSize; public RECT rcMonitor; public RECT rcWork; public uint dwFlags; }
  delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
  [DllImport("user32.dll")] public static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
  [DllImport("user32.dll")] public static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO mi);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
  public static bool IsFullscreen(IntPtr w) {
    RECT r; GetWindowRect(w, out r);
    IntPtr m = MonitorFromWindow(w, 2);
    MONITORINFO mi = new MONITORINFO(); mi.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
    if (!GetMonitorInfo(m, ref mi)) return false;
    return r.Left <= mi.rcMonitor.Left && r.Top <= mi.rcMonitor.Top && r.Right >= mi.rcMonitor.Right && r.Bottom >= mi.rcMonitor.Bottom;
  }
  public static string ClassOf(IntPtr w) { StringBuilder sb = new StringBuilder(256); GetClassName(w, sb, 256); return sb.ToString(); }
  public static List<IntPtr> WindowsOfPid(uint pid) {
    List<IntPtr> res = new List<IntPtr>();
    EnumWindows(delegate(IntPtr h, IntPtr l) { uint p; GetWindowThreadProcessId(h, out p); if (p == pid && IsWindowVisible(h)) res.Add(h); return true; }, IntPtr.Zero);
    return res;
  }
  public static Bitmap Capture(IntPtr h) {
    RECT r; GetWindowRect(h, out r);
    Bitmap bmp = new Bitmap(r.Right - r.Left, r.Bottom - r.Top);
    using (Graphics g = Graphics.FromImage(bmp)) { IntPtr hdc = g.GetHdc(); PrintWindow(h, hdc, 2); g.ReleaseHdc(hdc); }
    return bmp;
  }
}
'@

function Save-Result($obj) { $obj | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 -Path $resP }

$res = [ordered]@{ started = (Get-Date).ToString('o'); status = 'error'; reason = $null; pid = $null; killed_by_script = $false; exit_code = $null; screenshot = $null; stdout = $null; stderr = $null; gate = [ordered]@{} }
try {
  $req = Get-Content $reqP -Raw -Encoding utf8 | ConvertFrom-Json
  $maxSec = [int]$req.max_seconds; if ($maxSec -lt 2) { $maxSec = 5 }; if ($maxSec -gt 3600) { $maxSec = 3600 }

  # Gate 1: pause file
  if (Test-Path $pauseP) { $res.status = 'refused'; $res.reason = 'PAUSE file present'; Save-Result $res; return }

  # Only canonical files under apps may reach Start-Process. Reject directory links and ADS.
  $apps = Join-Path $root 'apps'
  $target = [System.IO.Path]::GetFullPath([string]$req.exe)
  if (-not $target.StartsWith($apps + '\', [System.StringComparison]::OrdinalIgnoreCase) -or $target.Substring(2).Contains(':')) {
    $res.status = 'refused'; $res.reason = "path outside $apps"; Save-Result $res; return
  }
  $cursor = $target
  while ($cursor -and $cursor.StartsWith($apps, [System.StringComparison]::OrdinalIgnoreCase)) {
    if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
      $res.status = 'refused'; $res.reason = 'reparse point in apps path'; Save-Result $res; return
    }
    if ($cursor -eq $apps) { break }
    $cursor = Split-Path $cursor -Parent
  }
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { $res.status = 'refused'; $res.reason = 'file not found'; Save-Result $res; return }

  # Gate 2: fullscreen foreground window that is not the desktop, Explorer or a browser
  $fg = [BpWin]::GetForegroundWindow()
  $fgPid = [uint32]0; [void][BpWin]::GetWindowThreadProcessId($fg, [ref]$fgPid)
  $fgProc = 'unknown'; try { $fgProc = (Get-Process -Id $fgPid).ProcessName } catch { }
  $cls = [BpWin]::ClassOf($fg)
  $fgFull = [BpWin]::IsFullscreen($fg)
  $fgShell = (@('Progman','WorkerW','Shell_TrayWnd') -contains $cls) -or (@('explorer','msedge','chrome','firefox','brave','opera') -contains $fgProc)
  $res.gate.foreground = [ordered]@{ process = $fgProc; class = $cls; fullscreen = $fgFull }
  if ($fgFull -and -not $fgShell) { $res.status = 'refused'; $res.reason = "foreground window is fullscreen ($fgProc)"; Save-Result $res; return }

  # Gate 3: GPU above 30% for 10 consecutive seconds while a non-BiliPai process is present
  $nvs = 'C:\WINDOWS\system32\nvidia-smi.exe'
  $busy = 0; $samples = @()
  for ($i = 0; $i -lt 10; $i++) {
    $u = [int](((& $nvs --query-gpu=utilization.gpu --format=csv,noheader,nounits) | Select-Object -First 1).Trim())
    $samples += $u; if ($u -gt 30) { $busy++ }
    Start-Sleep -Seconds 1
  }
  $plain = (& $nvs) -join "`n"
  $names = @([regex]::Matches($plain, '([A-Za-z0-9_.\-]+\.exe)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
  $others = @($names | Where-Object { $_ -notmatch '^BiliPai' })
  $res.gate.gpu_samples = $samples; $res.gate.gpu_process_names = $names
  if ($busy -eq 10 -and $others.Count -gt 0) { $res.status = 'refused'; $res.reason = "GPU above 30% for 10 s with other processes: $($others -join ', ')"; Save-Result $res; return }


  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $outP = Join-Path $logs "$stamp.out.txt"; $errP = Join-Path $logs "$stamp.err.txt"
  $argList = New-Object System.Collections.Generic.List[string]
  if ($target.EndsWith('.jar', [System.StringComparison]::OrdinalIgnoreCase)) {
    $envj = Get-Content (Join-Path $root 'handover\env.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $file = Join-Path $envj.java_home 'bin\java.exe'; $argList.Add('-jar'); $argList.Add($target)
  } else { $file = $target }
  if ($req.args) { foreach ($a in $req.args) { $argList.Add([string]$a) } }
  $startArgs = @{ FilePath = $file; PassThru = $true; RedirectStandardOutput = $outP; RedirectStandardError = $errP }
  if ($argList.Count -gt 0) { $startArgs['ArgumentList'] = $argList.ToArray() }
  $treeMode = $req.capture_tree -eq $true
  if ($treeMode) {
    . (Join-Path $tools 'TreeEvidence.ps1')
    $evidenceDir = Assert-BpEvidencePath ([string]$req.evidence_dir) $root
    if ($req.session_script) { $sessionScript = Assert-BpEvidencePath ([string]$req.session_script) $apps }
  }
  $p = Start-Process @startArgs
  $res.pid = $p.Id; $res.status = 'running'; $res.stdout = $outP; $res.stderr = $errP; Save-Result $res

  if ($treeMode) {
    try {
      $null = Initialize-BpTree $p.Id $evidenceDir
      if ($sessionScript) {
        & $sessionScript -Deadline ((Get-Date).AddSeconds($maxSec))
      } else {
        $w = Wait-BpTreeWindow ([Math]::Max(1, $maxSec - 1))
        $shot = Save-BpTreeShot $w 'descendant-window'
        $res.screenshot = $shot.path
      }
      $res.status = 'done'
    } catch {
      $res.status = 'error'; $res.reason = $_.Exception.Message
    } finally {
      if ($script:BpTree) {
        try { Stop-BpTree; $res.killed_by_script = $true }
        catch { $res.status = 'error'; $res.reason = $_.Exception.Message }
      } elseif ($p -and !$p.HasExited) { Stop-Process -Id $p.Id -Force }
      $res.tree_evidence = Join-Path $evidenceDir 'pid-tree.json'
      $res.ended = (Get-Date).ToString('o')
      Save-Result $res
    }
    return
  }

  # Capture only this PID's visible window, one second before the limit
  $shotAt = [Math]::Max(1, $maxSec - 1)
  Start-Sleep -Seconds $shotAt
  $wins = [BpWin]::WindowsOfPid([uint32]$p.Id)
  if ($wins.Count -gt 0) {
    $bmp = [BpWin]::Capture($wins[0]); $shotP = Join-Path $shots "$stamp.png"
    $bmp.Save($shotP, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); $res.screenshot = $shotP
  } else { $res.gate.window = 'no visible window owned by this PID' }

  $left = $maxSec - $shotAt
  if ($left -gt 0) { Start-Sleep -Seconds $left }
  $p.Refresh()
  if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; $res.killed_by_script = $true; Start-Sleep -Milliseconds 300 }
  $p.Refresh(); $res.exit_code = $p.ExitCode
  $res.status = 'done'; $res.ended = (Get-Date).ToString('o')
  Save-Result $res
} catch {
  $res.status = 'error'; $res.reason = $_.Exception.Message; Save-Result $res
}


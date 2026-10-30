# VM joint acceptance helpers. Never select or stop a process by image name.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Json.ps1"
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Text;
using System.Drawing;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class BpEvidence {
  public class Window { public long hwnd; public uint pid; public string title, windowClass; public bool visible; public int width, height; }
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L,T,R,B; }
  delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder b, int n);
  [DllImport("user32.dll")] static extern int GetClassName(IntPtr h, StringBuilder b, int n);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr h, uint m, IntPtr w, IntPtr l, uint flags, uint ms, out IntPtr result);
  public static Window[] Windows(uint[] ids) {
    var allowed = new HashSet<uint>(ids); var result = new List<Window>();
    EnumWindows(delegate(IntPtr h, IntPtr p) {
      uint id; GetWindowThreadProcessId(h, out id);
      if (!allowed.Contains(id)) return true;
      var t = new StringBuilder(1024); var c = new StringBuilder(256); RECT r;
      GetWindowText(h,t,t.Capacity); GetClassName(h,c,c.Capacity); GetWindowRect(h,out r);
      result.Add(new Window { hwnd=h.ToInt64(), pid=id, title=t.ToString(), windowClass=c.ToString(), visible=IsWindowVisible(h), width=r.R-r.L, height=r.B-r.T }); return true;
    }, IntPtr.Zero); return result.ToArray();
  }
  public static void Capture(long handle, uint expectedPid, string path) {
    var h=new IntPtr(handle); uint owner; GetWindowThreadProcessId(h,out owner);
    if(owner!=expectedPid || !IsWindowVisible(h)) throw new Exception("window ownership changed");
    RECT r; if(!GetWindowRect(h,out r) || r.R<=r.L || r.B<=r.T) throw new Exception("invalid window rectangle");
    using(var b=new Bitmap(r.R-r.L,r.B-r.T)) {
      if(GetForegroundWindow()!=h) throw new Exception("owned capture target is not foreground");
      using(var g=Graphics.FromImage(b)) { g.CopyFromScreen(r.L,r.T,0,0,b.Size); }
      b.Save(path,System.Drawing.Imaging.ImageFormat.Png);
    }
  }
}
'@

function Assert-BpEvidencePath([string]$Path, [string]$Boundary) {
    $full = [IO.Path]::GetFullPath($Path)
    if (!$full.StartsWith($Boundary.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or $full.Substring(2).Contains(':')) {
        throw 'evidence path outside permitted directory'
    }
    $cursor = $full
    while ($cursor -and $cursor.Length -ge $Boundary.Length) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'reparse point in evidence path'
        }
        $cursor = Split-Path $cursor -Parent
    }
    return $full
}

function Invoke-BpEvidenceSession([string]$SessionScript, [datetime]$Deadline, [switch]$OfflineContextCheck) {
    if (!$script:BpTreeDirectory -or !$script:BpTree) { throw 'measured tree context is not initialized' }
    # A call operator (&) creates another script scope: the callback and helpers then
    # lose the tree's script: variables. Dot-source in the owning scope instead.
    if ($OfflineContextCheck) {
        . $SessionScript -Deadline $Deadline -OfflineContextCheck
    } else {
        . $SessionScript -Deadline $Deadline
    }
}

function Initialize-BpTree([int]$RootProcessId, [string]$Directory) {
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $script:BpTreeDirectory = $Directory
    $script:BpTree = @{}
    $script:BpObservations = [Collections.ArrayList]::new()
    $script:BpRootProcessId = $RootProcessId
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$RootProcessId"
    if (!$p) { throw 'root exited before its identity could be measured; refusing to guess descendants' }
    $script:BpRootCreated = $p.CreationDate
    Update-BpTree
}

function Update-BpTree {
    $now = Get-Date
    $all = @(Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId,CreationDate,Name)
    foreach ($node in $script:BpTree.Values) {
        $node.alive = $false
        $same = @($all | Where-Object { $_.ProcessId -eq $node.pid -and $_.CreationDate.Ticks -eq $node.createdTicks })
        if (!$same.Count -and !$node.deadObservedAt) { $node.deadObservedAt = $now.ToString('o') }
    }
    # Fixed point handles an arbitrary number of generations in one CIM snapshot.
    do {
        $added = $false
        foreach ($p in $all) {
            $key = "$($p.ProcessId):$($p.CreationDate.Ticks)"
            if ($script:BpTree.ContainsKey($key)) {
                $script:BpTree[$key].alive = $true
                $script:BpTree[$key].lastSeen = $now.ToString('o')
                continue
            }
            $parent = @($script:BpTree.Values | Where-Object {
                $_.pid -eq $p.ParentProcessId -and $_.createdTicks -le $p.CreationDate.Ticks -and
                # An exited parent can own children born before the first snapshot proving its absence.
                ($_.alive -or ($_.deadObservedAt -and $p.CreationDate -le [datetime]$_.deadObservedAt))
            }) | Select-Object -First 1
            $rootMatch = $p.ProcessId -eq $script:BpRootProcessId -and $p.CreationDate -eq $script:BpRootCreated
            if ($rootMatch -or $parent) {
                $script:BpTree[$key] = [ordered]@{ pid=[int]$p.ProcessId; parentProcessId=[int]$p.ParentProcessId;
                    name=$p.Name; created=$p.CreationDate.ToString('o'); createdTicks=$p.CreationDate.Ticks;
                    parentIdentity=$(if($parent){$parent.identity}else{$null}); identity=$key;
                    firstSeen=$now.ToString('o'); lastSeen=$now.ToString('o'); alive=$true; deadObservedAt=$null;
                    source='Win32_Process.ParentProcessId' }
                $added = $true
            }
        }
    } while ($added)
    $ids = [uint32[]]@($script:BpTree.Values | Where-Object alive | ForEach-Object { $_.pid })
    $windows = @([BpEvidence]::Windows($ids))
    [void]$script:BpObservations.Add([ordered]@{at=$now.ToString('o'); alivePids=@($ids); windows=$windows;
        systemDialogs=@($windows | Where-Object windowClass -eq '#32770').Count})
    Save-BpJson ([ordered]@{rootPid=$script:BpRootProcessId; processes=@($script:BpTree.Values); observations=@($script:BpObservations)}) (Join-Path $script:BpTreeDirectory 'pid-tree.json')
    return $windows
}

function Wait-BpTreeWindow([int]$Seconds = 60, [int]$ExcludePid = 0) {
    $until = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $until) {
        $windows = @(Update-BpTree)
        if (@($windows | Where-Object windowClass -eq '#32770').Count) { throw 'system dialog in measured process tree' }
        $w = $windows | Where-Object { $_.visible -and $_.width -gt 200 -and $_.height -gt 150 -and $_.pid -ne $ExcludePid -and $_.windowClass -notmatch 'ConsoleWindowClass' } |
            Sort-Object { $_.width * $_.height } -Descending | Select-Object -First 1
        if ($w) { return $w }
        Start-Sleep -Milliseconds 150
    }
    throw 'no proven descendant window before deadline'
}

function Save-BpTreeShot($Window, [string]$Name) {
    $current = @(Update-BpTree) | Where-Object { $_.hwnd -eq $Window.hwnd -and $_.pid -eq $Window.pid }
    if (!$current) { throw 'window is no longer owned by a live measured descendant' }
    $path = Join-Path $script:BpTreeDirectory "$Name.png"
    [BpEvidence]::Capture($Window.hwnd, $Window.pid, $path)
    $record = [ordered]@{at=(Get-Date).ToString('o'); path=$path; hwnd=$Window.hwnd; pid=$Window.pid;
        identity=($script:BpTree.Values | Where-Object { $_.alive -and $_.pid -eq $Window.pid } | Select-Object -First 1).identity }
    Save-BpJson $record (Join-Path $script:BpTreeDirectory "$Name.window.json")
    return $record
}

function Stop-BpTree {
    $null = Update-BpTree
    foreach ($node in @($script:BpTree.Values | Where-Object alive | Sort-Object createdTicks -Descending)) {
        # Check creation time again immediately before stopping, protecting against PID reuse.
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($node.pid)"
        if ($p -and $p.CreationDate.Ticks -eq $node.createdTicks) { Stop-Process -Id $node.pid -Force -ErrorAction SilentlyContinue }
    }
    Start-Sleep -Milliseconds 300
    $null = Update-BpTree
    $remaining = @($script:BpTree.Values | Where-Object alive)
    if ($remaining.Count) { throw "measured processes still alive: $($remaining.pid -join ',')" }
}

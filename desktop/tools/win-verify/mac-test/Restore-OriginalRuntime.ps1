# No GUI or client launch. Refuse busy tasks/locks and any runtime resource owner.
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Json.ps1"
$root='C:\Users\TONYS\bp-mac-verify'
$out="$root\ton1047"
$target="$root\apps\TON1020\runtime"
$original="$out\runtime-original"
$retained="$out\runtime-probe-retained"
$env:TEMP="$root\ton1020\profile\Temp";$env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'MacTest occupied; runtime unchanged'}
if(Test-Path "$root\tools\job.lock"){throw 'tools occupied; runtime unchanged'}
if(Test-Path "$root\measure\job.lock"){throw 'measurement occupied; runtime unchanged'}
if(!(Test-Path "$original\release") -or (Test-Path $retained)){throw 'restore source/destination preflight failed'}
$marker="$out\client-launch-used.json"
$markerBefore=(Get-FileHash $marker -Algorithm SHA256).Hash
$lease=[IO.File]::Open("$root\tools\job.lock",'CreateNew','Write','Read')
$data=[Text.Encoding]::UTF8.GetBytes("TON-1047 offline runtime restore pid=$PID")
$lease.Write($data,0,$data.Length);$lease.Dispose()
try {
    if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'MacTest became occupied; runtime unchanged'}
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class BpRuntimeOwners {
 [StructLayout(LayoutKind.Sequential)] public struct Unique { public int pid; public System.Runtime.InteropServices.ComTypes.FILETIME started; }
 [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct Info {
  public Unique process;
  [MarshalAs(UnmanagedType.ByValTStr,SizeConst=256)] public string app;
  [MarshalAs(UnmanagedType.ByValTStr,SizeConst=64)] public string service;
  public uint type,status,session;
  [MarshalAs(UnmanagedType.Bool)] public bool restartable;
 }
 [DllImport("rstrtmgr.dll",CharSet=CharSet.Unicode)] static extern int RmStartSession(out uint s,int flags,StringBuilder key);
 [DllImport("rstrtmgr.dll",CharSet=CharSet.Unicode)] static extern int RmRegisterResources(uint s,uint n,string[] files,uint apps,Unique[] processes,uint services,string[] names);
 [DllImport("rstrtmgr.dll")] static extern int RmGetList(uint s,out uint needed,ref uint count,[In,Out] Info[] owners,ref uint reasons);
 [DllImport("rstrtmgr.dll")] static extern int RmEndSession(uint s);
 public static int[] Of(string[] files) {
  uint s; int rc=RmStartSession(out s,0,new StringBuilder(33));
  if(rc!=0) throw new Exception("RmStartSession: "+rc);
  try {
   rc=RmRegisterResources(s,(uint)files.Length,files,0,null,0,null);
   if(rc!=0) throw new Exception("RmRegisterResources: "+rc);
   uint needed=0,count=0,reasons=0;
   rc=RmGetList(s,out needed,ref count,null,ref reasons);
   if(rc==0 && needed==0) return new int[0];
   if(rc!=234) throw new Exception("RmGetList: "+rc);
   count=needed; var owners=new Info[count];
   rc=RmGetList(s,out needed,ref count,owners,ref reasons);
   if(rc!=0) throw new Exception("RmGetList changed/failed: "+rc);
   var ids=new int[count]; for(int i=0;i<count;i++) ids[i]=owners[i].process.pid;
   return ids;
  } finally {RmEndSession(s);}
 }
}
'@
    $files=[string[]]@(Get-ChildItem $target -Recurse -File | ForEach-Object FullName)
    if(!$files.Count){throw 'no runtime resources to check'}
    $owners=@([BpRuntimeOwners]::Of($files))
    if($owners.Count){throw "runtime still loaded/in use by PIDs: $($owners -join ','); runtime unchanged"}
    if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'MacTest became occupied; runtime unchanged'}
    $originalRelease=Get-Content "$original\release" -Raw
    Move-Item $target $retained
    try { Move-Item $original $target }
    catch {if(!(Test-Path $target)){Move-Item $retained $target};throw}
    if((Get-Content "$target\release" -Raw) -ne $originalRelease){throw 'restored runtime release differs'}
    if($originalRelease -match '\bjava\.instrument\b'){throw 'original runtime unexpectedly includes java.instrument'}
    $markerAfter=(Get-FileHash $marker -Algorithm SHA256).Hash
    if($markerAfter -ne $markerBefore){throw 'single-use marker changed'}
    Save-BpJson ([ordered]@{restored=$true;at=(Get-Date).ToString('o');ownerCheck='Windows Restart Manager';
        resourcesChecked=$files.Count;ownerPids=$owners;taskState='Ready';release=$originalRelease;
        preservedProbeRuntime=$retained;markerSha256Before=$markerBefore;markerSha256After=$markerAfter;
        files=@(Get-ChildItem $target -Recurse -File | ForEach-Object {
            [ordered]@{path=$_.FullName.Substring($target.Length+1);sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}
        })}) "$out\runtime-restored.json"
    Write-Output 'ORIGINAL_RUNTIME_RESTORED (zero resource owners; launch marker unchanged)'
} finally {Remove-Item "$root\tools\job.lock" -ErrorAction SilentlyContinue}

# Windows PowerShell 5.1 turns redirected native stderr into NativeCommandError
# records. Benign JVM startup diagnostics must not terminate a successful process.
# The actual exit code still decides whether the caller rejects the command.
function Invoke-BpNativeWithLogs([string]$Executable,[string[]]$ArgumentList,[string]$StdoutPath,[string]$StderrPath){
 $previousPreference=$ErrorActionPreference
 try{
  $ErrorActionPreference='Continue'
  & $Executable @ArgumentList > $StdoutPath 2> $StderrPath
  $nativeExitCode=$LASTEXITCODE
 }finally{$ErrorActionPreference=$previousPreference}
 return [int]$nativeExitCode
}

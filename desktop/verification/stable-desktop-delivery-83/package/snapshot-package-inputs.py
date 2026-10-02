from pathlib import Path
import datetime, hashlib, json, os, subprocess, sys
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent
REPO=LANE.parents[3]/'BiliPai-v023'
def wide(path):
 value=str(Path(path).absolute())
 return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def digest(path):
 h=hashlib.sha256()
 with wide(path).open('rb') as stream:
  while chunk:=stream.read(1024*1024):h.update(chunk)
 return h.hexdigest()
def git(*args):return subprocess.check_output(['git','-C',str(REPO),*args])
def file_row(path,relative):return dict(path=relative,bytes=wide(path).stat().st_size,sha256Bytes=digest(path))
def directory(root):
 root=wide(root)
 return [file_row(path,path.relative_to(root).as_posix()) for path in sorted(root.rglob('*')) if path.is_file()]
def main():
 mode=sys.argv[1];run=LANE/'attempt02';wide(run).mkdir(parents=True,exist_ok=True)
 head=git('rev-parse','HEAD').decode().strip()
 assert head=='6fd5bbd804f272650942f5a445385ba5428ffa9b',head
 status=git('status','--porcelain').decode()
 if mode=='before':assert not status,status
 all_paths=[p.decode('utf-8') for p in git('ls-files','-z').split(b'\0') if p]
 # Every tracked source anywhere in the repository, including proof source;
 # retain all non-verification tracked product/build/config files as well.
 code_suffixes={'.kt','.kts','.java','.py','.ps1','.bat','.cmd','.sh','.c','.cpp','.h','.hpp','.cc','.cs','.rs','.gradle','.groovy','.proto','.sql'}
 paths=[name for name in all_paths if not name.startswith('desktop/verification/') or Path(name).suffix.lower() in code_suffixes]
 tracked=[file_row(REPO/name,name) for name in paths]
 resources=directory(REPO/'desktop/resources/common/native/windows-x64')
 native=directory(REPO/'desktop/native/windows-x64')
 record=dict(mode=mode,recordedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),codeHead=head,
   allTrackedProductAndBuildFilesPlusAllTrackedSourceFiles=tracked,trackedFiles=len(tracked),
   nativeResources=resources,ignoredNativeSource=native,gitStatus=status,
   runtimeDiagnosticSha256=digest(REPO/'desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'))
 assert record['runtimeDiagnosticSha256']=='89705e2e0b5b146c8fdbccda63dc68bea7e46bfcc7e4aaa50c4cb1efa5331f06'
 wide(run/(mode+'-inputs.json')).write_bytes((json.dumps(record,ensure_ascii=False,indent=2)+'\n').encode())
 if mode=='after':
  before=json.loads(wide(run/'before-inputs.json').read_bytes())
  old={r['path']:r for r in before['allTrackedProductAndBuildFilesPlusAllTrackedSourceFiles']}
  new={r['path']:r for r in tracked}
  changed=[dict(path=name,before=old.get(name),after=new.get(name)) for name in sorted(set(old)|set(new)) if old.get(name)!=new.get(name)]
  result=dict(trackedSourceAndProductFilesUnchanged=not changed,trackedChanges=changed,diagnosticDllUnchanged=before['runtimeDiagnosticSha256']==record['runtimeDiagnosticSha256'],
   nativeResourcesBefore=before['nativeResources'],nativeResourcesAfter=resources,ignoredNativeSourceBefore=before['ignoredNativeSource'],ignoredNativeSourceAfter=native,gitStatusAfter=status)
  wide(run/'input-change-audit.json').write_bytes((json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode())
 print(json.dumps(dict(mode=mode,trackedFiles=len(tracked),nativeResources=len(resources),ignoredNativeFiles=len(native),recordSha256Bytes=digest(run/(mode+'-inputs.json')),gitStatus=status)))
if __name__=='__main__':main()

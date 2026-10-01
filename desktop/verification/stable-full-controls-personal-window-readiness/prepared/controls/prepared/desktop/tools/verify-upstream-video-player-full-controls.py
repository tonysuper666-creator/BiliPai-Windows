from pathlib import Path
import hashlib, importlib.util, json, tempfile, sys
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def verify(repo,output,report):
 path=Path(__file__).with_name('extract-upstream-video-player-full-controls.py')
 spec=importlib.util.spec_from_file_location('verify_one_original_controls',path);tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
 with tempfile.TemporaryDirectory(prefix='bilipai-verify-controls-')as tmp:
  tool.generate(repo,tmp)
  rows=[]
  for row in tool.OUTPUTS:
   if row['generated']:
    expected=Path(tmp)/row['path'];actual=Path(output)/row['path']
    assert sha(expected)==sha(actual)==row['sha256LF'],row['path']+' generated source differs'
    rows.append(dict(path=row['path'],sha256LF=sha(actual)))
  assert len(rows)==20
 wide(report).parent.mkdir(parents=True,exist_ok=True)
 wide(report).write_text(json.dumps(dict(passed=True,commit=tool.COMMIT,sourceIdentities=len(tool.SOURCES),selectedOutputs=rows,directCopyOutputs=26,scope='Exact selected source generation; existing protocol/editor gates remain independent'),indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':verify(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))

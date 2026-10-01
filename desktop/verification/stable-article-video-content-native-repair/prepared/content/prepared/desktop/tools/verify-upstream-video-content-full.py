from pathlib import Path
import importlib.util,tempfile
def verify(repo,generated):
 p=Path(__file__).with_name('extract-upstream-video-content-full.py')
 s=importlib.util.spec_from_file_location('verify_complete_video_content',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
 with tempfile.TemporaryDirectory(prefix='bilipai-full-video-content-')as d:
  m.generate(Path(repo),Path(d))
  for row in m.OUTPUTS:
   if not row['generated']:continue
   expected=Path(d)/row['path'];actual=Path(generated)/row['path']
   assert m.wide(actual).read_bytes()==m.wide(expected).read_bytes(),str(actual)+' differs from source-only producer'
if __name__=='__main__':
 import sys
 verify(Path(sys.argv[1]),Path(sys.argv[2]))

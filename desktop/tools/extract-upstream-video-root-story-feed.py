"""One full fixed v035 Story feed method through existing captured primary API."""
from pathlib import Path
import argparse,json
from v035_story import story_protocol
COMMIT='46eaf277e83512061785be9759d1f07aba3c4839'
SOURCE='app/src/main/java/com/android/purebilibili/data/repository/StoryRepository.kt'
OUTPUT='com/android/purebilibili/data/repository/DesktopOriginalVideoStoryFeedProtocol.kt'
def generate(repo,output,standalone=False):
 return story_protocol(Path(output))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--standalone',action='store_true')
 a=p.parse_args();r=generate(a.repo,a.output,a.standalone)
 print(json.dumps({k:r[k] for k in ['output','outputSHA256LF','bodyInverseByteEqual','outputCount']}))

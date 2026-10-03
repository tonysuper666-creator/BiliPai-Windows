"""Three complete stable pure helpers; Windows byte IO is the sole platform actor."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,subprocess
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
SOURCE='app/src/main/java/com/android/purebilibili/core/player/PlaybackMediaCache.kt'
SOURCE_SHA='9c05458cad086fd2583448883c806af2d610523b9a17f5052346bab2c0fc967a'
OUTPUT='com/android/purebilibili/core/player/DesktopOriginalPlaybackMediaCachePolicy.kt'
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def selected(text):
    size=text.split('internal fun resolvePlaybackMediaCacheMaxBytes():',1)[1].split('\n',1)[0]
    max_body='internal fun resolvePlaybackMediaCacheMaxBytes():'+size+'\n'
    use='internal fun shouldUsePlaybackMediaCache(rawUri: String):'+text.split('internal fun shouldUsePlaybackMediaCache(rawUri: String):',1)[1].split('\ninternal fun buildPlaybackCacheKey(uri:',1)[0].rstrip()+'\n'
    key='internal fun buildPlaybackCacheKey(rawUri: String, explicitKey: String?):'+text.split('internal fun buildPlaybackCacheKey(rawUri: String, explicitKey: String?):',1)[1].split('\n@UnstableApi',1)[0].rstrip()+'\n'
    return max_body+'\n'+use+'\n'+key
def generate(repo,output):
    raw=wide(_desktop_canonical_source(repo, SOURCE)).read_bytes().replace(b'\r\n',b'\n')
    assert hashlib.sha256(raw).hexdigest()==SOURCE_SHA
    blob=subprocess.run(['git','show',COMMIT+':'+SOURCE],cwd=repo,capture_output=True,check=True).stdout
    assert blob==raw,'Stable Git blob mismatch'
    target=wide(output/OUTPUT);target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(('package com.android.purebilibili.core.player\n\nimport java.net.URI\n\n'+selected(raw.decode())).encode())
    return target
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();generate(a.repo,a.output)

"""Fixed stable Comment URL declarations; one source producer, no Windows business rewrite."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, json, os
COMMIT = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
ORIGINAL = 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailSessionPolicy.kt'
SOURCE_SHA = 'fd76acf9f2b7c4a4a1d98c144c5b700986bdf0789a29b197ad082c163f18503e'
BODY_SHA = '911c09db9222fb439a47a1682d1a905973f5a719f4eacfd5241f0e7de711a32c'
OUTPUT = 'com/android/purebilibili/feature/video/screen/DesktopOriginalCommentUrlNavigation.kt'
def wide(p):
    value = os.path.abspath(p); prefix = chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def generate(repo, output):
    raw = wide(_desktop_canonical_source(repo, ORIGINAL)).read_bytes().replace(b'\r\n', b'\n')
    assert hashlib.sha256(raw).hexdigest() == SOURCE_SHA, 'Fixed stable source changed'
    text = raw.decode('utf-8')
    body = 'internal sealed interface CommentUrlNavigationTarget {' + text.split('internal sealed interface CommentUrlNavigationTarget {', 1)[1].split('internal fun resolveDanmakuDialogTopReservePx(', 1)[0].rstrip() + '\n'
    assert hashlib.sha256(body.encode()).hexdigest() == BODY_SHA, 'Selected original declarations changed'
    target = wide(output/OUTPUT); target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text('package com.android.purebilibili.feature.video.screen\n\n'+body, encoding='utf-8', newline='\n')
    return target
if __name__ == '__main__':
    ap=argparse.ArgumentParser(); ap.add_argument('--repo',type=Path,required=True); ap.add_argument('--output',type=Path,required=True)
    args=ap.parse_args(); generate(args.repo,args.output)

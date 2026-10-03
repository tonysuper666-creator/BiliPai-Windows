#!/usr/bin/env python3
"""Same original downloader body; Call.Factory final-publication seam only."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,json
SOURCE = 'app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt'
SOURCE_SHA = 'bffabd9dc119618ad842fee0d16e6e89e10ff813e2d30d2590ba68b47b3a4b5b'
def generate(repo: Path, output: Path) -> None:
    source=(_desktop_canonical_source(repo, SOURCE)).read_text(encoding="utf-8").replace("\r\n","\n")
    if hashlib.sha256(source.encode()).hexdigest()!=SOURCE_SHA:
        raise ValueError("Pinned original downloader identity changed")
    old="private val client: OkHttpClient"
    if source.count(old)!=1:raise ValueError("Original downloader transport boundary changed")
    body=source.replace(old,"private val client: okhttp3.Call.Factory")
    target=output/"com/android/purebilibili/feature/download/ResumableAssetDownloader.kt"
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(body,encoding="utf-8",newline="\n")
def inventory(repo: Path):
    source=(_desktop_canonical_source(repo, SOURCE)).read_text(encoding="utf-8").replace("\r\n","\n")
    return [dict(path=SOURCE,sha256=hashlib.sha256(source.encode()).hexdigest(),mode="platform-rewrite",features=["offline-download"])]
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--repo",type=Path,required=True);parser.add_argument("--output",type=Path);parser.add_argument("--inventory",action="store_true");args=parser.parse_args()
    if args.output:generate(args.repo,args.output)
    if args.inventory:print(json.dumps(inventory(args.repo),indent=2))

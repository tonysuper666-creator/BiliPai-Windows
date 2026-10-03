"""Preserve actual upstream Google Cast pure policies; exclude Android GMS objects."""
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse, hashlib, importlib.util, json, re, textwrap
from pathlib import Path
BASE = "app/src/main/java/com/android/purebilibili/feature/plugin/googlecast/"
SOURCES = {BASE+"GoogleCastRoutePolicy.kt":"platform-rewrite", BASE+"GoogleCastMediaLoader.kt":"policy-extract"}
def generate(repo, output):
    spec = importlib.util.spec_from_file_location("cast_kotlin_parser",repo/"desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec); spec.loader.exec_module(parser)
    def declaration(source, name, kind="fun"):
        tokens = parser.kotlin_tokens(source)
        hits = [i for i,t in enumerate(tokens[:-1]) if t[0]==kind and tokens[i+1][0]==name]
        assert len(hits)==1, name
        start=hits[0]; cursor=start
        while tokens[cursor][0]!="(":cursor+=1
        depth=1
        while depth:
            cursor+=1; depth+=(tokens[cursor][0]=="(")-(tokens[cursor][0]==")")
        if kind=="fun":
            while tokens[cursor][0]!="{":cursor+=1
            depth=1
            while depth:
                cursor+=1; depth+=(tokens[cursor][0]=="{")-(tokens[cursor][0]=="}")
        line=source.rfind("\n",0,tokens[start][1])+1
        return textwrap.dedent(source[line:tokens[cursor][2]])
    route=(_desktop_canonical_source(repo, BASE+"GoogleCastRoutePolicy.kt")).read_text(encoding="utf-8")
    assert route.count("import androidx.mediarouter.media.MediaRouter\n")==1
    route=route.replace("import androidx.mediarouter.media.MediaRouter\n","")
    loader=(_desktop_canonical_source(repo, BASE+"GoogleCastMediaLoader.kt")).read_text(encoding="utf-8")
    constants=re.findall(r'(?m)^    private const val (?:FALLBACK_TITLE|SESSION_TIMEOUT_MS) = .+$',loader)
    assert len(constants)==2
    body="package com.android.purebilibili.feature.plugin.googlecast\n\n"+declaration(loader,"GoogleCastMediaMetadataPolicy","class")+"\n\ninternal object GoogleCastMediaLoader {\n"+"\n".join(constants)+"\n\n"+textwrap.indent(declaration(loader,"resolveGoogleCastMediaMetadata"),"    ")+"\n\n"+textwrap.indent(declaration(loader,"shouldContinueWaitingForSession"),"    ")+"\n}\n"
    folder=output/"com/android/purebilibili/feature/plugin/googlecast";folder.mkdir(parents=True,exist_ok=True)
    (folder/"GoogleCastRoutePolicy.kt").write_text(route,encoding="utf-8",newline="\n")
    (folder/"GoogleCastMediaLoader.kt").write_text(body,encoding="utf-8",newline="\n")
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--repo",type=Path,required=True);parser.add_argument("--output",type=Path);parser.add_argument("--inventory",action="store_true");args=parser.parse_args()
    if args.inventory: print(json.dumps([dict(path=p,mode=m,features=["google-cast-v2"],sha256=hashlib.sha256((_desktop_canonical_source(args.repo, p)).read_text(encoding="utf-8").encode()).hexdigest()) for p,m in SOURCES.items()],indent=2))
    if args.output:generate(args.repo,args.output)

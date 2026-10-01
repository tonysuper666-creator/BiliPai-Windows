"""Actual Main PNG writer; only task fixture classes, no production overrides."""
from pathlib import Path
import hashlib, importlib.util, json, os, shutil, subprocess, sys, tempfile, zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
SNAP=REPO/"desktop/.local/dynamic-detail-container-main-integration/main-product-snapshot-02"
MANIFEST="f164846bb1ebe5545c727824bfa6fe853a5fca92bfd1bcfddf59abb53c943d17"
CP="be8a62821f74e73653ed2354afbb1de3d6ec8236db68f056328a482a5e0f4d9d"
def ext(path):
    value=str(Path(path).resolve());return Path(value if value.startswith("\\\\?\\") else "\\\\?\\"+value)
def raw(path):return ext(path).read_bytes()
def sha(path):return hashlib.sha256(raw(path)).hexdigest()
def lf(path):return raw(path).replace(b"\r\n",b"\n")
def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);assert not path.exists()
    path.write_text(value,encoding="utf-8",newline="\n")
def save(path,value):write(path,json.dumps(value,ensure_ascii=False,indent=2)+"\n")
def argsfile(path,values):write(path,"\n".join('"'+str(v).replace('\\','/')+'"' for v in values)+"\n")
def block(text,start):
    pos=text.index(start);opening=text.index("{",pos);depth=0
    for i in range(opening,len(text)):
        if text[i]=="{":depth+=1
        elif text[i]=="}":
            depth-=1
            if depth==0:return text[pos:i+1]
    raise AssertionError("unbalanced source")

attempt=sys.argv[1];assert attempt.isalnum()
out=HERE/"runs"/attempt;assert not out.exists();out.mkdir(parents=True)
assert sha(SNAP/"manifest.json")==MANIFEST and sha(SNAP/"ordered-runtime-cp.json")==CP
manifest=json.loads(raw(SNAP/"manifest.json"));cp=json.loads(raw(SNAP/"ordered-runtime-cp.json"));assert len(cp)==89
for row in cp:assert sha(row["path"])==row["sha256Bytes"]
main=next(row["path"] for row in cp if row.get("source")=="desktop/build/classes/kotlin/main")
pins={row["path"]:row["sha256Lf"] for row in manifest["sourceFiles"]}
selected=["desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt",
          "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCommentImageCanvas.kt",
          "desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSessionStore.kt",
          "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicCardSession.kt"]
sources=[]
for rel in selected:
    p=REPO/rel;assert hashlib.sha256(lf(p)).hexdigest()==pins[rel],rel
    copy=out/"actual-source-pins"/Path(rel).name;copy.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,copy)
    sources.append({"path":rel,"sha256Lf":pins[rel],"rawCopySha256Bytes":sha(copy)})
root=lf(REPO/selected[0]).decode("utf-8")
detail=root[root.index("internal fun CommunityDynamicDetail"):]
assert "val exportOwner=remember(alive){checkNotNull(exportGuard.dynamicCacheOwner())}" in root
owned=detail[detail.index("fun owned()="):].splitlines()[0][len("fun owned()="):]
assert owned=="alive.get()&&exportOwner.epoch==capturedEpoch&&cardSession.isOwned()&&repository.sessionEpoch==capturedEpoch"
commit=block(detail,"exportGuard.withCurrentDynamicCacheOwner(exportOwner){")
dispose=block(detail,"onDispose{")[len("onDispose{"):-1]
# Require the selected disposal belongs to this page owner, not another screen.
assert dispose=="synchronized(exportOwnerLock){alive.set(false)};replySession.close();pageScope.cancel()"
extracted=out/"RootGateExtracted.kt"
write(extracted,"package com.bilipai.desktop.ui.pngproof\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.cancel\n\n"+
    "internal fun rootOwned(page:FixturePageOwner):Boolean = with(page) { "+owned+" }\n"+
    "internal fun rootCommit(page:FixturePageOwner,commit:()->Unit):Boolean = with(page) {\n"+commit+"\n}\n"+
    "internal fun rootDispose(page:FixturePageOwner) = with(page) {\n"+dispose+"\n}\n")
fixture=out/"PngOwnerFixture.kt";shutil.copyfile(HERE/fixture.name,fixture)
save(out/"source-extraction.json",{"actualMainManifestSha256Bytes":MANIFEST,"actualOrdered89CpSha256Bytes":CP,
    "actualSourcePins":sources,"extractedOwnershipExpression":owned,"extractedFinalGateExpression":commit,"extractedDisposeExpression":dispose,
    "extractionNoBodyChanges":True,"RootScreenOrRememberExecuted":False,
    "newCaptureExpressionSourceVerified":True,"oldCaptureExpressionProbeDeclared":True,
    "extractedSourceSha256Bytes":sha(extracted),"fixtureSha256Bytes":sha(fixture)})
spec=importlib.util.spec_from_file_location("png_compiler",REPO/"desktop/.local/source9-appearance/compile-miuix.py")
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
jar=out/"fixture-only.jar"
values=["-no-stdlib","-no-reflect","-jvm-target","21","-cp",";".join(r["path"] for r in cp),
    "-Xfriend-paths="+main,"-module-name","actual_main_comment_png_owner_proof","-d",jar,fixture,extracted]
argsfile(out/"compiler.args",values)
r=subprocess.run([str(c.JAVA),"-Dfile.encoding=UTF-8","-Xmx2g","-cp",";".join(map(str,c.COMPILER)),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","@"+str(out/"compiler.args")],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=90)
write(out/"compile.log",r.stdout+r.stderr)
if r.returncode:
    save(out/"failure.json",{"phase":"compile","exitCode":r.returncode,"productionRun":False});print(r.stdout+r.stderr);r.check_returncode()
existing=set()
for row in cp:
    with zipfile.ZipFile(ext(row["path"])) as z:existing.update(z.namelist())
with zipfile.ZipFile(jar) as z:classes={n for n in z.namelist() if n.endswith(".class")}
assert not(classes&existing),classes&existing
assert all(n.startswith("com/bilipai/desktop/ui/pngproof/") for n in classes)
save(out/"compile-evidence.json",{"passed":True,"fixtureJarSha256Bytes":sha(jar),"productionClassOverlap":[],
    "ordered89Classpath":cp,"actualMainManifestSha256Bytes":MANIFEST,"fixtureClasses":sorted(classes)})
home=Path(tempfile.mkdtemp(prefix="bp-png-main-"));env=os.environ.copy()
for name in ("APPDATA","LOCALAPPDATA","USERPROFILE","TEMP","TMP"):
    p=home/name.lower();p.mkdir();env[name]=str(p)
proof=out/"proof"
values=["-Dfile.encoding=UTF-8","-Djava.awt.headless=true","-Duser.home="+str(home),"-Djava.io.tmpdir="+str(home/"temp"),
    "-cp",";".join([str(jar)]+[r["path"] for r in cp]),"com.bilipai.desktop.ui.pngproof.PngOwnerFixtureKt",proof,main]
argsfile(out/"run.args",values)
r=subprocess.run([str(c.JAVA),"@"+str(out/"run.args")],cwd=REPO,env=env,capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=60)
write(out/"run.log",r.stdout+r.stderr);print(r.stdout+r.stderr)
if r.returncode:
    save(out/"failure.json",{"phase":"runtime","exitCode":r.returncode,"actualMainRun":True,"productBugNotAutomaticallyInferred":True});r.check_returncode()
result=json.loads(raw(proof/"result.json"));assert result["passed"]
from PIL import Image
independent=[]
for name in ("chosen-comment.png","guest-comment.png"):
    with Image.open(proof/name) as image:
        assert image.format=="PNG" and image.width==1080;image.load()
        independent.append({"path":name,"width":image.width,"height":image.height,"sha256Bytes":sha(proof/name)})
for row in cp:assert sha(row["path"])==row["sha256Bytes"]
save(out/"accepted-evidence.json",{**result,"actualMainManifestSha256Bytes":MANIFEST,"actualOrdered89CpSha256Bytes":CP,
    "fixtureJarSha256Bytes":sha(jar),"productionClassOverlap":[],"independentPillowPngReadback":independent,
    "taskPrivateRuntimeHome":str(home),"actualCompleteRootScreenExecuted":False,"actualNativeChooser":False,
    "sourceExtractionReceiptSha256Bytes":sha(out/"source-extraction.json")})
print("PASS independent PNG readback, all actual Main 89 CP hashes unchanged, no production overlap")

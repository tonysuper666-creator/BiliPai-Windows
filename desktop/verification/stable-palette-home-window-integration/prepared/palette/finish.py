from pathlib import Path
import json,hashlib,subprocess,textwrap,re
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def read(p):return p.read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(read(p).encode()).hexdigest()
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def js(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2))
pins=json.loads(read(LANE/'source-pins.json'));path=pins['upstreamPath'];commit=pins['upstreamCommit']
prepared=LANE/'prepared/generated/com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt'
code=read(LANE/'prepare.py').split('store=original\n',1)[1].split("write(LANE/'prepared/generated",1)[0]
code=code.replace("read(LANE/'store-methods.template.kt')",repr(read(LANE/'store-methods.template.kt')))
producer='''"""Full original v0.2.3 WallpaperPaletteStore; required owned Windows context.
The schema and original quantizer are supplied by their existing sole producers.
No external dependency, settings namespace or image/network owner is created.
"""
from pathlib import Path
import argparse,hashlib,json,re,subprocess
'''+f"COMMIT={commit!r}\nPATH={path!r}\nORIGINAL_SHA={pins['originalSha256LF']!r}\nPREPARED_SHA={lfsha(prepared)!r}\n"+'''
def digest(text):return hashlib.sha256(text.encode()).hexdigest()
def write(path,text):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\\n')
def adapt(original):
 store=original
'''+textwrap.indent(code,' ')+' return store\n'+'''
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--source-repo',required=True);parser.add_argument('--output-dir',required=True);args=parser.parse_args()
 repo=Path(args.source_repo).resolve();out=Path(args.output_dir).resolve()
 manifest=json.loads((repo/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
 assert manifest['upstreamCommit']==COMMIT,'WallpaperPalette stable source identity changed'
 original=(repo/PATH).read_text(encoding='utf-8').replace('\\r\\n','\\n')
 assert digest(original)==ORIGINAL_SHA,'WallpaperPalette source content changed'
 blob=subprocess.run(['git','show',COMMIT+':'+PATH],cwd=repo,capture_output=True,check=True).stdout.decode().replace('\\r\\n','\\n')
 assert blob==original,'WallpaperPalette source differs from pinned Git blob'
 rows=[r for r in manifest['sources'] if r['path']==PATH]
 assert len(rows)==1 and rows[0]['sha256']==ORIGINAL_SHA and rows[0]['mode']=='policy-extract','WallpaperPalette sole registry row missing/different'
 store=adapt(original);assert digest(store)==PREPARED_SHA,'WallpaperPalette Windows adaptation changed'
 write(out/'com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt',store)
 proof={'pinnedCommit':COMMIT,'originalPath':PATH,'originalSha256LF':ORIGINAL_SHA,'preparedSha256LF':PREPARED_SHA,'wholeOriginalObjectRetained':True,'windowsOwnedInstance':True,'schemaAndQuantizerProduced':False,'generatedSources':1}
 write(out/'wallpaper-palette-selection-proof.json',json.dumps(proof,indent=2))
 print('Generated full original WallpaperPaletteStore with owned Windows services')
if __name__=='__main__':main()
'''
write(LANE/'prepared/tools/extract-upstream-wallpaper-palette.py',producer)
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={r['path']:r for r in registry['sources']}
assert path not in existing
row={'path':path,'sha256':pins['originalSha256LF'],'mode':'policy-extract','features':['home-wallpaper-palette-original'],'existingIdentity':False,'newSoleDefinition':True}
schema='app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCardAdaptiveTintPolicy.kt'
assert existing[schema]['mode']=='direct'
js(LANE/'registry-merge-recipe.json',{'pinnedCommit':commit,'observedRegistryRows':len(existing),'observedRegistrySha256Bytes':sha(REPO/'desktop/upstream-sources.json'),'records':[row],'newProductionRows':1,'referenceOnlyExistingSchema':existing[schema],'mergeExistingModeAndFeatures':True})
write(LANE/'gradle-tasks.snippet.kts','''// Merge source/task only; no new dependency/JAR or duplicate direct schema.
val extractOriginalWallpaperPalette by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-wallpaper-palette.py",
        "--source-repo", repositoryRoot.absolutePath,
        "--output-dir", layout.buildDirectory.dir("generated/wallpaper-palette").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-wallpaper-palette.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-wallpaper-palette-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/wallpaper-palette"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/wallpaper-palette")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalWallpaperPalette) }
// New manual Java sources live in existing desktop/src/main/java; default Java task compiles them.
''')
payloads=[]
for local,target in [
 ('prepared/tools/extract-upstream-wallpaper-palette.py','desktop/tools/extract-upstream-wallpaper-palette.py'),
 ('prepared/manual/com/bilipai/desktop/ui/DesktopWallpaperPalettePlatform.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWallpaperPalettePlatform.kt'),
 ('prepared/manual-java/com/bilipai/desktop/palette/DesktopPaletteTarget.java','desktop/src/main/java/com/bilipai/desktop/palette/DesktopPaletteTarget.java'),
 ('prepared/manual-java/com/bilipai/desktop/palette/DesktopWallpaperPaletteScoring.java','desktop/src/main/java/com/bilipai/desktop/palette/DesktopWallpaperPaletteScoring.java')]:
 payloads.append({'source':str(LANE/local),'target':target,'sha256Bytes':sha(LANE/local),'sha256LF':lfsha(LANE/local),'newSoleDefinition':True})
js(LANE/'install-contract.json',{'pinnedCommit':commit,'payloads':payloads,'generatedProductionSources':1,'manualKotlinSources':1,'manualJavaSources':2,'generatedPreparedFilesInstall':False,'jarInstall':False,'newDependencies':0,'secondQuantizer':False,'existingSchemaDirectReused':schema,'rootBindings':'ROOT-INTEGRATION.md','registryRecipe':'registry-merge-recipe.json','gradleRecipe':'gradle-tasks.snippet.kts','sharedSourceHunks':0,'rootMounted':False})
js(LANE/'official-selection-receipt.json',{'artifact':'androidx.palette:palette:1.0.0:sources','originalSourceJarSha256Bytes':pins['officialSourcesJarSha256Bytes'],'originalTargetSha256LF':pins['officialTargetSha256LF'],'originalPaletteSha256LF':pins['officialPaletteSha256LF'],'fullTargetRetained':True,'selectedMethods':json.loads(read(LANE/'selection-methods.json'))['officialSelectedMethods'],'collectionSeams':['SparseBooleanArray→HashSet<Integer>','ArrayMap→HashMap'],'targetNameSeam':'Target→DesktopPaletteTarget','defaultTargetOrder':['LIGHT_VIBRANT','VIBRANT','DARK_VIBRANT','LIGHT_MUTED','MUTED','DARK_MUTED'],'existingDesktopPaletteQuantizer':True,'sourceOnlyNoJarDependency':True,'license':'Apache-2.0; original source headers retained in both manual Java files'})
print('Prepared 4 install payloads, 1 original registry row and Root recipes')

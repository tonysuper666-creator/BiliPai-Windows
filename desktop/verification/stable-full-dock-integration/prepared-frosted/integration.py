from pathlib import Path
import importlib.util,json,hashlib,sys
sys.dont_write_bytecode=True;HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def save(p,x):write(p,json.dumps(x,ensure_ascii=False,indent=2)+'\n')
patches=[]
p='desktop/tools/extract-upstream-shared-liquid-tabs.py';before=read(REPO/p)
line=next(l for l in before.splitlines() if "emit(HOME+'BottomBar.kt'" in l)
afterline=line.replace('imports=imports,transforms=[','imports=imports,transforms=[(\'private val iosIndicatorSpecular\',\'internal val iosIndicatorSpecular\'),',1)
assert afterline!=line and before.count(line)==1
patches.append(dict(target=p,baseSHA256LF=sha(before),before=line,after=afterline,resultSHA256LF=sha(before.replace(line,afterline,1)),purpose='Only existing sole original specular value visibility widened. No second BottomBarKt or copied helper.'))
p='desktop/tools/extract-upstream-linked-dock.py';before=read(REPO/'desktop/.local/stable-linked-dock-parity/prepared'/p)
old="  p=BASE+rel+'.kt';emit(rel+'.kt',original[p],p)"
new="  p=BASE+rel+'.kt'\n  registry={r['path']:r for r in json.loads(read(repo/'desktop/upstream-sources.json'))['sources']}\n  if registry[p].get('mode')!='direct':emit(rel+'.kt',original[p],p)"
assert before.count(old)==1
patches.append(dict(target=p,baseSHA256LF=sha(before),before=old,after=new,resultSHA256LF=sha(before.replace(old,new,1)),base='historical frozen e5eb03 prepared producer (not installed yet)',purpose='Reuse actual17 Favorites direct HomeScrollOffsetPolicy. Any exact source direct policy is synced solely by Root prepareUpstreamSources.'))
before=before.replace(old,new,1)
old=" emit('feature/home/DesktopOriginalLinkedDockScrollLocals.kt','package com.android.purebilibili.feature.home\\nimport androidx.compose.runtime.*\\n\\n'+decl.declarations(parser,original[p],names),p,names)"
new=" if 'stable-favorites' not in registry[p].get('features',[]):\n "+old
assert before.count(old)==1
patches.append(dict(target=p,baseSHA256LF=sha(before),before=old,after=new,resultSHA256LF=sha(before.replace(old,new,1)),base='sequential after previous LinkedDock DIRECT-skip hunk',purpose='Reuse actual17 Favorites sole DesktopFavoriteScrollLocalsKt exact two original locals; never create second CompositionLocal authority.'))
save(HERE/'existing-sole-producer-hunks.json',dict(status='PREPARED_ONLY',hunks=patches))
states={}
for row in patches:
 base=states.get(row['target']) or (read(REPO/row['target']) if (REPO/row['target']).exists() else read(REPO/'desktop/.local/stable-linked-dock-parity/prepared'/row['target']))
 states[row['target']]=base.replace(row['before'],row['after'],1)
 assert sha(base)==row['baseSHA256LF'];write(HERE/'prepared-existing-producer-review'/row['target'],base.replace(row['before'],row['after'],1))
write(HERE/'gradle-install-fragment.kt.txt','''// Root serial integration recipe only; no Gradle or live source mutation was run here.
val extractFrostedAudioRenderer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-frosted-audio-renderer.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/frosted-audio-renderer").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-frosted-audio-renderer.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-frosted-audio-renderer" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/frosted-audio-renderer"))
}
tasks.named("compileKotlin") { dependsOn(extractFrostedAudioRenderer) }
// Existing kotlin source set, exactly once:
// kotlin.srcDir(layout.buildDirectory.dir("generated/frosted-audio-renderer"))
''')
print('Prepared three existing sole producer hunks and Root-only task recipe')

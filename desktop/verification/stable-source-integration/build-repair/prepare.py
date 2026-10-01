from pathlib import Path
import difflib, hashlib, importlib.util, json, os, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
STABLE='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
    v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,t):
    assert Path(p).absolute().is_relative_to(HERE)
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def dump(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def digest(t):return hashlib.sha256(t.encode()).hexdigest()
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def module(n,p):
    spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def replace(t,a,b,count=1):assert t.count(a)==count,a;return t.replace(a,b)
paths=['desktop/tools/extract-upstream-diagnostics.py','desktop/tools/extract-upstream-dynamic-tabs.py','desktop/tools/extract-upstream-plugins.py']
rows=[];patch=[]
for path in paths:
    old=read(REPO/path);new=old
    if path.endswith('extract-upstream-diagnostics.py'):
        new=replace(new,"CRASH=BASE+'core/util/CrashReporter.kt'", "CRASH=BASE+'core/util/CrashReporter.kt'\nEXIT=BASE+'core/performance/Android17Diagnostics.kt'\nNATIVE=BASE+'core/performance/NativeExitTrace.kt'")
        new=replace(new," host=load(repo/'desktop/tools/extract-upstream-plugins.py','diaghost');media=host.media_extractor(repo);parser=media.parser_for(repo)",
            " host=load(repo/'desktop/tools/extract-upstream-plugins.py','diaghost');media=host.media_extractor(repo);parser=media.parser_for(repo)\n identity=load(repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py','diag_fixed_identity')\n fixed_sources,source_ids=identity.load_pinned_sources(repo,[LOGGER,SETTINGS,SECTIONS,CRASH,EXIT,NATIVE])")
        new=replace(new," out.mkdir(parents=True,exist_ok=True);files=[];original=read(repo,LOGGER)",
            " out.mkdir(parents=True,exist_ok=True);files=[];original=fixed_sources[LOGGER]\n declaration=load(repo/'desktop/tools/extract-appearance-platform.py','diag_original_exception')\n exception=declaration.declarations(parser,fixed_sources[EXIT],['AbnormalProcessExitException'])\n files.append(host.write(out,EXIT,fixed_sources[EXIT],'package com.android.purebilibili.core.performance\\nimport java.io.PrintWriter\\nimport java.io.PrintStream\\n'+exception,'DesktopOriginalAbnormalProcessExitException.kt'))\n # Pure JVM original tombstone encoding/parsing/summary stays source-owned.\n files.append(host.write(out,NATIVE,fixed_sources[NATIVE],fixed_sources[NATIVE],'DesktopOriginalNativeExitTrace.kt'))\n (out/'source-identity.json').write_text(json.dumps(source_ids,indent=2)+'\\n',encoding='utf-8')")
        new=replace(new,"'package com.android.purebilibili.core.util\\nimport java.io.File\\nimport java.text.SimpleDateFormat\\nimport java.util.*\\n\\n'+pure",
            "'package com.android.purebilibili.core.util\\nimport com.android.purebilibili.core.performance.AbnormalProcessExitException\\nimport com.android.purebilibili.core.performance.nativeExitTraceSummary\\nimport java.io.File\\nimport java.text.SimpleDateFormat\\nimport java.util.*\\n\\n'+pure")
        for name in ['SETTINGS','SECTIONS','CRASH']:new=new.replace('original=read(repo,'+name+')','original=fixed_sources['+name+']')
        new=replace(new,'for p in [LOGGER,SETTINGS,SECTIONS,CRASH]]','for p in [LOGGER,SETTINGS,SECTIONS,CRASH,EXIT,NATIVE]]')
    elif path.endswith('extract-upstream-dynamic-tabs.py'):
        new=replace(new,'import com.android.purebilibili.core.ui.components.AppIconButton as IconButton',
            'import com.android.purebilibili.core.ui.components.AppIconButton\nimport com.android.purebilibili.core.ui.components.AppIconButton as IconButton')
    else:
        marker='    generated.append(write(output, path, source, body))\n\n    path = BASE + "feature/plugin/SponsorBlockPlugin.kt"'
        new=replace(new,marker,'    body = substitute(body, "android.os.SystemClock.elapsedRealtime()", "com.bilipai.desktop.appearance.DesktopMonotonicClock.elapsedRealtime()", count=2)\n'+marker)
    write(HERE/'original-tools'/Path(path).name,old);write(HERE/'prepared'/path,new)
    rows.append(dict(path=path,baseLfSha256=digest(old),candidateLfSha256=digest(new)))
    patch+=list(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='a/'+path,tofile='b/'+path))
write(HERE/'candidate.patch',''.join(patch))

base='app/src/main/java/com/android/purebilibili/'
helpers=[base+'core/performance/Android17Diagnostics.kt',base+'core/performance/NativeExitTrace.kt']
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'));assert manifest['upstreamCommit']==STABLE
registry=[];helper_ids=[]
for path in helpers:
    stable=subprocess.check_output(['git','-c','core.longpaths=true','show',STABLE+':'+path],cwd=REPO).decode('utf-8').replace('\r\n','\n')
    assert read(REPO/path)==stable,path
    blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',STABLE+':'+path],cwd=REPO,text=True).strip()
    current=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+path,path],cwd=REPO,text=True).strip();assert blob==current
    row=dict(path=path,sha256=digest(stable),features=['settings-local-diagnostics-parity'],mode='policy-extract')
    if not any(r['path']==path for r in manifest['sources']):manifest['sources'].append(row)
    else:assert next(r for r in manifest['sources'] if r['path']==path)['sha256']==row['sha256']
    registry.append(row);helper_ids.append(dict(path=path,sha256Lf=digest(stable),pinnedCommit=STABLE,pinnedGitBlob=blob,currentGitBlob=current))
    write(HERE/'original-stable'/path,stable)
dump(HERE/'registry-append-rows.json',registry);dump(HERE/'new-helper-source-identity.json',helper_ids)
shadow=HERE/'source-shadow'
git_dir=subprocess.check_output(['git','rev-parse','--absolute-git-dir'],cwd=REPO,text=True).strip();write(shadow/'.git','gitdir: '+git_dir+'\n')
dump(shadow/'desktop/upstream-sources.json',manifest)
for p in ['.gitattributes']:
    if (REPO/p).exists():write(shadow/p,read(REPO/p))
for p in safe(REPO/'desktop/tools').iterdir():
    if p.is_file() and p.suffix=='.py':
        relative='desktop/tools/'+p.name;prepared=HERE/'prepared'/relative
        write(shadow/relative,read(prepared) if safe(prepared).exists() else read(p))
source_paths=[base+n for n in ['core/util/Logger.kt','core/store/SettingsManager.kt','feature/settings/ui/SettingsSections.kt','core/util/CrashReporter.kt',
    'feature/settings/SettingsSemanticIconPolicy.kt','feature/settings/SettingsEntryVisualPolicy.kt']]+helpers+['app/src/main/res/drawable/ms_pest_control_24.xml']
for p in source_paths:write(shadow/p,read(REPO/p))
diag=module('prepared_stable_diagnostics',HERE/'prepared'/paths[0])
diag_files=diag.generate(shadow,safe(HERE/'generated/diagnostics/sources'))
tabs=module('prepared_stable_tabs',HERE/'prepared'/paths[1]);tab_files=tabs.generate(REPO,safe(HERE/'generated/dynamic-tabs'))
plugins=module('prepared_stable_plugins',HERE/'prepared'/paths[2]);plugin_files=plugins.generate(REPO,safe(HERE/'generated/plugins'))
targets=[p for p in diag_files if p.name in ['DesktopDiagnosticPolicy.kt','DesktopDiagnosticCollector.kt','DesktopOriginalAbnormalProcessExitException.kt','DesktopOriginalNativeExitTrace.kt']]
targets+=[p for p in tab_files if p.name=='DesktopOriginalDynamicTabsFields.kt']
targets+=[p for p in plugin_files if p.name=='SponsorBlockRepository.kt']
assert len(targets)==6
dump(HERE/'generated-targets.json',[dict(path=str(p).removeprefix(EXT),sha256Bytes=sha(p)) for p in targets])
dump(HERE/'candidate-source-inventory.json',dict(fixedStableCommit=STABLE,sourceCandidates=rows,registryAppendRows=registry,
    baselineManifestSha256=sha(REPO/'desktop/upstream-sources.json'),preparedSourceShadowManifestSha256=sha(shadow/'desktop/upstream-sources.json'),
    generatedCounts=dict(diagnostics=len(diag_files),tabs=len(tab_files),plugins=len(plugin_files)),MainWrittenByThisLane=False,sharedGradle=False))
gradle=read(REPO/'desktop/build.gradle.kts')
anchor='    inputs.files("tools/extract-upstream-diagnostics.py", "tools/extract-upstream-plugins.py",'
assert gradle.count(anchor)==1
modified=gradle.replace(anchor,'    inputs.files("tools/extract-upstream-diagnostics.py", "tools/extract-upstream-dynamic-reply-protocol.py", "tools/extract-appearance-platform.py", "upstream-sources.json", "tools/extract-upstream-plugins.py",')
write(HERE/'gradle-input-only.patch',''.join(difflib.unified_diff(gradle.splitlines(True),modified.splitlines(True),fromfile='a/desktop/build.gradle.kts',tofile='b/desktop/build.gradle.kts')))
print(json.dumps(dict(candidates=rows,registryAppendRows=registry,generatedCounts=dict(diagnostics=len(diag_files),tabs=len(tab_files),plugins=len(plugin_files))),indent=2))

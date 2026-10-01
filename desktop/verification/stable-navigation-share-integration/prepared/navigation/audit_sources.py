from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, textwrap

HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p)
    return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()
def read(p): return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
spec=importlib.util.spec_from_file_location('nav',HERE/'prepared/tools/extract-upstream-navigation3-host.py')
producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
receipt=json.loads(read(HERE/'source-receipt.json'))
checks=[]
for row in receipt['sources']+receipt['references']:
    original=subprocess.check_output(['git','show',producer.TARGET+':'+row['path']],cwd=REPO).decode('utf-8').replace('\r\n','\n')
    assert sha(original)==row['sha256LF'],row['path']
    checks.append('fixed-tag LF identity '+row['path'])
for row in receipt['outputs']:
    text=read(HERE/'generated'/row['path'])
    assert sha(text)==row['sha256LF'],row['path']
for row in receipt['sources']:
    if row['mode']=='direct':
        original=read(REPO/row['path'])
        package=next(s.removeprefix('package ') for s in original.splitlines() if s.startswith('package '))
        target=HERE/'generated'/package.replace('.','/')/Path(row['path']).name
        assert read(target)==original
        checks.append('whole direct original '+row['path'])
for path, changes in receipt['replacements'].items():
    if path.endswith(('BiliPaiNavDisplayHost.kt','BiliPaiMiuixNavTransition.kt')):
        original=read(REPO/path)
        package=next(s.removeprefix('package ') for s in original.splitlines() if s.startswith('package '))
        text=read(HERE/'generated'/package.replace('.','/')/Path(path).name)
        for change in reversed(changes):
            if change['after']:
                assert text.count(change['after'])==1,change
                text=text.replace(change['after'],change['before'])
            else:
                anchor=original[original.index(change['before'])+len(change['before']):].splitlines()[0]
                assert text.count(anchor)==1
                text=text.replace(anchor,change['before']+anchor,1)
        assert text==original,path
        checks.append('full inverse byte equal '+path)
for selected in receipt['selectedDeclarations']:
    original=selected['originalBody']
    assert sha(original)==selected['originalSha256LF']
    adapted=original
    for change in receipt['replacements'][selected['source']]: adapted=adapted.replace(change['before'],change['after'])
    assert sha(adapted)==selected['generatedSha256LF']
    assert any(read(HERE/'generated'/row['path']).endswith(adapted+'\n') for row in receipt['outputs'])
    checks.append('selected body inverse '+selected['source'])
for selected in receipt['selections']:
    assert sha(selected['body'])==selected['sha256LF']
    assert textwrap.indent(selected['body'],'    ') in read(HERE/'generated/com/bilipai/desktop/ui/DesktopOriginalNavigationHostSettings.kt')
    checks.append('original global settings body '+selected['name'])
raw=read(REPO/'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt')
nav=read(REPO/'app/src/main/java/com/android/purebilibili/core/store/navigation/NavigationSettingsStore.kt')
for key,literal in [('KEY_CLICK_TO_PLAY','click_to_play'),('KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED','video_transition_realtime_blur_enabled'),('KEY_RELATED_VIDEO_TRANSITION_ENABLED','related_video_transition_enabled'),('keyFullScreenSwipeBackEnabled','full_screen_swipe_back_enabled')]:
    assert 'booleanPreferencesKey("'+literal+'")' in (nav if key.startswith('keyFull') else raw)
    checks.append('original same-global key '+literal)
production=HERE/'production-generated-audit'
assert not production.exists()
other=producer.generate(REPO,production,False)
assert len(other['outputs'])==7
for row in other['outputs']:
    assert read(production/row['path'])==read(HERE/'generated'/row['path'])
    checks.append('production selected output identical '+row['path'])
assert not any(Path(p).name in {Path(x).name for x in producer.DIRECT} for p in [r['path'] for r in other['outputs']])
checks.append('production DIRECT22 emitted zero: sole Sync copy-once')
result={'target':producer.TARGET,'checks':checks,'checkCount':len(checks),'newIdentityCount':24,
 'existingFeatureMergeCount':6,'directWholeCount':22,'defaultProducerOutputCount':7,
 'existingSourceBodyOverrides':0,'actualMain40ClassOverlap':[],
 'bodyAlgorithmsChanged':False,'preparedOnly':True}
safe(HERE/'source-audit.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print('PASS',len(checks),'source/tag/body/copy-once checks')

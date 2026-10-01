from pathlib import Path
import hashlib,json,re
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023';OUT=HERE/'playback-publication-install45/native-test-contract-repair';assert not OUT.exists()
rows=[];pending=[]
for name in ['MpvStartupProbeTest.kt','DesktopAdvancedPlaybackSettingsTest.kt','DesktopPremiumAudioRecoveryTest.kt']:
 relative='desktop/src/test/kotlin/com/bilipai/desktop/player/'+name;p=REPO/relative;raw=p.read_bytes();text=raw.decode().replace('\r\n','\n')
 marker=re.search(r'(?m)^( +)override fun mpv_free\(data: Pointer\).*$',text);assert marker and text.count(marker.group())==1;indent=marker.group(1)
 methods='''override fun mpv_render_context_create(result: com.sun.jna.ptr.PointerByReference, handle: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
override fun mpv_render_context_set_update_callback(context: Pointer, callback: MpvRenderUpdateCallback, data: Pointer?): Unit = error("Unexpected software render call in memory-only native fixture")
override fun mpv_render_context_update(context: Pointer): Long = error("Unexpected software render call in memory-only native fixture")
override fun mpv_render_context_render(context: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
override fun mpv_render_context_free(context: Pointer): Unit = error("Unexpected software render call in memory-only native fixture")
'''
 assert 'override fun mpv_render_context_create'not in text;before=marker.group();after='\n'.join(indent+s for s in methods.splitlines())+'\n'+before;result=text.replace(before,after,1).encode()
 rows.append(dict(path=relative,before=before,after=after,baseSha256LF=hashlib.sha256(text.encode()).hexdigest(),candidateSha256LF=hashlib.sha256(result).hexdigest(),originalAssertionsUnchanged=True,unexpectedRenderFailsExplicitly=True))
 pending.append((p,raw,result))
OUT.mkdir()
for p,raw,result in pending:
 (OUT/p.name).write_bytes(raw);p.write_bytes(result)
(OUT/'repair.json').write_text(json.dumps(dict(reason='Whole test compilation found three older memory-only native fixtures missing the five same-core render interface methods introduced before Playback45',rows=rows,productSourcesChanged=False),indent=2)+'\n',encoding='utf-8')
print('Three memory-only native fixture contracts completed; original assertions unchanged')

from pathlib import Path
import hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf8')
root=Path(__file__).resolve().parent
main=root.parents[2]
candidate=main.parent/'BiliPai-v023'
target='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoSectionWindowsPlatform.kt'
base='efba03e9b8a4b5eaec9189ea297db00144b1969d'
old=subprocess.check_output(['git','show',base+':'+target],cwd=candidate)
after=(candidate/target).read_bytes()
before_fragment=b'    override fun readViewportBrightness(): Float { val value=expected(); return resources.overlay.viewportBrightnessFor(value.sourceVersion) ?: 1f }'
after_fragment=b'''    override fun readViewportBrightness(): Float {
        // The original PGC view exists before detail/playurl succeeds. Until a
        // source owns the dimmer, its actual viewport has full brightness.
        // This read cannot grant a source or relax any presentation write.
        val value = resources.native.current() ?: return 1f
        return if (owns(value)) resources.overlay.viewportBrightnessFor(value.sourceVersion) ?: 1f else 1f
    }'''
assert old.count(before_fragment)==1
assert old.replace(before_fragment,after_fragment).replace(b'\r\n',b'\n')==after.replace(b'\r\n',b'\n')
sha=lambda b:hashlib.sha256(b).hexdigest()
record=dict(baseCommit=base,sourceCount=1234,resourceCount=244,sourceTargets=[dict(path=target,beforeSha256Bytes=sha(old),afterSha256Bytes=sha(after))],
    priorActualWindow='actual92-03',actualWindowFailure='Original PGC Loading/Error reads brightness before any accepted media source',
    sourceWriteAdmissionUnchanged=True,normalCompile=False,rootRuntimeAccepted=False)
(root/'installation.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf8')
(root/'brightness-exact-delta.json').write_text(json.dumps(dict(path=target,before=before_fragment.decode(),after=after_fragment.decode()),indent=2)+'\n',encoding='utf8')
(root/'generated-verification.json').write_text(json.dumps(dict(passed=True,newProducerCount=0,fullOriginalBodiesChanged=0,exactPlatformHunks=1,normalProductCompile=False),indent=2)+'\n',encoding='utf8')
print('Exact one-method platform delta PASS; native writes unchanged.')

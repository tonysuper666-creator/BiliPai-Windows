"""Complete fixed v029 cache-clear UI, consumed only by the existing storage producer.

The immutable canonical CacheClearUiPolicy is already identical and is not emitted
again. Three presentation ports adapt SDK capability/back input to the real desktop
bindings. Owner cache mutations never occur in this module or original UI body.
"""
from pathlib import Path
import hashlib, json, os

ROOT = Path(__file__).resolve().parents[1] / 'upstream-slices/v029-brand-cache-clear'
COMMIT = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
ANIMATION = 'app/src/main/java/com/android/purebilibili/feature/settings/ui/CacheClearAnimation.kt'
POLICY = 'app/src/main/java/com/android/purebilibili/feature/settings/CacheClearUiPolicy.kt'
TEST = 'app/src/test/java/com/android/purebilibili/feature/settings/CacheClearUiPolicyTest.kt'
MANIFEST_SHA256 = '09638dbfb67a56d854de5a84e5a855c37965d8beee11b9e60f360cf81be3d0e5'
PINS = {
    ANIMATION: ('2ea795802be10e92b290418f812c2b70e3f8944ae76dee6ad8542ac4e11aaa9c', 21954, 'eb92a28e668f7144dbeb5da381b6cfd54aab87c5'),
    POLICY: ('5a3659469307fd48462114c2c88b2873078ee0448e39f6960846cd23a7bb5d70', 8055, 'd54468300d948fb19d3712747dd3053559904b58'),
    TEST: ('1cb25076e470fe45ff23d0845484435f6819363c2fced30f29c9dbb7ca51f98a', 9355, 'ca5d1c993fac01bab0fb5dd6d601d302e45b554e'),
}

def wide(path):
    value=str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value) if os.name=='nt' else path

def digest(raw): return hashlib.sha256(raw).hexdigest()

def _unique(pairs):
    result={}
    for k,v in pairs:
        if k in result: raise ValueError('duplicate cache-clear manifest key')
        result[k]=v
    return result

def checked_sources():
    root=wide(ROOT)
    manifest_raw=(root/'manifest.json').read_bytes()
    if digest(manifest_raw)!=MANIFEST_SHA256: raise ValueError('cache-clear manifest pin mismatch')
    manifest=json.loads(manifest_raw, object_pairs_hook=_unique)
    if manifest['upstreamCommit']!=COMMIT: raise ValueError('cache-clear commit mismatch')
    actual={Path(here,f).relative_to(root).as_posix() for here,dirs,files in os.walk(root) for f in files}
    if actual!=set(PINS)|{'manifest.json'}: raise ValueError('cache-clear raw file set mismatch')
    rows={row['path']:row for row in manifest['files']}
    if set(rows)!=set(PINS) or len(rows)!=len(manifest['files']): raise ValueError('cache-clear manifest rows mismatch')
    source={}
    for path,(sha,length,blob) in PINS.items():
        file=root/path
        if file.is_symlink(): raise ValueError('cache-clear source link rejected')
        raw=file.read_bytes()
        row=rows[path]
        if row['upstreamCommit']!=COMMIT or row['gitBlob']!=blob or row['bytes']!=length or row['sha256Bytes']!=sha:
            raise ValueError('cache-clear source manifest mismatch')
        if len(raw)!=length or digest(raw)!=sha or hashlib.sha1(b'blob '+str(length).encode()+b'\0'+raw).hexdigest()!=blob:
            raise ValueError('cache-clear source pin mismatch: '+path)
        source[path]=raw.decode('utf-8')
    return manifest,source

def reverse(text,recipe):
    for row in reversed(recipe):
        at=row['index'];after=row['after']
        if text[at:at+len(after)]!=after: raise ValueError('cache-clear inverse offset mismatch')
        text=text[:at]+row['before']+text[at+len(after):]
    return text

def _adapt(original,pairs):
    text=original;recipe=[]
    for label,before,after in pairs:
        if text.count(before)!=1: raise ValueError('cache-clear counted mapping mismatch: '+label)
        at=text.index(before)
        text=text[:at]+after+text[at+len(before):]
        recipe.append(dict(label=label,before=before,after=after,index=at,count=1))
    if reverse(text,recipe)!=original: raise ValueError('cache-clear whole source inverse mismatch')
    return text,recipe

def animation_source():
    _,raw=checked_sources()
    pairs=[
        ('actual-renderer-capability-import','import android.os.Build\n','import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\n'),
        ('same-root-navigation-input','androidx.activity.compose.BackHandler(enabled = progress.isComplete)',
            'com.android.purebilibili.core.ui.LocalNavigationBackHandler(enabled = progress.isComplete)'),
        ('actual-renderer-capability','com.android.purebilibili.core.ui.blur.shouldAllowRuntimeShaderBackedHazeEffect(\n            Build.VERSION.SDK_INT\n        )',
            'desktopDetailRenderEffectsSupported()'),
        ('unsupportedAndroidWindowBlur',
            '        com.android.purebilibili.core.ui.ModalWindowBlurBehindEffect(enabled = true)\n',
            '        // Windows platform omission: Android FLAG_BLUR_BEHIND cannot sample across\n'
            '        // this Compose dialog. Preserve the original dialog scrim/AppPopupSurface fallback.\n'),
    ]
    body,recipe=_adapt(raw[ANIMATION],pairs)
    return body,_proof(ANIMATION,body,recipe)

def test_source():
    _,raw=checked_sources()
    before='''        val candidates = listOf(
            File("app/src/main/java/com/android/purebilibili/feature/settings/ui/CacheClearAnimation.kt"),
            File("src/main/java/com/android/purebilibili/feature/settings/ui/CacheClearAnimation.kt")
        )'''
    after='''        val candidates = listOf(
            File("upstream-slices/v029-brand-cache-clear/app/src/main/java/com/android/purebilibili/feature/settings/ui/CacheClearAnimation.kt"),
            File("desktop/upstream-slices/v029-brand-cache-clear/app/src/main/java/com/android/purebilibili/feature/settings/ui/CacheClearAnimation.kt")
        )'''
    # The pinned upstream test predates its same-commit UI change from Android
    # DialogProperties to a full-window overlay. Retain the test, assert the new
    # exact original scrim boundary instead of a removed phone system-bar option.
    # The first two tests target helpers in the Android SettingsScreen, which
    # Windows neither emits nor calls. Keep them in the pinned raw reference;
    # do not invent unused production helpers merely to compile those tests.
    screen_only_tests=raw[TEST].split('class CacheClearUiPolicyTest {\n\n',1)[1].split(
        '    @Test\n    fun defaultCacheClearTargets_focusOnPlaybackRecovery()',1)[0]
    if screen_only_tests.count('@Test')!=2:
        raise ValueError('cache-clear Android-only test selection changed')
    pairs=[
        ('unique-jvm-test-class','class CacheClearUiPolicyTest {','class DesktopV029CacheClearUiPolicyTest {'),
        ('android-settings-screen-only-tests-not-compiled',screen_only_tests,
            '    // Two Android SettingsScreen-only helper tests remain in the fixed raw reference.\n\n'),
        ('fixed-original-ui-source-locator',before,after),
        ('current-original-full-window-scrim','source.contains("decorFitsSystemWindows = false")',
            'source.substringAfter("fun CacheClearAnimationDialog(").contains("Box(modifier = Modifier.fillMaxSize())")'),
        ('desktop-scrim-assertion-message','Cache clear animation dialog should be edge-to-edge so the scrim covers status and navigation bars',
            'The complete original cache progress scrim must fill its bounded Windows content'),
    ]
    body,recipe=_adapt(raw[TEST],pairs)
    return body,_proof(TEST,body,recipe)

def _proof(path,body,recipe):
    return dict(path=path,upstreamCommit=COMMIT,originalSha256Bytes=PINS[path][0],
        generatedSha256LF=digest(body.encode()),countedAdaptations=recipe,exactFullSourceInverse=True)

def generated_sources(tests_output=None):
    body,proof=animation_source()
    result={'com/android/purebilibili/feature/settings/CacheClearAnimation.kt':body}
    proofs=[proof]
    if tests_output is not None:
        test,audit=test_source()
        path=wide(Path(tests_output)/'com/android/purebilibili/feature/settings/DesktopV029CacheClearUiPolicyTest.kt')
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(test,encoding='utf-8',newline='\n')
        proofs.append(audit)
    return result,proofs

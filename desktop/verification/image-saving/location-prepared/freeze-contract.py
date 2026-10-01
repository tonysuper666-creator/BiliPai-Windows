"""Freeze this prepared source/review lane; read immutable original/compile evidence only."""
from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())

def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)

def sha(p):
    return hashlib.sha256(safe(p).read_bytes()).hexdigest()

def write(p, text):
    safe(p.parent).mkdir(parents=True, exist_ok=True)
    safe(p).write_text(text, encoding='utf-8', newline='\n')

def save_json(name, obj):
    write(HERE / name, json.dumps(obj, ensure_ascii=False, indent=2) + '\n')

assert not safe(HERE / 'evidence-manifest.json').exists(), 'This lane is frozen already'
inventory = json.loads(safe(HERE / 'source-inventory.json').read_text(encoding='utf-8'))
assert inventory['originalCommit'] == 'fcf84853b287662e8a9129ea0d38576c36522a34'
for row in inventory['sources']:
    raw = subprocess.check_output(['git', 'show', inventory['originalTag'] + ':' + row['path']], cwd=ROOT)
    normalized = raw.decode('utf-8').replace('\r\n', '\n').encode('utf-8')
    assert hashlib.sha256(normalized).hexdigest() == row['sha256Lf'], row['path']
    assert safe(HERE / row['originalCopy']).read_bytes() == normalized, row['path']
    lines = normalized.decode('utf-8').splitlines()
    for fragment in row['fragments']:
        body = '\n'.join(lines[fragment['startLine'] - 1:fragment['endLine']]) + '\n'
        assert safe(HERE / fragment['path']).read_text(encoding='utf-8') == body
        assert hashlib.sha256(body.encode()).hexdigest() == fragment['sha256Lf']

policy = HERE / 'generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalImageSaveLocationPolicy.kt'
prefs = HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSaveLocationPreferences.kt'
assert sha(policy) == '4a7892023f8c89913f3fe11b5d4ba7ec2bd8283438d710474f64d5a9361a41e2'
assert sha(prefs) == '83df4f8424f22c8ee00d81f3de4a79dc658206c1bf5aa65b683f3ed9a372d370'
original_policy = safe(HERE / 'original/ImageSaveLocationPolicy.kt').read_text(encoding='utf-8').splitlines(keepends=True)
assert ''.join(original_policy[10:28]) in safe(policy).read_text(encoding='utf-8')
compile_evidence = json.loads(safe(HERE / 'compile-01/compile-evidence.json').read_text(encoding='utf-8'))
assert compile_evidence['passed'] and compile_evidence['runtimeCpCount'] == 89
for row in compile_evidence['sources']:
    assert sha(HERE / row['path']) == row['sha256Bytes']
assert safe(policy).read_bytes() == safe(HERE / 'compile-01/sources' / policy.name).read_bytes()
assert safe(prefs).read_bytes() == safe(HERE / 'compile-01/sources' / prefs.name).read_bytes()
assert sha(HERE / 'compile-01/candidate-preferences-policy.jar') == compile_evidence['candidateJarSha256Bytes']

sdk = Path('C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0/um')
sdk_sources = []
for filename, ranges in [('KnownFolders.h', [(146, 154)]), ('ShlObj_core.h', [(946, 949), (1017, 1023), (1043, 1049)])]:
    p = sdk / filename
    lines = safe(p).read_text(encoding='utf-8-sig').splitlines()
    fragments = []
    for start, end in ranges:
        fragment = f'sdk-fragments/{filename}-{start}-{end}.txt'
        write(HERE / fragment, '\n'.join(f'{n}: {lines[n-1]}' for n in range(start, end+1)) + '\n')
        fragments.append(dict(path=fragment, startLine=start, endLine=end, sha256Bytes=sha(HERE / fragment)))
    sdk_sources.append(dict(path=str(p).replace('\\', '/'), sha256Bytes=sha(p), fragments=fragments))
save_json('windows-sdk-source-evidence.json', dict(
    sources=sdk_sources,
    sourceOnly=True, nativeCallExecuted=False,
    contract='FOLDERID_Pictures with KF_FLAG_DEFAULT (0), current redirected path; free returned PWSTR with CoTaskMemFree. Do not use KF_FLAG_DEFAULT_PATH or guess HOME/Pictures.',
))

main_assets = ROOT / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
main_files = ROOT / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt'
approved_assets = ROOT / 'desktop/.local/dynamic-motion-photo-download-owner-parity/prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
assert sha(approved_assets) == '23ff374ea8a4fe8526d29773c8c8b262e83daa1b61d86169e8337fd55e9ef6bc'
current_assets = safe(main_assets).read_text(encoding='utf-8')
restored_assets = current_assets.replace('import java.awt.Component\n', '')
restored_assets = restored_assets.replace('= { name, mime -> selectDynamicSaveTarget(name, mime) },', '= ::selectDynamicSaveTarget,')
restored_assets = restored_assets.replace('= { selectDynamicSaveDirectory() },', '= ::selectDynamicSaveDirectory,')
restored_assets = restored_assets.replace('internal data class DesktopDynamicSaveTarget', '// This actual Main type is unchanged. Task-only compile omits only this line\n// so the real frozen Main class is used, without a SaveTarget override.\ninternal data class DesktopDynamicSaveTarget')
restored_assets = restored_assets.replace('name: String, mime: String, parent: Component? = null', 'name: String, mime: String')
restored_assets = restored_assets.replace('internal suspend fun selectDynamicSaveDirectory(parent: Component? = null)', 'private suspend fun selectDynamicSaveDirectory()')
restored_assets = restored_assets.replace('chooser.showSaveDialog(parent)', 'chooser.showSaveDialog(null)')
restored_assets = restored_assets.replace('JOptionPane.showConfirmDialog(parent,', 'JOptionPane.showConfirmDialog(null,')
assert restored_assets.encode('utf-8') == safe(approved_assets).read_bytes()
assert sha(main_files) == '702e37833b3ef68bb73c677e6f81cc77b77a98a2cdef3eca07f9d135c2b6738e'
save_json('source-contract-review.json', dict(
    verdict='Prepared preference/source contract is reviewable and independently compiled; static codec/default-directory consumer remains pending.',
    originalTag=inventory['originalTag'], originalCommit=inventory['originalCommit'], originalSourceCount=6,
    originalIdentitiesRecheckedFromGit=True, originalPurePolicyBodyByteEqual=True,
    preparedPolicySha256Bytes=sha(policy), preparedPreferencesSha256Bytes=sha(prefs),
    compileEvidencePath='compile-01/compile-evidence.json', compileEvidenceSha256Bytes=sha(HERE / 'compile-01/compile-evidence.json'),
    actualCompileBase='Main02 snapshot f164846bb1ebe5545c727824bfa6fe853a5fca92bfd1bcfddf59abb53c943d17; 89 ordered runtime binaries, pre/post verified by compile script.',
    currentMutableMainSourceReadbackOnly=[dict(path=str(p.relative_to(ROOT)).replace('\\', '/'), sha256Bytes=sha(p)) for p in [main_assets, main_files]],
    currentMutableMainAssetsFullBytesEqual114=False,
    currentMutableMainAssetsEquals114AfterDeclaredPickerOwnerAdapterReversal=True,
    currentMutableMainAssetsAdapter=['Component import', 'Parent-aware target/directory chooser and overwrite confirmation', 'Default function references -> lambdas matching parent-default signatures', 'Directory picker visibility private -> internal', 'Remove task-only SaveTarget comment'],
    currentMutableMainFilesEqualsApproved34Bytes=True,
    currentMainRuntimeOrMain03SnapshotVerified=False,
    compiledSourceCount=2, compiledClassCount=8, classFqnIntersection=[],
    purePackageMethodSignatureCounts=dict(candidate=3, actualMain02=167), methodSignatureIntersection=[],
    preferences=dict(backing='Caller existing DesktopPluginStore', namespace='settings', key='image_save_tree_uri', valueType='nullable String',
        absentDefault=None, sameFlowAndSyncBacking=True, setterUsesRequiredRootOwnerCommitGate=True,
        setterChecksCoroutineInsideGate=True, storeWritesFrozenAuthorityRetained=True,
        androidSyncMirror='Original Android image_save_prefs/tree_uri collapses into the same Windows primary-key backing; not a second namespace/store.'),
    actualExistingOwners=['DesktopDynamicImageAssets', 'DesktopDynamicCacheSessionGuard', 'DesktopDynamicCardOperations', 'Root picker/window', 'Root global DesktopPluginStore'],
    knownPending=['Windows directory picker + persistent selection UI', 'Canonical file URI serialization/validation and foreign content URI boundary',
        'Actual SHGetKnownFolderPath redirected Pictures default consumer', 'Static decoded PNG/JPEG95 transport codec',
        'Original all-images attempt-all aggregation and naming contract', 'Settings setter/restore runtime proof'],
    scope=dict(MainEdited=False, sharedGradle=False, registryEdited=False, oldFreezeEdited=False,
        HTTP=False, HWND=False, windowChooserExecuted=False, nativeKnownFolderCallExecuted=False,
        runtimePreferencesFixtureExecuted=False, codecFixtureExecuted=False),
))

write(HERE / 'static-save-location-contract.txt', '''Prepared source contract; no Main installation claim

Original identity: v0.2.3-alpha.9, commit fcf84853b287662e8a9129ea0d38576c36522a34.
The six full LF-normalized original files, exact line fragments and SHA-256 identities are in source-inventory.json. This freeze rechecks all of them against local git show. No source/content was retrieved from a network.

Exact original behaviour
1. ImagePreviewDialog.kt 2037-2043 tests URL substrings case-insensitively. GIF wins over WebP; both stream original bytes to preserve animation. Static WebP still takes that same raw stream branch. 2054-2058 stages with a 64 KiB buffer; 2067-2077 select extension/MIME and BiliPai_<milliseconds>.<extension>.
2. 2122-2139 uses Coil with the Bilibili Referer and requires SuccessResult + BitmapImage. 2142-2153 chooses PNG for a URL containing .png; all other remaining static formats become JPEG, quality 95. 2177 uses the same format and 95 for MediaStore fallback. This is decoded/re-encoded output, not a raw-byte save. PNG quality is passed as 95 by the original Android API; do not claim that number controls a Windows PNG codec in the same way.
3. Custom directory is attempted first (2079-2086, 2147-2159). Original SAF helpers return false if absent/invalid/not writable/creation or stream failure (ImageSaveLocationPolicy.kt 29-73); the caller then tries MediaStore. Streams can have been partially consumed, so retry opens a fresh staged input. Bitmap helper 75-93 checks compression success before saving bytes.
4. Original default is MediaStore.Images EXTERNAL_CONTENT_URI with RELATIVE_PATH Pictures/BiliPai, IS_PENDING 1 then 0 (2088-2115, 2161-2184). Android indexing/pending rows are part of that platform route.
5. Save-all UI 505-513 evaluates urls.map { saveImageToGallery(...) }.all { it }: each image is attempted even if an earlier ordinary save returns false, then one all-success result is reported. Existing Assets114 instead stops when a write throws; extending its scope needs a declared aggregator preserving cancellation propagation.
6. DynamicSaveImage.kt 149-184 and ReplyCommentImageSaver.kt 183-225 are separate generated bitmap outputs: PNG quality 100, custom directory first, then the same MediaStore default. Reuse their already installed renderer/spec/writer; this task does not produce another one.

Exact original preference/UI contract
SettingsManager.kt 6683 defines string key image_save_tree_uri. 6737-6749 reads it as nullable string from settingsDataStore and writes/removes it, also updating the Android image_save_prefs/tree_uri sync mirror. 6752-6755 is synchronous mirror read. Absent/null means default; empty/malformed strings are not invented into a directory preference.
SettingsScreen.kt 292-306 uses OpenDocumentTree. Cancel/null leaves the old preference unchanged; selected URI is stored after READ/WRITE persistable grant is attempted. 582-624 owns the image-location dialog, choose action and reset-to-null action. Labels: 图片保存位置 / 选择图片目录 / 恢复默认; feedback 已设置图片保存目录 / 已恢复默认图片保存位置. Its explanatory scope is dynamic images, avatars and comment images, not only one gallery card.

Windows mapping, smallest complete next slice
Keep one caller-owned DesktopPluginStore.settings backing and original primary key image_save_tree_uri. The prepared DesktopImageSaveLocationPreferences facade reads a Flow and synchronous value from that same backing; setters require Root's actual settings/window commit gate. They check cancellation inside that final gate and retain the Store's writesFrozen restore rejection. Android's separate sync mirror is an explicitly declared platform collapse, not another store/cache or HomeSettings replacement. This preference is global; do not scope it to a card MID/account epoch.

Persist the user-selected local directory as a canonical file URI string using Path.toUri(), preserving the original string/null schema. The original selected pure policy still only recognizes content:// as Android SAF. A Windows consumer must explicitly support file URI conversion and validation; it must not change that original helper or present a content URI as a usable Windows path. A restored Android content URI is retained as an unsupported platform value until selection/reset; an attempted custom write can fail to the Windows default route, consistent with the original custom-failure fallback. Never fabricate a SAF token/permission grant. Picker cancellation is not custom-write failure and must not trigger an unsolicited default save.

Use Root's existing directory picker/window ownership for the settings choose action. A configured valid directory is reused across saves/restart; it does not reopen a chooser for each image. Reset removes the primary key. Existing per-image Save As and save-all directory selection may remain explicit user actions, but they are not persistent preference parity. No new items list, image owner, HTTP client, store or cache is needed.

Default Windows destination must resolve the actual OS FOLDERID_Pictures with SHGetKnownFolderPath, flags KF_FLAG_DEFAULT=0 and current user, then append BiliPai. Use the current redirected path (including OneDrive), not KF_FLAG_DEFAULT_PATH, USERPROFILE/Pictures, an English folder-name guess or PicturesLibrary. Release the returned allocation with CoTaskMemFree. Exact installed SDK definitions/signature are recorded in windows-sdk-source-evidence.json. No actual native call was made. If this resolver fails, return failure or expose the real folder-selection action; do not invent a path.

Reuse DesktopDynamicImageAssets' current authenticated-owner admission, same session/cache guard, download staging and cancellation drain, with Store -> Assets -> Files lock order. Add a static decode/encode step between download and the existing owned final move. GIF/WebP retain exact bytes. PNG/JPEG reuse a real Windows decoder/encoder and original format/quality selection; dimension/resource validation stays within existing 32 MiB image and 200 MiB video transport limits, with explicit pixel-allocation bounds if decoding expands the image. Avoid a full-heap encoded copy if staged streaming can be used. The existing motion-photo JPEG helper is not by itself proof of Coil orientation/ICC/alpha behaviour for ordinary static images.

Keep same-parent scratch output, real cancellation check inside final ownership gate, target validation/collision decisions and atomic replacement. Custom/default attempts reopen staged input when necessary. Windows has no MediaStore index row or SAF grant; file commit and filesystem permissions are the thin platform boundary. Report actual directory/file saving, not Windows Photos indexing or receiver recognition.

Current installed scope and evidence
The current mutable Main Assets source has Root's existing parent-aware chooser adapter: Component import, default function references -> lambdas, parent-default signatures and actual parent in save/overwrite dialogs, internal directory-picker entry, and removal of a task-only SaveTarget comment. Reversing only those declared substitutions is exactly byte-equal to approved Assets114 (SHA 23ff374ea8a4fe8526d29773c8c8b262e83daa1b61d86169e8337fd55e9ef6bc); the full current source SHA is recorded separately in source-contract-review.json. The Assets class transport/commit body retains ordinary bytes unchanged at 56-75/107-123. This explicitly does not close the original PNG/JPEG encoding, persistent directory, exact filename or save-all failure contracts. Current Main MotionPhotoFiles matches the separately approved two-line final-gate cancellation delta SHA 702e37833b3ef68bb73c677e6f81cc77b77a98a2cdef3eca07f9d135c2b6738e. This source readback is not a claim of Main03 compiled/runtime identity.

The two prepared sources independently compiled against immutable actual Main02 and 89 pinned runtime binaries: compile-01/compile-evidence.json PASS, eight classes with no class FQN overlap, three original pure top-level methods against 167 actual same-package methods with no JVM-name/parameter-descriptor overlap. Prepared bytes equal their retained compile inputs. No settings setter, chooser, static codec or native KnownFolder runtime was executed. No shared Gradle/Main/source registry/window/network was operated. Existing 233/114/34 matrices were not re-run.

Focused acceptance needed for the next slice
Check raw GIF/WebP equality; decoded PNG/JPEG format and quality with EXIF orientation/alpha/ICC fixtures; original names and MIME; configured-directory reuse/restart, picker cancel, reset and restored unsupported content URI; redirected Pictures resolution; custom-write failure -> default with fresh input; per-file ordinary failure while save-all still attempts later files; cancellation/retirement preserves target and removes scratch; settings restore freezes old facade. Extend only these new contracts rather than rerunning unrelated frozen matrices.
''')

write(HERE / 'root-installation-recipe.txt', '''Prepared payload only; keep current Main installation untouched.

1. Retain evidence-manifest.json, source-inventory.json and compile-01 inputs together. The compile base is immutable actual Main02, not the still-changing Main03 tree. Runtime acceptance remains pending for this preference bridge.
2. For the next selected source slice, copy prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSaveLocationPreferences.kt once. Construct it with Root's existing global DesktopPluginStore and actual settings/window commit gate. Never construct another HomeSettings/SettingsManager or preference Store.
3. If the original pure location declarations are needed by source consumers, produce generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalImageSaveLocationPolicy.kt once and register only its original app/.../ImageSaveLocationPolicy.kt identity. The three original function bodies and enum are byte-preserved. Desktop file URI resolution must be a declared platform seam and must not call the content-only SAF predicate as if it accepted file URI.
4. Merge registry per identity/path with the current Root registry; do not copy any historical entire registry. Check new package/JVM parameter signatures and class FQNs on the actual installation compile input. This lane does not modify or provide a replacement registry or Gradle file.
5. Root owns settings choose/reset UI, real window directory chooser binding, canonical selected URI serialization, native redirected Pictures resolution and using that preference in existing gallery/PNG consumers. Install those as the next slice, together with real PNG/JPEG95 encoding and same Assets authority. Do not claim the present one-key facade changes save routing.
6. Preserve installed Gallery233 valid-XMP, Assets114, Files34 cancellation correction and QR/receipt additions. Neither this source contract nor the prepared bridge modifies those frozen payloads or their claims.
''')

artifacts = []
for p in sorted(safe(HERE).rglob('*'), key=lambda x: str(x)):
    if p.is_file() and p.name != 'evidence-manifest.json':
        relative = str(p)[len(str(safe(HERE))) + 1:].replace('\\', '/')
        artifacts.append(dict(path=relative, sizeBytes=p.stat().st_size, sha256Bytes=sha(p)))
save_json('evidence-manifest.json', dict(
    schema='task-only-source-review-freeze-v1', frozen=True, artifactCount=len(artifacts), artifacts=artifacts,
    scope='Original static-image save / persistent directory source contract plus independently compiled same-Store one-key facade. No codec/picker/KnownFolder integration.',
    compileEvidence='compile-01/compile-evidence.json', sourceContractReview='source-contract-review.json',
    originalTag=inventory['originalTag'], originalCommit=inventory['originalCommit'],
    MainChanged=False, oldFreezeChanged=False, sharedGradle=False, sourceRegistryChanged=False,
    HTTP=False, HWND=False,
))
print(json.dumps(dict(manifest=str(HERE / 'evidence-manifest.json'), sha256Bytes=sha(HERE / 'evidence-manifest.json'), artifacts=len(artifacts))))

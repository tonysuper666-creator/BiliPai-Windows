from pathlib import Path
import hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p).write_text(v,encoding='utf-8',newline='\n')
VERIFY='''from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\\\\\?\\\\') else '\\\\\\\\?\\\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
manifest=json.loads((HERE/'verified-artifacts.json').read_text(encoding='utf-8'))
for row in manifest['files']:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
for row in json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8')):assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
assert sha(Path(manifest['productManifestPath']))==manifest['productManifestSha256Bytes']
old=HERE.parent/'discovery-storage-product-ui-proof'
assert sha(old/'verified-artifacts.json')==manifest['original43ManifestSha256Bytes']
for row in json.loads((old/'verified-artifacts.json').read_text(encoding='utf-8'))['files']:assert sha(old/row['path'])==row['sha256Bytes'],row['path']
print('VERIFIED',len(manifest['files']),234,'original43 unchanged',sha(HERE/'verified-artifacts.json'))
'''
stages=[(HERE.parent/'discovery-storage-product-alpha-review','blocked-up-main-product-snapshot',
'''# Historical product capture diagnosis — additive frozen evidence

The original 43-artifact proof stays byte-for-byte unchanged. Its `material3-stopped-restart.png` was captured after the real Press/Release handler had incremented the supplied restart callback to 1, after eight settled frames, and after `guard.load()` returned false. That proof established the UI semantics, button handler and retired guard behavior. It did not assert pixel contrast or a visible native window.

The original PNG is not an empty render: 455,227 pixels are fully transparent, 5,573 have nonzero alpha, and its alpha bounding box is (20,26)–(297,97). Every RGB value is black. A viewer compositing transparency on black hides the rendered black text. The original startup-error/ready Material3 PNGs have the same transparent black-text limitation. Miuix rendered white text; management pages already used an opaque original surface.

This new proof compiled only `StoppedCaptureFixture.kt`. It loaded the original immutable product jars pinned by manifest 10e08fbc781a62ceff68c06c768677922925679b6121f59f7eb43ab56845cd7b, plus the unchanged old pure fixture classes. No production class is overridden or modified. Four scenes compare the actual product Boundary with the current bare theme to an explicitly test-owned original `AppSurface` host. Each scene converged to three identical PNG hashes before the pointer; actual restart Press/Release called the supplied callback exactly once.

Material3 bare theme: 458,410 transparent pixels; zero message/action contrasted ink. Original surface control: all 460,800 pixels opaque; message/action ink 1,733/657. Miuix bare theme: 458,166 transparent pixels; surface control makes all pixels opaque. This rules out a missing-frame explanation in the narrow offscreen seam and identifies absent background/content-color hosting. The control is not a claim that the old product already contained the fix.

No HWND, Main/Runtime/Mpv construction, real process restart, Backup restore, HTTP or human-account data was used. The constructor is deliberately closed before composition for this stopped-only diagnosis. Current product repair is independently proven in the separate final-product cohort; these old bytes remain historical.
'''),(HERE,'blocked-up-final-product-snapshot',
'''# Current product error-surface verification

Actual product snapshot manifest: `38efc073ee76d2148e700582e0be468d4c0eea24cea1b5111943ac865402a919`. Kotlin jar: `a6148f5486585361c45b1114f3fe10e76f3de52e90f237602656f10d01b56fb1`; Java: `8b713fc0b45c8d7dac2ac5446e09e6b38074fe0f0b2aa732ad3cfb7ca3522425`; resources: `c62e0fc0362b5c9ea8cee7adf5f266a3d9d252d593dd06ebde2a5a547cb46735`. All 234 ordered runtime jars were verified before and after execution. Nine production class codeSources resolve only to this Kotlin jar. Fixture output shadows zero product classes.

The old 43 proof's pure startup/stopped fixture was retained with its exact bare `DesktopAppearanceTheme(settings){body()}` errorTheme invocation. The only derivation omits the already proven management flow and adds strict actual PNG assertions. No fixture Surface is supplied. The original product Boundary now supplies its own AppSurface.

Both Material3 and Miuix passed the real temporary-disk corrupt guest JSON failure, safe error labels, actual repair/retry Press/Release, same active guard/actual repository/global BlockedStore, untouched unrelated namespace, guard close disposing the finite ready consumer, actual stopped restart Press/Release callback and rejection of reopening the retired store. There are 36 concrete assertions across two styles, not a replacement for the earlier management cases.

All four error/stopped renders converge to three identical consecutive PNG hashes after settling. Every image is 720×640, all 460,800 pixels opaque, with zero transparent pixels. Actual semantic rectangles contain contrasted rendered ink (RGB distance >180, alpha >150):

| Product style / state | Message ink | Action ink |
| --- | ---: | ---: |
| Material3 error | 840 | 463 |
| Material3 stopped | 1,733 | 657 |
| Miuix error | 931 | 518 |
| Miuix stopped | 1,906 | 728 |

The four final screenshots were inspected and are readable. The stopped screenshot is captured after the actual restart callback, which intentionally records rather than terminates the test JVM. Readiness uses a finite test AppText/LaunchedEffect consumer, not DesktopReadyApp. This proves the always-mounted Boundary seam and current actual product surface; it does not prove the full Shell, HWND, Runtime, Mpv, Backup restoration or real process exit/restart. The snapshot contains Root's Backup fix, but this fixture never executes it.

There is no main source edit, shared Gradle execution, native window, HTTP or user-account access. The original 43 artifacts, its historical PNGs and old snapshot were verified unchanged; the alpha diagnosis remains a separate frozen cohort. Management proof stays in the old 43 and is not rerun here. `verify-frozen.py` reads all payload bytes, product manifest, 234 dependencies and original 43 artifacts without recompiling.
''')]
for root,snapshotName,doc in stages:
 assert not safe(root/'verified-artifacts.json').exists(),'Already frozen; do not overwrite evidence'
 write(root/'ROOT-RESULT.md',doc)
 write(root/'verify-frozen.py',VERIFY)
 rows=[]
 for p in sorted(safe(root).rglob('*')):
  if p.is_file():rows.append(dict(path=str(p.relative_to(safe(root))).replace('\\','/'),sha256Bytes=sha(p),bytes=p.stat().st_size))
 snapshot=root.parent/snapshotName/'manifest.json'
 result=dict(frozen=True,files=rows,productManifestPath=str(snapshot),productManifestSha256Bytes=sha(snapshot),
  original43ManifestSha256Bytes=sha(root.parent/'discovery-storage-product-ui-proof/verified-artifacts.json'),
  productionOverrides=0,sharedGradle=False,HWND=False,HTTP=False,fullShell=False,actualProcessRestart=False)
 write(root/'verified-artifacts.json',json.dumps(result,indent=2)+'\n')
 r=subprocess.run([sys.executable,str(root/'verify-frozen.py')],capture_output=True,text=True,encoding='utf-8',errors='replace')
 print(r.stdout+r.stderr);r.check_returncode()

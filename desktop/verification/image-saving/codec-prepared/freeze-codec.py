"""Freeze only this prepared stateless codec lane; do not run or mutate Main."""
from pathlib import Path
import hashlib, importlib.util, json, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'.git').exists())
def safe(p):
    s=str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,text):
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(text,encoding='utf-8',newline='\n')
def save(name,value):write(HERE/name,json.dumps(value,ensure_ascii=False,indent=2)+'\n')
assert not safe(HERE/'evidence-manifest.json').exists(),'already frozen'
accepted=HERE/'proof-02/accepted-evidence.json';assert sha(accepted)=='15565d8085e6fc0eb2dcfdc5acac01acfe07ffed7a706c9d4f7e0ea32dbaa29e'
record=json.loads(safe(accepted).read_text(encoding='utf-8'));assert record['passed'] and record['runtimeCpCount']==92
pure=HERE/'generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalStaticGalleryFormat.kt'
platform=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicStaticImageCodec.kt'
for path in [pure,platform,HERE/'StaticGalleryCodecFixture.kt']:
    assert safe(path).read_bytes()==safe(HERE/'proof-02/sources'/path.name).read_bytes()
    assert safe(HERE/'proof-01/sources'/path.name).read_bytes()==safe(HERE/'proof-02/sources'/path.name).read_bytes()
spec=importlib.util.spec_from_file_location('codec_source_only_producer',HERE/'prepare-codec.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
producer=HERE/'prepared/desktop/tools/extract-upstream-dynamic-static-image-codec.py'
write(producer,safe(HERE/'prepare-codec.py').read_text(encoding='utf-8'))
generated=HERE/'producer-production-proof';p.generate(ROOT,generated,standalone=False)
production_pure=generated/'com/android/purebilibili/feature/dynamic/components/DesktopOriginalStaticGalleryFormat.kt'
assert safe(pure).read_bytes()==safe(production_pure).read_bytes()
assert [x.name for x in safe(generated).rglob('*') if x.is_file()]==['DesktopOriginalStaticGalleryFormat.kt']
runtime=ROOT/'desktop/.local/dynamic-media-main-integration/main-product-snapshot-03/ordered-runtime-cp.json'
cp=json.loads(safe(runtime).read_text(encoding='utf-8'));assert len(cp)==92
native_jar=next(row for row in cp if 'skiko-awt-runtime-windows-x64' in row['path'])
native=[]
with zipfile.ZipFile(safe(native_jar['path'])) as z:
    for path in safe(HERE/'proof-02/private-home/.skiko').rglob('*'):
        if path.is_file() and path.name in ['skiko-windows-x64.dll','icudtl.dat']:
            match=[n for n in z.namelist() if n.rsplit('/',1)[-1]==path.name];assert len(match)==1
            assert hashlib.sha256(z.read(match[0])).hexdigest()==sha(path)
            native.append(dict(path=str(path)[len(str(safe(HERE)))+1:].replace('\\','/'),sha256Bytes=sha(path),sourceJarPath=native_jar['path'],sourceJarSha256Bytes=native_jar['sha256Bytes'],originalJarEntry=match[0],exactEntryBytes=True))
assert len(native)==2
save('source-install-audit.json',dict(passed=True,productionProducer= str(producer.relative_to(HERE)).replace('\\','/'),productionProducerSha256Bytes=sha(producer),
    productionEmittedSelectedFiles=1,productionByteEqualToCompiledPure=True,platformByteEqualToCompiledSource=True,
    originalTag='v0.2.3-alpha.9',originalCommit='fcf84853b287662e8a9129ea0d38576c36522a34',
    originalSourcePath=p.ORIGINAL,originalSourceSha256Lf=p.ORIGINAL_SHA,selectedTokenFragmentAudit='source-extraction-audit.json',
    productionPurePath=str(pure.relative_to(HERE)).replace('\\','/'),productionPureSha256Bytes=sha(pure),platformSourceSha256Bytes=sha(platform),
    priorProof01ProductionAndFixtureBytesUnchanged=True,proof01HiddenRgbAssertionFailureRetained=True,
    declaredAdapters=['Selected predicates/local format/naming assignments -> unique internal function seams and return statements','Android Coil Bitmap decode/compress -> explicit Windows Skia stage encoder','Required caller source/decode budgets + checkpoint, not another owner or store'],
    actualRuntimeNativeLibraryPins=native,classFqnIntersection=[],packageMethodAudits=record['packageMethodAudits'],
    noMainOverrides=True,noAddedDependencies=True,MainChanged=False,sourceRegistryChanged=False,sharedGradle=False,HTTP=False,HWND=False))
write(HERE/'codec-contract-and-installation.txt','''Prepared static codec source slice. No Main installation / complete static-save feature claim.

Original: v0.2.3-alpha.9 commit fcf84853b287662e8a9129ea0d38576c36522a34; ImagePreviewDialog.kt full LF SHA 8ab6d642e5085483ffa5fbe684cb962468c6b93daec46c1eb768e98f8b3fe0b0. Original saveImageToGallery2034-2194 reference is retained. Exact selected classifiers/extension/MIME/filename assignments are verified by source-extraction-audit.json; function boundaries/returns/indentation are explicit extraction adaptations.

Original contract: case-insensitive URL substring .gif/.webp preserves all bytes (GIF priority even mixed suffix). Remaining URL .png uses decoded PNG; all remaining static input uses decoded JPEG95. No magic-sniffing decision replaces that algorithm. PNG quality95 is passed by the original Android API; native PNG compression level is not thereby a quality95 equivalent. Original filename is BiliPai_<System.currentTimeMillis()>.<extension>. The four selected stateless helpers retain those choices without schema/store/cache.

Windows adapter: encodeDesktopStaticGalleryImageToStage(source:Path,staged:Path,imageUrl:String,maxEncodedBytes:Long,maxDecodedBytes:Long,checkpoint:suspend()->Unit). It creates neither final target nor owner, store, client, cache or list. The existing Assets caller must own/validate source and stage paths and parent ancestry, supply its actual checkpoint, source limit and decoded resource budget, and retain cleanup/final commit authority. Both paths must already be private regular scratch files. Never pass a selected final destination as staged.

Raw GIF/WebP stays 64 KiB streamed bytes. Static input is capped before parsing; Data/Codec metadata preflight checks dimensions and an overflow-safe raster-byte budget before Image raster/encode. That estimate is a minimum raster budget, not total native peak memory. Preflight native objects close before decode/encode. Native output size is checked; output is transferred in 64 KiB chunks via Data.getBytes(offset,count), without another full Java encoded clone. Native decode/encode itself is synchronous and cannot be interrupted by coroutine checkpoint; ownership/cancellation is checked before/after and during writes. Root must retain existing final-gate ensureActive and all scratch cleanup.

Evidence: actual immutable Main03 manifest f9d91763db17acaa59753f5dccad4feb88512c8ad2984062520ed867943df6a6, ordered CP SHA3abb7e2fecccb5bd0693e6b0868d002b576fc8f689d734d86bd5737fe1c39cac, 92 runtime entries. Three sources independently compile; prepared production sources equal retained compile inputs. Fresh headless JVM executes native Skia, no product class override, 40 Kotlin assertions /15 cases; independent Pillow verifies 24 checks. DLL/ICU extracted bytes exactly match entries in the pinned actual runtime jar. No extra dependency is introduced.

Actual observations: GIF/WebP byte equality; original format/MIME/naming; PNG alpha channel and opaque pixels exact, visible premultiplied colour unchanged for fixture; JPEG quantization matches independent Pillow quality95; EXIF origins1-8 automatically applied by Image.makeFromEncoded (dimensions/pixels match Pillow exif_transpose). No extra rotation algorithm is added. Decoded budget is rejected before opening stage; real codec Job cancellation before output preserves the scratch sentinel while caller scope remains active.

Proof01 is retained rejected history: its independent full-RGBA-byte assertion was too strong for native premultiplied re-encoding. 4562 hidden RGB pixels differed, alpha/opaque pixels and visible black-composite colours were correct. Proof02 changes only Python independent acceptance assertions; compiled production and Kotlin fixture bytes are identical. Do not claim raw PNG byte identity, invisible RGB preservation or Android Bitmap pixel/byte equivalence.

Still pending: original Android N32 raster equivalence, ICC/colour precision/metadata semantics across Android and Windows, actual integrated Assets scratch/owner/final-target pipeline, persistent preference and chooser consumer, redirected default-folder consumer, original custom-failure/default fallback and combined save-all codec semantics. Windows JPEG alpha matte behaviour is not declared black/white or Android-equivalent. No Photos indexing/receiver acceptance claim.

Source-only installation recipe for a later combined slice:
1. Copy prepared/desktop/tools/extract-upstream-dynamic-static-image-codec.py once, import generate(repo,output,standalone=False). It emits only the selected pure file into output/com/android/.../components; production output is byte-equal to the compiled pure source. Copy DesktopDynamicStaticImageCodec.kt once. No prepared JAR installation and no entire Ops/Assets replacement.
2. Merge original ImagePreviewDialog source identity with the current registry entry by declaration/output ownership; that original file already belongs to Gallery233. Do not add a duplicate source identity or overwrite the registry. Confirm five new JVM method signatures/class FQNs against the actual install input. Main03 audit is zero intersections (UI1 vs166; components4 vs168).
3. In the existing Assets save flow, download to its existing owned scratch, then encode into another caller-owned private scratch. Existing Assets finally deletes both scratch files; existing Store->Assets->Files commit path and final cancellation admission move only the completed output. Reuse actual picker/window and global preferences rather than creating another owner/client/store. Preserve Root's parent-aware picker adapters and previously accepted batch delta when combining.
4. Use required explicit decoded budget; do not create another persisted setting or silently claim a numeric Windows resource cap was original Android policy. Current image/video transport limits remain32MiB/200MiB. Reuse native KnownFolder and sameStore bridge from their separate frozen lanes for destination selection.
5. The combined actual-product slice needs focused static codec/owner scratch/fallback integration proof; these standalone codec results are not a substitute. Do not rerun unrelated old233/114/34/135 matrices solely because this prepared source exists.
''')
artifacts=[]
for path in sorted(safe(HERE).rglob('*'),key=str):
    if path.is_file() and path.name!='evidence-manifest.json':
        relative=str(path)[len(str(safe(HERE)))+1:].replace('\\','/')
        artifacts.append(dict(path=relative,sizeBytes=path.stat().st_size,sha256Bytes=sha(path)))
save('evidence-manifest.json',dict(schema='task-only-prepared-codec-freeze-v1',frozen=True,artifactCount=len(artifacts),artifacts=artifacts,
    acceptedEvidence='proof-02/accepted-evidence.json',acceptedEvidenceSha256Bytes=sha(accepted),
    sourceInstallAudit='source-install-audit.json',sourceInstallAuditSha256Bytes=sha(HERE/'source-install-audit.json'),
    kotlinAssertions=40,independentAssertions=24,caseCount=15,MainChanged=False,sourceRegistryChanged=False,sharedGradle=False,HTTP=False,HWND=False,
    scope='Stateless prepared stage codec plus original format fragments; destination preference/Assets final commit/Android bitmap parity not integrated.'))
print(json.dumps(dict(manifest=str(HERE/'evidence-manifest.json'),sha256Bytes=sha(HERE/'evidence-manifest.json'),artifacts=len(artifacts))))

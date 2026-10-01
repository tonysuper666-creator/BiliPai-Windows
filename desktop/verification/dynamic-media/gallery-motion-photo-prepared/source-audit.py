"""Independent source-byte and minimal producer adaptation receipt, lane-only writes."""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def lf_sha(path): return hashlib.sha256(path.read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()
def save(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
out = HERE/'audit03'; assert not out.exists(); out.mkdir()
base = 'app/src/main/java/com/android/purebilibili/'
original_paths = [base+'feature/dynamic/components/'+name+'.kt' for name in [
    'ImagePreviewDialog','LivePhotoPlayback','ImageSaveLocationPolicy','ImagePreviewDecodePolicy',
    'ImagePreviewFeedbackPolicy','ImagePreviewTransitionPolicy','ImagePreviewSourceAnchor',
    'ZoomableImageGesturePolicy','ZoomableImageScalePolicy']] + [base+'core/util/GalleryVisualMediaContracts.kt']
originals = []
for path in original_paths:
    actual = (REPO/path).read_text(encoding='utf-8').replace('\r\n','\n')
    tagged = subprocess.check_output(['git','show','v0.2.3-alpha.9:'+path],cwd=REPO).decode().replace('\r\n','\n')
    assert actual == tagged, path
    originals.append({'path':path,'sha256Lf':hashlib.sha256(actual.encode()).hexdigest(),
                      'gitBlob':subprocess.check_output(['git','rev-parse','v0.2.3-alpha.9:'+path],cwd=REPO,text=True).strip(),
                      'pinnedTagLfBytesEqual':True})
producer = HERE/'prepared/desktop/tools/extract-upstream-dynamic-gallery-motion-photo-valid-xmp.py'
spec = importlib.util.spec_from_file_location('gallery_byte_reproducer',producer)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
reproduced = out/'reproduced'; module.generate(REPO,reproduced)
generated = []
for p in sorted((HERE/'generated-acceptance-final').rglob('*')):
    if p.is_file():
        relative = p.relative_to(HERE/'generated-acceptance-final')
        other = reproduced/relative
        assert p.read_bytes() == other.read_bytes(), relative
        generated.append({'path':str(p.relative_to(HERE)),'sha256Bytes':sha(p),'bytes':p.stat().st_size,'freshProducerBytesEqual':True})
before = (reproduced/'original-packing-before-xmp-fix.txt').read_text(encoding='utf-8')
after = (reproduced/'packing-after-xmp-fix.txt').read_text(encoding='utf-8')
deleted = ['        GCamera:MotionPhoto="1"','        GCamera:MotionPhotoVersion="1"','        GCamera:MotionPhotoPresentationTimestampUs="0"']
expected = before
for line in deleted:
    assert before.splitlines().count(line) == 1
    expected = expected.replace(line+'\n','')
assert after == expected
receipt = json.loads((reproduced/'producer-receipt.json').read_text(encoding='utf-8'))
assert receipt['selectedPacking']['deletedExactLines'] == deleted
assert receipt['selectedPacking']['unchangedBodyExceptThreeXmpAttributes']
preview = (REPO/original_paths[0]).read_text(encoding='utf-8')
exif_start = preview.index('                exif.setAttribute(android.media.ExifInterface.TAG_MAKE')
exif_stop = preview.index('                exif.saveAttributes()',exif_start)+len('                exif.saveAttributes()')
exif_body = preview[exif_start:exif_stop]
(out/'original-four-exif-tag-statements.txt').write_text(exif_body+'\n',encoding='utf-8',newline='\n')
assert exif_body.count('exif.setAttribute(') == 4
assert 'yyyy:MM:dd HH:mm:ss' in exif_body and 'Locale.US' in exif_body
current_paths = [
    'desktop/tools/extract-upstream-dynamic-card.py',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDynamicCardHost.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicLivePhoto.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicEditorSelectedImages.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicEditorWindowsPickers.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicEditorPlatform.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicCardSession.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicCardStateRegistry.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/player/MpvNative.kt',
]
current = [{'path':p,'sha256Lf':lf_sha(REPO/p),'scope':'read-only current working source; not frozen Main source proof'} for p in current_paths]
save(out/'source-audit.json', {'passed':True,'originalTag':'v0.2.3-alpha.9',
    'originalCommit':subprocess.check_output(['git','rev-parse','v0.2.3-alpha.9^{commit}'],cwd=REPO,text=True).strip(),
    'originals':originals,'generatedFreshByteEqual':generated,'producerSha256Bytes':sha(producer),
    'onlyMotionPackingBodyAdaptation':{'deletedExactThreeLines':deleted,'beforeSha256Bytes':sha(reproduced/'original-packing-before-xmp-fix.txt'),
                                     'afterSha256Bytes':sha(reproduced/'packing-after-xmp-fix.txt'),'allOtherSelectedPackingBytesEqual':True},
    'originalExifTagBodySha256Bytes':sha(out/'original-four-exif-tag-statements.txt'),
    'currentReadOnlySourcePins':current,'androidSourcesModified':False,'MainOrGradleModified':False,
    'newImageImportExtractionAlgorithm':False,'rawFixtureTailExtractionIsVerificationOnly':True})
print('PASS 10 pinned original source identities, fresh producer byte equality, exact three-line XMP whitelist and original four EXIF tag mapping')

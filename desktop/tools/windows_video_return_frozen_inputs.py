"""Finite reversible Root bindings on the complete existing original return renderer.

Existing callers keep their original live snapshot path. A Root survivor can pass
its frozen session instead, without rereading a later click's GraphicsLayer/bitmap
or attempting to resolve the retired detail Entry's unused Holder Local.
"""
from pathlib import Path
import hashlib
import json
import os

OUTPUT = 'com/android/purebilibili/feature/video/screen/VideoDetailReturnSourceCardChrome.kt'
BASE_SHA256_LF = '957afa39b1013acb005f4f7b0eecc6511f405381f009a5613018fd24ba950e5a'
PARAMETER = '    frozenSnapshot: com.bilipai.desktop.ui.DesktopWindowsVideoCardReturnSnapshot? = null,\n'


def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def restore(text, edits):
    for row in reversed(edits):
        at, after = row['offset'], row['after']
        if type(at) is not int or at < 0 or at + len(after) > len(text):
            raise ValueError('Invalid frozen-renderer inverse offset')
        if text[at:at + len(after)] != after:
            raise ValueError('Frozen-renderer inverse anchor differs: ' + row['label'])
        text = text[:at] + row['before'] + text[at + len(after):]
    if sha(text) != BASE_SHA256_LF:
        raise ValueError('Frozen-renderer inverse changed remaining original bytes')
    return text


def apply(path, text):
    if path != OUTPUT:
        return text, None
    if sha(text) != BASE_SHA256_LF:
        raise ValueError('Complete original Windows return renderer identity changed')
    original = text
    edits = []

    def exact(before, after, label, count=1):
        nonlocal text
        if text.count(before) != count:
            raise ValueError('Frozen-renderer binding anchor changed: ' + label)
        # Each edit is recorded in its actual sequential coordinate system.
        for _ in range(count):
            at = text.index(before)
            edits.append(dict(offset=at, before=before, after=after, label=label))
            text = text[:at] + after + text[at + len(before):]

    if text.count('holderPlatform') != 9:
        raise ValueError('Unused Holder locals gained a consumer; re-review their lifetime')
    exact('    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current\n',
          '', 'Remove unused retired Entry Local lookups', count=9)
    exact('    followProgressEnabledProvider: () -> Boolean = { true },\n) {',
          '    followProgressEnabledProvider: () -> Boolean = { true },\n' + PARAMETER + ') {',
          'Optional frozen info renderer input')
    exact('    inverseScaleYProvider: () -> Float = inverseScaleXProvider,\n) {',
          '    inverseScaleYProvider: () -> Float = inverseScaleXProvider,\n' + PARAMETER + ') {',
          'Optional frozen cover renderer input')
    exact('if (isNowPlayingBar && CardPositionManager.lastClickedNativeCardBitmap != null) return',
          'if (frozenSnapshot == null && isNowPlayingBar && CardPositionManager.lastClickedNativeCardBitmap != null) return',
          'Legacy whole-bar bitmap is never read by a frozen survivor')
    exact('val nativeCardLayer = CardPositionManager.lastClickedNativeCardLayer',
          'val nativeCardLayer = if (frozenSnapshot == null) CardPositionManager.lastClickedNativeCardLayer else null',
          'Do not borrow a disposed source card layer')
    exact('''        VideoCardSourceLayout.SIDE_BY_SIDE -> {
            val isCapsuleDock = (CardPositionManager.lastClickedVideoSourceCornerDp ?: 0) >= 24 ||
                (layout.cardHeightPx <= with(density) { 72.dp.toPx() } && (CardPositionManager.lastClickedVideoSourceCornerDp ?: 0) >= 20)''',
          '''        VideoCardSourceLayout.SIDE_BY_SIDE -> {
            val sourceCornerDp = if (frozenSnapshot == null) CardPositionManager.lastClickedVideoSourceCornerDp ?: 0
                else frozenSnapshot.transitionSession.sourceCornerDp ?: 0
            val isCapsuleDock = sourceCornerDp >= 24 ||
                (layout.cardHeightPx <= with(density) { 72.dp.toPx() } && sourceCornerDp >= 20)''',
          'Freeze the actual source corner including an absent zero fallback')
    exact('val nativeBarBitmap = CardPositionManager.lastClickedNativeCardBitmap',
          'val nativeBarBitmap = if (frozenSnapshot == null) CardPositionManager.lastClickedNativeCardBitmap else null',
          'No later click bitmap in the Root cover renderer')
    exact('val overlayLayer = CardPositionManager.lastClickedNativeCoverOverlayLayer',
          'val overlayLayer = if (frozenSnapshot == null) CardPositionManager.lastClickedNativeCoverOverlayLayer else null',
          'No later source overlay layer in the Root cover renderer')
    if restore(text, edits) != original:
        raise ValueError('Complete renderer inverse failed')
    proof = dict(path=path, previousWindowsRendererSHA256LF=BASE_SHA256_LF,
        emittedSHA256LF=sha(text), adaptations=edits, completePreviousRendererInverse=True,
        allRemainingOriginalBytesUnchanged=True, unusedEntryLocalReadsRemoved=9,
        existingCallersRetainDefaultBehaviour=True, rootFrozenPathReadsNoGlobalNativeLayers=True,
        frozenNativeBitmapSupport=False, nowPlayingRequiresSeparateTrustedBitmapHandoff=True,
        rootHostQualificationOutsideRendererReceipt=True, actualRootVisualHostVerified=False, kotlinCompiled=False, uiExecuted=False)
    return text, proof


def apply_and_record(path, text, output):
    text, proof = apply(path, text)
    if proof is not None:
        target = (Path(output) / (Path(path).name + '.windows-return-frozen-proof.json')).resolve()
        if os.name == 'nt' and not str(target).startswith('\\\\?\\'):
            target = Path('\\\\?\\' + str(target))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return text

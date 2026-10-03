"""Whole canonical DonateDialog function and untouched author QR; Windows modal leaves."""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, os, sys
from pathlib import Path
sys.dont_write_bytecode = True
UPSTREAM="79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
CATALOG_SHA="2fa53aa78cc27c76a3923cc44750128b3657c340dbcfe44ec13810cbc48becbc"
FEATURE="desktop-whole-original-donate-dialog"
SCREEN="app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt"
SECTIONS="app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt"
BACK="app/src/main/java/com/android/purebilibili/feature/settings/SettingsScreenPolicy.kt"
QR="app/src/main/res/drawable/author_qr.jpg"
SYMBOL="app/src/main/res/drawable/ms_close_24.xml"
def wide(path):
    value=str(Path(path).absolute())
    return Path("\\\\?\\"+value) if os.name=='nt' and not value.startswith('\\\\?\\') else Path(value)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def emit(path,raw):
    wide(path.parent).mkdir(parents=True,exist_ok=True)
    wide(path).write_bytes(raw if isinstance(raw,bytes) else raw.encode('utf-8'))
class Original:
    def __init__(self,repo):
        self.repo=repo;raw=wide(repo/'desktop/tools/v025-canonical-sources.json').read_bytes()
        if sha(raw)!=CATALOG_SHA:raise ValueError('Canonical catalog changed')
        self.catalog=json.loads(raw)
        if self.catalog['upstreamCommit']!=UPSTREAM:raise ValueError('Canonical commit changed')
    def raw(self,path):
        row=self.catalog['paths'][path];file=wide(self.repo/path)
        if file.is_symlink():raise ValueError('Original is linked')
        raw=file.read_bytes();normalized=raw.replace(b'\r\n',b'\n') if row['hashNormalization']=='lf' else raw
        if sha(normalized)!=row['sha256']:raise ValueError('Original changed: '+path)
        return raw
    def text(self,path):return self.raw(path).decode('utf-8').replace('\r\n','\n')
    def row(self,path,mode=None):
        raw=self.raw(path);row=dict(path=path,sha256=sha(raw if path==QR else raw.replace(b'\r\n',b'\n')),features=[FEATURE])
        if path==QR:row['hashNormalization']='raw'
        if mode is not None:row['mode']=mode
        return row
def selected_dialog(screen):
    marker='@Composable\nfun DonateDialog(onDismiss: () -> Unit) {'
    if screen.count(marker)!=1:raise ValueError('Original whole DonateDialog signature changed')
    start=screen.index(marker);body=screen[start:]
    if not body.endswith('}\n'):raise ValueError('Original whole final DonateDialog changed')
    return body,screen[:start].count('\n')+1
def adapt(original):
    source=original;changes=[]
    def replace(before,after,count=1):
        nonlocal source
        if source.count(before)!=count:raise ValueError('Original modal leaf changed: '+before)
        start=0
        for _ in range(count):
            offset=source.index(before,start);changes.append(dict(offset=offset,before=before,after=after))
            source=source[:offset]+after+source[offset+len(before):];start=offset+len(after)
    replace('fun DonateDialog(onDismiss: () -> Unit) {','internal fun DesktopOriginalDonateDialog(bindings: DesktopDonateDialogBindings) {')
    replace('''    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false, // Full screen
            decorFitsSystemWindows = false
        )
    ) {''','''    DesktopOriginalDonateModal(bindings) {''')
    replace('            Column(\n                horizontalAlignment = Alignment.CenterHorizontally,',
            '            Column(\n                modifier = Modifier.desktopOriginalDonateFitClient(),\n                horizontalAlignment = Alignment.CenterHorizontally,')
    replace('painterResource(id = com.android.purebilibili.R.drawable.author_qr)','desktopOriginalDonateQrPainter()')
    replace('.clickable { onDismiss() }','.clickable { bindings.dismiss() }')
    replace('onClick = onDismiss,','onClick = bindings::dismiss,')
    replace('com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_close_24)','DesktopOriginalDonateVectors.vector("ms_close_24")')
    inverse=source
    for change in reversed(changes):
        offset=change['offset'];after=change['after']
        if inverse[offset:offset+len(after)]!=after:raise ValueError('Inverse modal hunk changed')
        inverse=inverse[:offset]+change['before']+inverse[offset+len(after):]
    if inverse!=original:raise ValueError('Whole selected dialog inverse failed')
    return source,dict(originalSelectedFunctionLfSha256=sha(original.encode()),adaptedSelectedFunctionLfSha256=sha(source.encode()),fullSelectedFunctionInverseVerified=True,wholeSettingsScreenAdapted=False,selectedProtocolMembers=['DonateDialog'],changes=changes)
def source_inventory(original):return [original.row(path,'extracted') for path in [SCREEN,SECTIONS,BACK]]
def resource_inventory(original):return [original.row(QR),original.row(SYMBOL)]
def generate(repo,output,resource_output,proof_output):
    sys.path.insert(0,str(repo/'desktop/tools'));original=Original(repo)
    whole=original.text(SCREEN);selected,line=selected_dialog(whole);adapted,proof=adapt(selected)
    prefix='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.*
import com.bilipai.desktop.settings.*

'''
    emit(output/'com/android/purebilibili/feature/settings/DesktopOriginalDonateDialog.kt',prefix+adapted)
    qr=original.raw(QR);target='original-settings-donate/author_qr.jpg'
    if resource_output:emit(resource_output/target,qr)
    helper='''package com.bilipai.desktop.settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import java.io.ByteArrayInputStream
import java.security.MessageDigest
internal const val DESKTOP_ORIGINAL_DONATE_QR_SHA256 = "QR_SHA"
internal fun desktopOriginalDonateQrBytes(): ByteArray {
    val raw = requireNotNull(DesktopDonateDialogBindings::class.java.getResourceAsStream("/original-settings-donate/author_qr.jpg")) { "Original author QR resource missing" }.use { it.readBytes() }
    require(MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) } == DESKTOP_ORIGINAL_DONATE_QR_SHA256) { "Original author QR resource changed" }
    return raw
}
@Composable internal fun desktopOriginalDonateQrPainter(): Painter {
    val bitmap = remember { ByteArrayInputStream(desktopOriginalDonateQrBytes()).use { loadImageBitmap(it) } }
    return remember(bitmap) { BitmapPainter(bitmap) }
}
'''.replace('"QR_SHA"', '"' + sha(qr) + '"')
    emit(output/'com/bilipai/desktop/settings/DesktopOriginalDonateResources.kt',helper)
    spec=importlib.util.spec_from_file_location('donate_vectors',repo/'desktop/tools/extract-upstream-settings-search.py')
    converter=importlib.util.module_from_spec(spec);spec.loader.exec_module(converter);converter.symbol_names=lambda _:['ms_close_24']
    vector=converter.vectors(repo).replace('DesktopSettingsSymbols','DesktopOriginalDonateSymbols').replace('DesktopSettingsVectors','DesktopOriginalDonateVectors')
    emit(output/'com/bilipai/desktop/settings/DesktopOriginalDonateVectors.kt',vector)
    proof.update(upstreamCommit=UPSTREAM,sourcePath=SCREEN,originalWholeScreenLfSha256=sha(whole.encode()),originalFirstLine=line,qr=dict(path=QR,target=target,bytes=len(qr),sha256=sha(qr),untouchedOriginalBytes=True),adapterCompiled=False,actualRootRuntimeVerified=False,actualModalBoundsVerified=False,candidateModified=False)
    if proof_output:emit(proof_output,json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
    return proof
if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path);cli.add_argument('--resource-output',type=Path);cli.add_argument('--proof',type=Path);cli.add_argument('--inventory',action='store_true');cli.add_argument('--resource-inventory',action='store_true')
    args=cli.parse_args();repo=args.repo.resolve();original=Original(repo)
    if args.inventory:print(json.dumps(source_inventory(original),ensure_ascii=False,indent=2))
    if args.resource_inventory:print(json.dumps(resource_inventory(original),ensure_ascii=False,indent=2))
    if args.output:
        proof=generate(repo,args.output.resolve(),args.resource_output.resolve() if args.resource_output else None,args.proof)
        print(json.dumps(dict(fullSelectedFunctionInverseVerified=proof['fullSelectedFunctionInverseVerified'],originalAuthorQrSha256=proof['qr']['sha256'])))

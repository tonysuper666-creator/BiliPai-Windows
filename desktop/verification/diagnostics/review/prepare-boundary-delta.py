"""A separate two-file delta, after the ten-file producer-verbatim install."""
from pathlib import Path
import difflib, hashlib, json
HERE=Path(__file__).resolve().parent
OUT=HERE/'boundary-delta'
OUT.mkdir(exist_ok=True)
changes=[]
for name in ['DesktopDiagnostics.kt','DesktopLocalDiagnosticViewer.kt']:
    relative='desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/'+name
    baseline=(HERE/'payload'/relative).read_bytes()
    text=baseline.decode('utf-8').replace('\r\n','\n')
    if name=='DesktopDiagnostics.kt':
        text=text.replace('import com.bilipai.desktop.plugins.DesktopPluginStore\n',
                          'import com.bilipai.desktop.plugins.DesktopPluginStore\nimport com.bilipai.desktop.update.UpdateStorage\n')
        old='''        check(!Files.isSymbolicLink(dir)&&!Files.isSymbolicLink(path)) {"诊断目录不能是符号链接"}
        Files.createDirectories(dir)'''
        new='''        // Reuse the actual Windows reparse/ancestor guard before creating a missing directory.
        var existing=dir
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=requireNotNull(existing.parent)
        UpdateStorage.existingPathWithoutLinks(existing)
        Files.createDirectories(dir)
        val verifiedDir=UpdateStorage.existingPathWithoutLinks(dir)
        require(Files.isDirectory(verifiedDir,LinkOption.NOFOLLOW_LINKS)) {"诊断目录必须是普通目录"}
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            val verifiedFile=UpdateStorage.existingPathWithoutLinks(path)
            require(Files.isRegularFile(verifiedFile,LinkOption.NOFOLLOW_LINKS)) {"诊断文件必须是普通文件"}
        }'''
    else:
        old='''    LaunchedEffect(diagnostics) {content=diagnostics.viewLocal()}'''
        new='''    LaunchedEffect(diagnostics) {
        try {content=diagnostics.viewLocal()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){content="本地诊断日志无法读取，请重试";result="操作失败，请重试"}
    }'''
    assert text.count(old)==1,name
    desired=text.replace(old,new).encode('utf-8')
    target=OUT/'payload'/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(desired)
    (OUT/(name+'.patch')).write_text(''.join(difflib.unified_diff(baseline.decode('utf-8').replace('\r\n','\n').splitlines(True),
        desired.decode('utf-8').splitlines(True),fromfile='a/'+relative,tofile='b/'+relative)),encoding='utf-8',newline='\n')
    changes.append(dict(path=relative,baselineSha256Bytes=hashlib.sha256(baseline).hexdigest(),payloadSha256Bytes=hashlib.sha256(desired).hexdigest()))
(OUT/'delta-contract.json').write_text(json.dumps(dict(schemaVersion=1,afterInstallPlanSha256Bytes=hashlib.sha256((HERE/'install-plan.json').read_bytes()).hexdigest(),
    files=changes,existingUpdateStorageGuardReused=True,originalAlgorithmsUnchanged=True,mainWritten=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(passed=True,files=len(changes))))

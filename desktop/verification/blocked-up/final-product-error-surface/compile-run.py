from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'discovery-storage-product-ui-proof'
SNAP=HERE.parent/'blocked-up-final-product-snapshot'
PIN='38efc073ee76d2148e700582e0be468d4c0eea24cea1b5111943ac865402a919'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def verify_old():
 assert sha(OLD/'verified-artifacts.json')=='78b134f0e122e02c626934f826dcf31762fc4dd19a6d5feee1c9bbb48f5515c6'
 for r in json.loads((OLD/'verified-artifacts.json').read_text(encoding='utf-8'))['files']:assert sha(OLD/r['path'])==r['sha256Bytes'],r['path']
verify_old();assert sha(SNAP/'manifest.json')==PIN
snapshot=json.loads((SNAP/'manifest.json').read_text(encoding='utf-8'))
old_deps=json.loads((OLD/'dependency-identities.json').read_text(encoding='utf-8'))
deps=[dict(path=r['path'],sha256Bytes=r['sha256Bytes']) for r in snapshot['artifacts']]+old_deps[3:]
def verify_deps():
 for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
verify_deps();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
source=(OLD/'ProductStorageUiFixture.kt').read_text(encoding='utf-8')
source=source[:source.index('private suspend fun settingsManagement')]+source[source.index('fun main(args:Array<String>)'):]
source=source.replace(';settingsManagement(style,output,checks,coroutineContext)','')
source=source.replace('put("cases",4)','put("cases",2)')
source=source.replace('put("backupRestoreFixPresentClaimed",false)','put("backupRestoreExecuted",false);put("productSnapshotContainsRootBackupFix",true)')
source=source.replace('private class ActualProductScene','private val imageChecks=mutableListOf<JsonObject>()\n\nprivate class ActualProductScene')
source=source.replace('import kotlin.math.abs','import java.security.MessageDigest\nimport javax.imageio.ImageIO\nimport kotlin.math.max\nimport kotlin.math.min\nimport kotlin.math.abs')
original='    fun screenshot(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}'
replacement='''    suspend fun screenshot(path:Path){
        val checked=path.fileName.toString().contains("startup-error")||path.fileName.toString().contains("stopped-restart")
        if(!checked){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)};return}
        repeat(80){frame()}
        val hashes=mutableListOf<String>()
        repeat(3){index->repeat(6){frame()}
            val target=path.resolveSibling(path.fileName.toString().removeSuffix(".png")+"-converged-$index.png")
            scene.render(nanos+1).use{Files.write(target,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            hashes+=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)).joinToString(""){"%02x".format(it)}
            if(index==2)Files.copy(target,path)
        }
        verify("actual error/stopped render converges in three frames",hashes.distinct().size==1)
        val image=ImageIO.read(path.toFile());var transparent=0;var opaque=0
        for(y in 0 until image.height)for(x in 0 until image.width){val a=image.getRGB(x,y) ushr 24
            if(a==0)transparent++;if(a==255)opaque++}
        verify("product paints every error/stopped pixel opaque",transparent==0&&opaque==image.width*image.height)
        val error=path.fileName.toString().contains("startup-error")
        val message=assertNotNull(text(if(error)"本地存储无法读取"else"本地设置读取已停止，请重新启动应用。").lastOrNull())
        val action=assertNotNull(text(if(error)"重试读取"else"重新启动应用").lastOrNull())
        val background=image.getRGB(500,300)
        fun ink(node:SemanticsNode):Int{val rect=node.boundsInRoot;var count=0
            for(y in max(0,rect.top.toInt()) until min(image.height,rect.bottom.toInt()+1))
                for(x in max(0,rect.left.toInt()) until min(image.width,rect.right.toInt()+1)){
                    val pixel=image.getRGB(x,y);val difference=abs(((pixel shr 16)and 255)-((background shr 16)and 255))+
                        abs(((pixel shr 8)and 255)-((background shr 8)and 255))+abs((pixel and 255)-(background and 255))
                    if(pixel ushr 24>150&&difference>180)count++
                };return count}
        val messageInk=ink(message);val actionInk=ink(action)
        verify("actual product message pixels contrast with background",messageInk>200)
        verify("actual product action pixels contrast with background",actionInk>100)
        imageChecks+=buildJsonObject{put("file",path.fileName.toString());put("transparentPixels",transparent);put("opaquePixels",opaque)
            put("messageContrastedInkPixels",messageInk);put("actionContrastedInkPixels",actionInk);put("convergedFrameSha256",JsonArray(hashes.map(::JsonPrimitive)))
            put("fixtureAppSurfaceHost",false);put("actualProductBoundarySurface",true)}
    }'''
assert original in source;source=source.replace(original,replacement)
source=source.replace('    Files.writeString(output.resolve("result.json")','    Files.writeString(output.resolve("screenshot-metrics.json"),JsonArray(imageChecks).toString())\n    Files.writeString(output.resolve("result.json")')
write(HERE/'ProductStorageUiFixture.kt',source)
write(HERE/'ProductClassOriginFixture.kt',(OLD/'ProductClassOriginFixture.kt').read_text(encoding='utf-8'))
write(HERE/'fixture-derivation.json',json.dumps(dict(source= str(OLD/'ProductStorageUiFixture.kt'),originalSourceSha256Bytes=sha(OLD/'ProductStorageUiFixture.kt'),
 changes=['omit previously verified management cases','add actual pixel opacity/contrast and three-frame convergence','no change to bare errorTheme or original disk/retry/stopped pointer flow'],
 finalSourceSha256Bytes=sha(HERE/'ProductStorageUiFixture.kt'),productionOverrides=0),indent=2)+'\n')
spec=importlib.util.spec_from_file_location('final_ui_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cp=[r['path'] for r in deps];out=HERE/'classes';safe(out).mkdir(parents=True,exist_ok=False)
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
file=HERE/'compiler.args';args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+cp[0],
 '-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out),str(HERE/'ProductStorageUiFixture.kt'),str(HERE/'ProductClassOriginFixture.kt')])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],
 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
write(HERE/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
names={str(p.relative_to(safe(out))).replace('\\','/') for p in safe(out).rglob('*.class')}
with zipfile.ZipFile(Path(cp[0])) as z:assert not names.intersection(z.namelist()),'Fixture shadows actual product'
sandbox=Path(tempfile.mkdtemp(prefix='bpd-final-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
safe(HERE/'proof').mkdir(exist_ok=True)
for name,main,tail in [('origins','com.bilipai.desktop.ui.ProductClassOriginFixtureKt',[cp[0],str(HERE/'proof/class-origins.json')]),
 ('runtime','com.bilipai.desktop.ui.ProductStorageUiFixtureKt',[str(HERE/'proof')])]:
 file=HERE/(name+'.args');args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false','-Duser.home='+str(sandbox/'home'),
  '-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(cp),main]+tail)
 r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
 write(HERE/(name+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
verify_old();verify_deps();assert sha(SNAP/'manifest.json')==PIN
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClasses='classes',actualSnapshotManifestSha256Bytes=PIN,
 original43ManifestSha256Bytes=sha(OLD/'verified-artifacts.json'),original43Unchanged=True,pureFixtureSourcesOnly=True,
 productionOverrides=0,fixtureSurfaceHost=False,actualProductBoundarySurface=True,HWND=False,sharedGradle=False,HTTP=False,
 fullDesktopShellExecuted=False,PluginRuntimeConstructed=False,MpvPlayerConstructed=False,backupRestoreExecuted=False,actualProcessRestart=False),indent=2)+'\n')

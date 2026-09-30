from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
def safe(p):
    v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\')else'\\\\?\\'+v)
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
p=HERE/'prepared/desktop/tools/extract-upstream-dynamic-editor.py'
spec=importlib.util.spec_from_file_location('editor_generator',p);g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.generate(REPO,safe(HERE/'generated'),standalone=True)
base=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
s=base.read_text(encoding='utf-8').replace('\r\n','\n');assert s.rstrip().endswith('}')
spec=importlib.util.spec_from_file_location('editor_protocol_generator',HERE/'prepared/desktop/tools/extract-upstream-dynamic-editor-protocol.py');protocol=importlib.util.module_from_spec(spec);spec.loader.exec_module(protocol)
members=protocol.generate_members(REPO)
write(HERE/'generated-protocol-members.kt',members)
s=s.rstrip()[:-1]+members+'\n}\n'
s=s.replace('import java.io.IOException','import java.io.IOException\nimport okhttp3.MultipartBody\nimport okhttp3.RequestBody.Companion.toRequestBody\nimport kotlin.random.Random\nimport kotlinx.serialization.json.JsonObject\nimport kotlinx.serialization.json.buildJsonObject\nimport kotlinx.serialization.json.put')
write(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt',s)
write(HERE/'source-inventory.json',json.dumps(g.inventory(REPO),indent=2)+'\n')
write(HERE/'baseline.json',json.dumps(dict(operationsPath=str(base),operationsSha256Bytes=sha(base),snapshot='desktop/.local/dynamic-full-card-main-product-snapshot-lifecycle-final/manifest.json',snapshotSha256Bytes='04e5d4e029621ffa34d360dd590beede56ff9c9110634cc0d1b1bdb181c5e3f3',editorLiquidChromePending=True,fullDetailReplyItemViewPending=True,MainChanged=False),indent=2)+'\n')

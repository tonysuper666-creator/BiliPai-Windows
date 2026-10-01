"""Rebase two selected-source producers after verifying their emitted bodies."""
from pathlib import Path
import difflib,hashlib,importlib.util,json,os,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
BASE=HERE.parents[2]; CANDIDATE=BASE.parent/'BiliPai-v023'
def ext(path):
    value=str(Path(path).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path)
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
out=ext(HERE/'simple-producers-02');out.mkdir()
payloads=[]
for relative in ['desktop/tools/extract-upstream-crash-prompt.py','desktop/tools/extract-upstream-dynamic-gallery-motion-photo.py']:
    original=(BASE/relative).read_text(encoding='utf-8')
    updated=original
    if 'crash-prompt.py' in relative:
        old="PIN='9100a8fbea8e7e581f642653cca5a18429d9a1c55ee0bf3189d5a6a2fe725d90'"
        main=(CANDIDATE/'app/src/main/java/com/android/purebilibili/MainActivity.kt').read_bytes().replace(b'\r\n',b'\n')
        assert original.count(old)==1
        updated=updated.replace(old,"PIN='"+sha(main)+"'")
        updated=updated.replace('import androidx.compose.material3.Text','import com.android.purebilibili.core.ui.components.AppText')
        old_kind,new_kind='crash-old','crash-new'
    else:
        assert original.count("TAG = 'v0.2.3-alpha.9'")==1
        updated=updated.replace("TAG = 'v0.2.3-alpha.9'","TAG = 'v0.2.3'")
        updated=updated.replace("path + ' differs from pinned alpha.9'","path + ' differs from pinned ' + TAG")
        updated=updated.replace("fromfile='alpha9-original-packing'","fromfile=TAG + '-original-packing'")
        old_kind,new_kind='gallery-old','gallery-new'
    prepared=out/Path(relative).name
    prepared.write_text(updated,encoding='utf-8',newline='\n')
    (CANDIDATE/relative).write_text(updated,encoding='utf-8',newline='\n')
    old=load(BASE/relative,old_kind);new=load(prepared,new_kind)
    old_out=out/old_kind/'sources';new_out=out/new_kind/'sources'
    old.generate(BASE,old_out);new.generate(CANDIDATE,new_out)
    compared=[]
    for directory,dirs,names in os.walk(old_out):
        for name in names:
            if not name.endswith('.kt'):continue
            old_file=Path(directory)/name; rel=old_file.relative_to(old_out)
            new_file=new_out/rel
            def body(path):
                text=path.read_text(encoding='utf-8').replace('\r\n','\n')
                return text[text.index('package '):]
            a,b=body(old_file),body(new_file)
            unchanged=a==b
            typography_only=False
            if not unchanged and name=='DesktopPendingCrashLogPrompt.kt':
                expected=a.replace('import androidx.compose.material3.Text','import com.android.purebilibili.core.ui.components.AppText').replace('Text(', 'AppText(')
                typography_only=expected==b
                assert typography_only, 'Stable crash dialog changed beyond original AppText migration'
            else:
                assert unchanged, 'Selected original behavior changed: '+str(rel)
            (out/(name+'.diff')).write_text(''.join(difflib.unified_diff(a.splitlines(True),b.splitlines(True),fromfile='alpha9-selected',tofile='stable-selected')),encoding='utf-8',newline='\n')
            compared.append(dict(path=rel.as_posix(),exactBodyAfterPackageEqual=unchanged,
                originalStableAppTextMigrationOnly=typography_only,
                originalGeneratedSha256Lf=sha(old_file.read_bytes()),candidateGeneratedSha256Lf=sha(new_file.read_bytes()),
                unchangedBodySha256Lf=sha(a.encode())))
    payloads.append(dict(path=relative,preparedSha256Lf=sha(updated.encode()),comparedGenerated=compared))
record=dict(schema='selected-stable-source-producer-rebase-v1',oldTag='v0.2.3-alpha.9',newTag='v0.2.3',
    oldCommit='fcf84853b287662e8a9129ea0d38576c36522a34',newCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    sourceOnly=True,mainInstalled=False,candidateInstalled=True,compiled=False,runtimeAccepted=False,payloads=payloads)
(HERE/'simple-producers-review.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(producers=len(payloads),originalAppTextMigrationAccepted=True,generatedCompared=sum(len(r['comparedGenerated']) for r in payloads))))

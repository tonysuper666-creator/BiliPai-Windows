from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).write_bytes((json.dumps(v,indent=2,ensure_ascii=False)+'\n').encode())
assert json.loads(read(P/'source-audit.json'))['passed']
assert json.loads(read(P/'compile/04/result.json'))['passed']
result=json.loads(read(P/'runs/05/result.json'));assert result['passed'] and result['assertions']==27
include=[];excluded=[]
for p in wide(P).rglob('*'):
 if not p.is_file():continue
 rel=str(p.relative_to(wide(P))).replace('\\','/')
 if rel in ['frozen-handoff.json','install-whitelist.json']:continue
 if p.suffix in ['.jar','.class','.pyc'] or '/runtime/private-store/' in '/'+rel or rel.startswith('audit-baseline-generated/') and not rel.endswith(('CdnRegionPlugin.kt','SponsorBlockInsightPolicy.kt')) or rel.startswith('generated/') and not rel.endswith(('CdnRegionPlugin.kt','SponsorBlockInsightPolicy.kt')):
  excluded.append(dict(path=rel,reason='binary/private fixture Store/unchanged sole producer output not installed'));continue
 include.append(dict(path=rel,sha256Bytes=sha(p),bytes=len(read(p))))
install={'newManual':'prepared/desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPlayerPluginWriteAdmission.kt','exactHunks':'install-exact-hunks.json','replaceWholeFiles':False,'regenerateWithExistingSoleProducer':'desktop/tools/extract-upstream-plugins.py','existingOriginalIdentityFeatureMerge':['app/src/main/java/com/android/purebilibili/feature/plugin/CdnRegionPlugin.kt','app/src/main/java/com/android/purebilibili/feature/plugin/SponsorBlockInsightPolicy.kt'],'originalPluginStoreIdentityUnchanged':True,'rootIndependentNewRuntimeMemberPreserved':True}
save(P/'install-whitelist.json',install)
include.append(dict(path='install-whitelist.json',sha256Bytes=sha(P/'install-whitelist.json'),bytes=len(read(P/'install-whitelist.json'))))
save(P/'frozen-handoff.json',dict(frozen=True,scope='source-only final-write captured plugin candidate; no actual product/fullRoot/native completion acceptance',baseActual69ManifestSHA256Bytes='4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d',actualRuntimeEntries=101,prospectiveExistingFamilyOverrides=4,sourceInputs=5,compilePassed=True,focusedAssertions=27,sourceAuditChecks=27,httpAccountsNativeGui=False,rawRows=len(include),artifacts=sorted(include,key=lambda r:r['path']),excluded=sorted(excluded,key=lambda r:r['path']),install=install,limitations=['real native completed-seek ticket supplied by Root, fixture memory only','Root typed plugin/source ownership integration not executed','accepted final permit is in-flight and cannot be revoked before rename','existing CDN IP/global initialization and legacy callbacks retain original behavior']))
print(json.dumps(dict(path=str(P/'frozen-handoff.json'),sha256Bytes=sha(P/'frozen-handoff.json'),rawRows=len(include)),indent=2))

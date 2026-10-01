from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-home-navigation3-host-parity';OUT=HERE/'navigation3-host-install'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
raw=wide(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='317a635bc0dd27454f0fcdf351050672fd6f5490a0b4f3195581dc5a9bce625d'
frozen=json.loads(raw);rows=frozen.get('artifacts',frozen.get('rawArtifacts'));assert len(rows)==121
for row in rows:assert sha(wide(LANE/row['path']).read_bytes())==row['sha256Bytes'],row['path']
pending=[];records=[]
for row in json.loads(wide(LANE/'install-whitelist.json').read_bytes())['installOnly']:
 data=wide(LANE/row['source']).read_bytes();assert sha(data)==row['sha256Bytes'];target=REPO/row['destination'];assert not target.exists()
 pending.append((target,data));records.append(dict(target=row['destination'],sha256Bytes=sha(data),newPayload=True))
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(reg.read_bytes());assert len(registry['sources'])==902
by={r['path']:r for r in registry['sources']};delta=json.loads(wide(LANE/'registry-delta.json').read_bytes())
assert len(delta['newOriginalIdentityRows'])==24 and len(delta['existingIdentityFeatureMerges'])==6
for row in delta['newOriginalIdentityRows']:
 assert row['path']not in by
 assert sha((REPO/row['path']).read_text(encoding='utf-8').replace('\r\n','\n').encode())==row['sha256']
 registry['sources'].append(row)
for row in delta['existingIdentityFeatureMerges']:
 old=by[row['path']];assert old['sha256']==row['sha256'] and old['mode']==row['preserveExistingMode']
 old['features']=list(dict.fromkeys(old.get('features',[])+row['appendFeatures']))
assert len(registry['sources'])==926
pending.append((reg,(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()))
gradle=REPO/'desktop/build.gradle.kts';text=gradle.read_text(encoding='utf-8');marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(marker)==1 and 'val extractNavigation3Host 'not in text
snippet='''// Complete original navigation host; the 22 DIRECT declarations retain sync's sole ownership.
val extractNavigation3Host by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-navigation3-host.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-navigation3-host").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-navigation3-host.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-navigation3-host" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-navigation3-host"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-navigation3-host")) }
tasks.named("compileKotlin") { dependsOn(extractNavigation3Host) }

'''
text=text.replace(marker,snippet+marker,1)
marker='    implementation(compose.desktop.currentOs)';assert text.count(marker)==1
deps='''
    // Expose existing exact runtime97 Lifecycle modules through Miuix's implementation boundary.
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")'''
assert 'implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0")'not in text
text=text.replace(marker,marker+deps,1);pending.append((gradle,text.encode()))
OUT.mkdir()
for target,data in pending:
 if target.exists():
  before=wide(OUT/'baseline'/target.relative_to(REPO));before.parent.mkdir(parents=True,exist_ok=True);before.write_bytes(target.read_bytes())
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
report=dict(preparedManifestSHA256='317a635bc0dd27454f0fcdf351050672fd6f5490a0b4f3195581dc5a9bce625d',payloads=records,newOriginalIdentities=[r['path']for r in delta['newOriginalIdentityRows']],featureMerges=delta['existingIdentityFeatureMerges'],sourceCountBefore=902,sourceCountAfter=926,existingRuntimeLifecycleModulesExposed=3,newRuntimeArtifactClaimed=False,rootMounted=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(applied=True,payloads=3,newSourceIdentities=24,sourceCount=926,rootMounted=False)))

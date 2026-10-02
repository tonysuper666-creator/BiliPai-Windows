from pathlib import Path
import hashlib,json,os
P=Path(__file__).resolve().parent
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def read(p):return wide(p).read_text(encoding='utf8')
def write(p,v):wide(p).write_text(v,encoding='utf8',newline='\n')
assert json.loads(read(P/'runs/02/result.json'))['passed']
assert json.loads(read(P/'replay.json'))['passed']
assert json.loads(read(P/'initializer-receipt.json'))['sameOriginalConstructorFields']
families=json.loads(read(P/'families.json'));hunks=json.loads(read(P/'exact-hunks.json'));assert len(hunks)==5 and len(families)==3
write(P/'install-contract.json',json.dumps(dict(scope='Story89 reviewed exact delta; apply AFTER unchanged Story89',requiresFrozenStoryManifestSha256='60a8bd713b5138ee126c962dc02db07713cdd1e42d6d6eb6d1d0c6fa2b18013f',copyWhitelist=[],exactHunks='exact-hunks.json',hunkCount=5,productionTargets=families,registryChanges=0,gradleChanges=0,generatedOrBinaryInstallForbidden=True),indent=2)+'\n')
write(P/'ROOT-INTEGRATION.md','''Apply only the five ordered exact edits to the three production targets in install-contract.json, AFTER unchanged Story89. Copy whitelist is empty. Never copy prepared/existing whole files, reference/replay generated bodies, classes or JAR. No registry/Gradle/dependency changes. Existing sole FullOwner and Holder producers retain ownership and regenerate their own outputs.

Story physical leaf obtains the actual DesktopOriginalRootRouteAssembly already passed by RootStack. The captured route must still be active/current, the same Shell Assembly must remain published, and its captured dialog accepted publication must still be current inside environment.commit (same Store -> entry). Covered/outgoing entries mount none of the three shared dialogs. Coin, favorite toggle/save/dismiss/create and follow toggle/save/dismiss callbacks all receive this final captured route/source admission. Existing Holder consumers preserve their old original behavior through the optional callback parameter; this Story caller explicitly supplies the real captured gate. No invented commands.owns API is used.

The raw-page adoption helper independently publishes Mini metadata, Playlist and analytics only inside the captured Binding admission with exact original Session request token/BVID/caller/epoch/entry/accepted source validation. Mini's real consumer only Channel.trySend()s a typed event; Shell later rechecks the exact accepted publication before SMTC. Playlist modifies original in-memory state and only queues LAZY Dispatchers.IO persistence; its write/rename is outside this gate. Diagnostics performs only cached preference reads and enqueue on its existing writer. No disk/native wait/HTTP moved into the gate. Original deferred post-load/note work remains outside and retains its original request-token/subject guards.

initializer-receipt.json proves the complete initializer selection is LF byte-identical to the pinned original source before the seven RHS CDN expression substitutions. These substitutions reverse exactly; only copied comments in the new helper were removed. The original file and producer recipe body are unchanged. Complete original dialog bodies inverse through the explicit platform edits; real sole producer replay shows all unrelated outputs byte unchanged.

runs/02 is the required prospective thirteen-source narrow compile against immutable actual83/101 plus explicitly declared Story89 sources, PASS. runs/01 preserves the initial fixture-source signature mistake (create-folder callback has three actual arguments), corrected only in the prepared hook. This is source-only acceptance; real Root Story/swipes/dialogs/native/HTTP remain pending. Ordinary guest HTTP 412 remains unresolved; no seeded Success, API proxy, network or window run was performed.
''')
files=[]
for f in sorted(wide(P).rglob('*')):
 if not f.is_file() or f.name=='frozen-handoff.json' or f.suffix in {'.class','.jar','.pyc'} or '__pycache__' in f.parts:continue
 files.append(dict(path=str(f.relative_to(wide(P))).replace('\\','/'),sha256Bytes=sha(f),bytes=f.stat().st_size))
write(P/'frozen-handoff.json',json.dumps(dict(schemaVersion=1,scope='Story89 review delta',artifacts=files,artifactCount=len(files),compilePassed=True,copyWhitelistCount=0,orderedHunkCount=5,productionFamilyCount=3,rootRuntimeAccepted=False),indent=2)+'\n')
print('Frozen',len(files),'raw; manifest',sha(P/'frozen-handoff.json'));print('contract',sha(P/'install-contract.json'));print(json.dumps(families,indent=2))

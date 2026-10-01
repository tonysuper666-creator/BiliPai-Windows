from pathlib import Path
import difflib,hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023';OUT=HERE/'miuix5c91-install'
p=REPO/'desktop/tools/extract-upstream-preferences.py';b=p.read_bytes();before=b.decode().replace('\r\n','\n')
old='''    if path.endswith("/AppNavigationComponents.kt"):
        # The pinned Miuix JVM rail uses state=null for the same non-expandable
        # collapsed overload described by the original facade's own comment.
        source = host.substitute(source, "            expanded = false,\\n", "            state = null,\\n")
    elif path.endswith("/AdaptivePreferenceComponents.kt"):
'''
new='''    if path.endswith("/AdaptivePreferenceComponents.kt"):
'''
assert before.count(old)==1
after=before.replace(old,new,1);out=after.encode()
(OUT/'extract-upstream-preferences.py.before').write_bytes(b);p.write_bytes(out)
(OUT/'extract-upstream-preferences.py.after').write_bytes(out)
patch=''.join(difflib.unified_diff(before.splitlines(keepends=True),after.splitlines(keepends=True),fromfile='before/desktop/tools/extract-upstream-preferences.py',tofile='after/desktop/tools/extract-upstream-preferences.py'))
(OUT/'remove-old-rail-adaptation.patch').write_bytes(patch.encode())
sha=lambda x:hashlib.sha256(x).hexdigest()
report=dict(reason='Exact Miuix5c91 supplies the original non-expandable expanded=false rail overload; retire only the older5157 state=null adapter. Preserve original v0.2.3 facade without rewriting library body.',beforeSHA256=sha(b),afterSHA256=sha(out),sourceBodyChanged=False,oldPlatformSubstitutionRetired=True,failedClassesPhase=28,wholeClassesPassed=False)
(OUT/'rail-api-correction.json').write_bytes((json.dumps(report,indent=2)+'\n').encode());print(json.dumps(report))

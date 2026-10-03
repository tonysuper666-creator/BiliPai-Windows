from pathlib import Path
import hashlib
import json
import sys

sys.stdout.reconfigure(encoding='utf8')
root = Path(__file__).resolve().parent
candidate = root.parents[2].parent / 'BiliPai-v023'
def sha(raw): return hashlib.sha256(raw).hexdigest()
def wide(p): return Path('\\\\?\\' + str(p.absolute()))
installation_path = root / 'installation.json'
installation = json.loads(installation_path.read_bytes())
assert not (root / 'installation-attempt01.json').exists()
rows = {r['path']: r for r in installation['sourceTargets']}
repairs = [
    ('desktop/tools/extract-upstream-space-pages.py',
     "    def remaining(s,edit,logs):\n        declarations=helper.members(parser,s)\n",
     """    archiveOwner=repo/'desktop/build/generated/original-favorites/com/android/purebilibili/feature/space/DesktopFavoriteArchiveMappings.kt'
    archiveDeclarations=helper.members(parser,archiveOwner.read_text(encoding='utf8'))
    def verify_sole_declaration(original,existing):
        # These sole producers change visibility only, and already own the full
        # original body. Reject a changed mapping instead of dropping it by name.
        normalize=lambda value: re.sub(r'^\\s*(?:private|internal)\\s+', '', value).strip()
        assert normalize(original)==normalize(existing), 'Existing sole declaration drifted'
    def remaining(s,edit,logs):
        declarations=helper.members(parser,s)
        for name in ['mapSeasonArchiveToVideoItem','mapSeriesArchiveToVideoItem']:
            assert len(declarations[name])==len(archiveDeclarations[name])==1
            declaration=declarations[name][0]['text']
            verify_sole_declaration(declaration,archiveDeclarations[name][0]['text'])
            s=edit(s,declaration,'',1)
"""),
    ('desktop/tools/extract-upstream-space-pages.py',
     "            return logs(s)\n        produce(BASE+'feature/space/SpaceSupporterScreens.kt',pkg+'SpaceSupporterScreens.kt',supporter_screen)",
     """            originalGuardLabel=helper.members(parser,s)['resolveSpaceGuardLevelLabel']
            guardOwner=repo/'desktop/build/generated/space/com/android/purebilibili/feature/space/DesktopUpstreamSpaceDeclarations.kt'
            soleGuardLabel=helper.members(parser,guardOwner.read_text(encoding='utf8'))['resolveSpaceGuardLevelLabel']
            assert len(originalGuardLabel)==len(soleGuardLabel)==1
            verify_sole_declaration(originalGuardLabel[0]['text'],soleGuardLabel[0]['text'])
            s=edit(s,originalGuardLabel[0]['text'],'',1)
            return logs(s)
        produce(BASE+'feature/space/SpaceSupporterScreens.kt',pkg+'SpaceSupporterScreens.kt',supporter_screen)"""),
    ('desktop/build.gradle.kts',
     '    dependsOn(prepareUpstreamSources, extractUpstreamSpaceOverview, extractUpstreamSpaceContributions,\n        extractOriginalVideoTabletFull, extractOriginalFavorites)',
     '    dependsOn(prepareUpstreamSources, extractUpstreamSpace, extractUpstreamSpaceOverview, extractUpstreamSpaceContributions,\n        extractOriginalVideoTabletFull, extractOriginalFavorites)'),
    ('desktop/build.gradle.kts',
     '        layout.buildDirectory.file("generated/space-contributions/com/android/purebilibili/feature/space/DesktopUpstreamSpaceContributionDeclarations.kt"))\n    inputs.files(sources.filter { "stable-original-space-pages-root-parity"',
     '        layout.buildDirectory.file("generated/space-contributions/com/android/purebilibili/feature/space/DesktopUpstreamSpaceContributionDeclarations.kt"),\n        layout.buildDirectory.file("generated/space/com/android/purebilibili/feature/space/DesktopUpstreamSpaceDeclarations.kt"),\n        layout.buildDirectory.file("generated/original-favorites/com/android/purebilibili/feature/space/DesktopFavoriteArchiveMappings.kt"))\n    inputs.files(sources.filter { "stable-original-space-pages-root-parity"'),
]
before, after, receipts = {}, {}, []
for path, old, new in repairs:
    if path not in before:
        raw = wide(candidate / path).read_bytes()
        assert sha(raw) == rows[path]['afterSha256Bytes']
        before[path] = raw
        after[path] = raw.replace(b'\r\n', b'\n').decode()
    text = after[path]
    assert text.count(old) == 1
    pos = text.index(old)
    output = text[:pos] + new + text[pos + len(old):]
    assert output[:pos] + old + output[pos + len(new):] == text
    after[path] = output
    receipts.append(dict(path=path, before=old, after=new, position=pos, reverseExact=True))
for path, text in after.items():
    raw = before[path]
    output = text.replace('\n', '\r\n').encode() if b'\r\n' in raw else text.encode()
    backup = wide(root / 'normal-attempt01-before-repair' / path)
    backup.parent.mkdir(parents=True, exist_ok=True)
    backup.write_bytes(raw)
    wide(candidate / path).write_bytes(output)
    rows[path]['afterSha256Bytes'] = sha(output)
    rows[path]['afterSha256LF'] = sha(text.encode())
(root / 'installation-attempt01.json').write_bytes(installation_path.read_bytes())
installation['rootSoleOwnerRepair'] = dict(exactHunks=4, excludedOriginalHelpers=3,
    newDependencies=[], failedNormalBuildPreserved='actual-build01.log')
installation_path.write_text(json.dumps(installation, indent=2) + '\n', encoding='utf8')
(root / 'sole-owner-repair.json').write_text(json.dumps(dict(exactHunks=receipts,
    beforeHashes={p: sha(v) for p,v in before.items()},
    newDependencies=[], failedNormalBuildPreserved=True), indent=2) + '\n', encoding='utf8')
print('Declared exact sole-owner repair applied; failed normal build01 remains unchanged.')

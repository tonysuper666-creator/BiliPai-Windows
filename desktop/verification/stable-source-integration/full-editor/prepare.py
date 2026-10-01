from pathlib import Path
import difflib, hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
ALPHA = 'fcf84853b287662e8a9129ea0d38576c36522a34'
STABLE = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
EXT = chr(92) * 2 + '?' + chr(92)
def safe(p):
    v = str(Path(p).absolute()); return Path(v if v.startswith(EXT) else EXT + v)
def read(p): return safe(p).read_text(encoding='utf-8').replace('\r\n', '\n')
def write(p, text):
    assert Path(p).absolute().is_relative_to(HERE)
    safe(p.parent).mkdir(parents=True, exist_ok=True); safe(p).write_text(text, encoding='utf-8', newline='\n')
def dump(p, data): write(p, json.dumps(data, indent=2, ensure_ascii=False) + '\n')
def sha(text): return hashlib.sha256(text.encode()).hexdigest()
def module(name, p):
    spec = importlib.util.spec_from_file_location(name, p); value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
def replace(text, before, after, count=1):
    assert text.count(before) == count, before; return text.replace(before, after)
path = 'desktop/tools/extract-upstream-dynamic-editor.py'
old = read(REPO/path)
new = old[old.index('from pathlib import Path'):]
new = '"""Sole fixed-source dynamic editor UI producer with existing Windows bindings.\n\nThe original full-window sheet, draft controls and dialogs are retained.\nSource drift rejects the build by manifest LF hash and exact fixed Git blob.\n"""\n' + new
new = replace(new, "def inventory(repo):\n", "def pinned(repo):\n    identity = load(Path(__file__).resolve().parent, 'editor_fixed_identity', 'extract-upstream-dynamic-reply-protocol.py')\n    return identity.load_pinned_sources(repo, list(dict.fromkeys(EDITOR + DIRECT + REFERENCES)))\n\ndef inventory(repo):\n    source_texts, source_identities = pinned(repo)\n")
new = replace(new, "sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()", "sha256=hashlib.sha256(source_texts[p].encode()).hexdigest()")
new = replace(new, "    host = load(repo, 'editor_host', 'desktop/tools/extract-upstream-plugins.py')", "    source_texts, source_identities = pinned(repo)\n    host = load(repo, 'editor_host', 'desktop/tools/extract-upstream-plugins.py')")
start = new.index('def generate(')
head, tail = new[:start], new[start:]
tail = tail.replace('read(repo,p)', 'source_texts[p]')
new = head + tail
start = new.index("        if p.endswith('DynamicPublishComposer.kt'):")
end = new.index("        elif p.endswith('DynamicPublishPickers.kt'):", start)
composer_adapter = '''        if p.endswith('DynamicPublishComposer.kt'):
            # Retain the complete original stable full-window Material sheet.
            # Only Android's activity result launcher maps to the same owned
            # Windows selection callback. The original 18-item cap is retained.
            s=sub(s,'    var text by remember(initialDraft)',
                '    val platform = LocalDesktopDynamicEditorBindings.current\\n    var text by remember(initialDraft)')
            a=s.index('    val picker = rememberLauncherForActivityResult(')
            b=s.index('\\n    val canPublish =',a)
            original_picker=s[a:b]
            expected=''' + repr('''    val picker = rememberLauncherForActivityResult(
        PickMultipleGalleryVisualMedia(maxItems = MAX_DYNAMIC_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) {
            imageUris = (imageUris + uris.map { it.toString() }).distinct().take(MAX_DYNAMIC_IMAGES)
        }
    }
''') + '''
            if original_picker!=expected: raise ValueError('Original full composer gallery result changed')
            s=s[:a]+s[b:]
            picker_call='platform.pickImages(MAX_DYNAMIC_IMAGES) { uris ->\\n' + \
                '                                            if (platform.isOwned() && uris.isNotEmpty()) {\\n' + \
                '                                                imageUris = (imageUris + uris.map { it.toString() }).distinct().take(MAX_DYNAMIC_IMAGES)\\n' + \
                '                                            }\\n                                        }'
            s=sub(s,'picker.launch(\\n                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)\\n                                        )',picker_call)
            s=sub(s,'picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))',picker_call)
'''
new = new[:start] + composer_adapter + new[end:]
start = new.index('        # Miuix layerBackdrops in Vote are original and available; no fake renderer.')
end = new.index("        emit(p,s,'DesktopOriginal'+Path(p).name)", start)
new = new[:start] + '        # Preserve every original UI renderer and branch outside the explicit platform seams.\n' + new[end:]
new = replace(new, '    return files\n', '''    (output / 'source-identity.json').write_text(json.dumps(source_identities, indent=2) + '\\n', encoding='utf-8')
    (output / 'original-editor-source-bodies.json').write_text(json.dumps(
        [dict(path=p, sha256Lf=hashlib.sha256(source_texts[p].encode()).hexdigest(), originalText=source_texts[p]) for p in EDITOR],
        ensure_ascii=False, indent=2) + '\\n', encoding='utf-8')
    return files
''')
rows = []; patches = []
for relative, before, after in [(path, old, new)]:
    write(HERE/'original'/relative, before); write(HERE/'prepared'/relative, after)
    rows.append(dict(path=relative, baseLfSha256=sha(before), candidateLfSha256=sha(after)))
    patches += list(difflib.unified_diff(before.splitlines(True), after.splitlines(True), fromfile='a/'+relative, tofile='b/'+relative))
for filename, before_literal, after_literal in [
    ('DesktopDynamicEditorSelectedImages.kt', 'for (path in paths.take(9))', 'for (path in paths.take(18))'),
    ('DesktopDynamicEditorWindowsPickers.kt', 'maxItems = maxItems.coerceIn(1, 9)', 'maxItems = maxItems.coerceIn(1, 18)'),
    ('DesktopDynamicGallerySelection.kt', 'require(maxItems in 1..9) { "Dynamic editor image capacity must be between 1 and 9" }', 'require(maxItems in 1..18) { "Dynamic editor image capacity must be between 1 and 18" }'),
]:
    relative = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/' + filename
    before = read(REPO/relative); after = replace(before, before_literal, after_literal)
    write(HERE/'original'/relative, before); write(HERE/'prepared'/relative, after)
    rows.append(dict(path=relative, baseLfSha256=sha(before), candidateLfSha256=sha(after)))
    patches += list(difflib.unified_diff(before.splitlines(True), after.splitlines(True), fromfile='a/'+relative, tofile='b/'+relative))
write(HERE/'candidate.patch', ''.join(patches))
identity_path = 'desktop/tools/extract-upstream-dynamic-reply-protocol.py'
write(HERE/'prepared'/identity_path, read(REPO/identity_path))
producer = module('prepared_stable_full_editor', HERE/'prepared'/path)
files = producer.generate(REPO, safe(HERE/'generated'))
identities = json.loads(read(HERE/'generated/source-identity.json'))
assert all(r['pinnedCommit']==STABLE for r in identities)
dump(HERE/'candidate-source-inventory.json', dict(fixedStableCommit=STABLE, sourceCandidates=rows,
    requiredIdentityProducerLfSha256=sha(read(REPO/identity_path)), MainChanged=False, sharedGradle=False))
dump(HERE/'generated-inventory.json', [dict(path=str(p).removeprefix(EXT), sha256Bytes=hashlib.sha256(safe(p).read_bytes()).hexdigest()) for p in files])
gradle = read(REPO/'desktop/build.gradle.kts')
anchor='    inputs.files("tools/extract-upstream-dynamic-editor.py", "tools/extract-upstream-plugins.py",'
assert gradle.count(anchor)==1
adapted_gradle=gradle.replace(anchor,'    inputs.files("tools/extract-upstream-dynamic-editor.py", "tools/extract-upstream-dynamic-reply-protocol.py", "upstream-sources.json", "tools/extract-upstream-plugins.py",')
write(HERE/'gradle-input-only.patch', ''.join(difflib.unified_diff(gradle.splitlines(True),adapted_gradle.splitlines(True),fromfile='a/desktop/build.gradle.kts',tofile='b/desktop/build.gradle.kts')))
print(json.dumps({'generatedKotlinFiles':len(files),'fixedSourceFiles':len(identities),'candidateFiles':rows},indent=2))

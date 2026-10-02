from pathlib import Path
import hashlib, json, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.dont_write_bytecode = True
import packet_inventory_current as inspection
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
sha = lambda value: hashlib.sha256(value).hexdigest()
packets = inspection.inspect()
tablet_lane = MAIN / 'desktop/.local/stable-tablet-owner-uploads-platform-parity'
tablet = json.loads(inspection.read(tablet_lane / 'install-contract.json'))
payloads = []
for row in tablet['copyWhitelist']:
    source = Path(row['source']['path'])
    data = inspection.read(source)
    assert sha(data) == row['source']['sha256Bytes'] and len(data) == row['source']['size']
    payloads.append((row['target'], source, data))
danmaku_lane = MAIN / 'desktop/.local/stable-video-owner-danmaku-cache-binding-parity'
danmaku = json.loads(inspection.read(danmaku_lane / 'install-whitelist.json'))
tool = [row for row in danmaku['rows'] if row.get('kind') == 'newTool']
assert len(tool) == 1
source = danmaku_lane / tool[0]['source']
data = inspection.read(source)
assert sha(data) == '95a3676ac440e13c98285f42260830b9cccb0df47f7512bd1880e3211eded9a5' and len(data) == 4911
payloads.append((tool[0]['destination'], source, data))
assert len(payloads) == 3
receipt_path = HERE / 'joint-payload-completion-01.json'
assert not receipt_path.exists()
for path, _, _ in payloads:
    assert path.startswith(('desktop/src/', 'desktop/tools/')) and '..' not in Path(path).parts
    assert (REPO / path).resolve().is_relative_to(REPO.resolve()) and not (REPO / path).exists(), path
for path, _, data in payloads:
    destination = REPO / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    assert destination.read_bytes() == data
receipt_path.write_bytes((json.dumps(dict(schema=1, reason='Explicit new copyWhitelist paths missed by the old prepared-path naming heuristic',
    generatedCopies=False, wholeExistingCopies=False, rawPrerequisitePacketsVerified=len(packets),
    rawPrerequisiteArtifactsVerified=sum(row['rawVerified'] for row in packets),
    files=[dict(path=path, new=True, sourcePath=str(source), sha256Bytes=sha(data), bytes=len(data)) for path, source, data in payloads]), indent=2) + '\n').encode())
print(json.dumps(dict(additionalCanonicalNewPayloads=3, totalInstalledSourceFiles=97, generatedOrBinaryCopies=0)))

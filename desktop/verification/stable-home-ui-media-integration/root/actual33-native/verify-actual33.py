"""Read-only validation of the completed actual33 cohort, no native/JVM rerun."""
from pathlib import Path
import hashlib, importlib.util, json, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
RUN = HERE / 'runs/actual33-01'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    from PIL import Image
    before = json.loads((RUN/'dependency-pins-before.json').read_text(encoding='utf-8'))
    after = json.loads((RUN/'dependency-pins-after.json').read_text(encoding='utf-8'))
    assert before == after
    assert len(before['runtime']) == 97 and before['declaredCompileOnlyPreparedDependencies'] == []
    result = json.loads((RUN/'proof/result.json').read_text(encoding='utf-8'))
    accepted = json.loads((RUN/'accepted.json').read_text(encoding='utf-8'))
    symbols = json.loads((RUN/'fixture-symbol-audit.json').read_text(encoding='utf-8'))
    assert result['status'] == 'PASS' and result['assertions'] == 19
    assert accepted['productionClassOverrides'] == 0 and accepted['actualClassSourcesVerified'] == 9
    assert symbols['productionClassIntersection'] == [] and symbols['fixtureClassCount'] == 12
    with Image.open(RUN/'proof/actual-offscreen-native-texture.png') as image:
        image = image.convert('RGBA'); assert image.size == (256,128)
        samples = {str(point): list(image.getpixel(point)) for point in [(128,64),(32,16),(0,0)]}
    center = samples['(128, 64)']
    assert 100 <= center[0] <= 155 and 100 <= center[1] <= 155 and center[2] > 230 and center[3] == 255
    assert samples['(32, 16)'] == [255,255,255,255] and samples['(0, 0)'] == [255,255,255,255]
    source_manifest = json.loads((HERE/'prepared-fixture-handoff.json').read_text(encoding='utf-8'))
    for item in source_manifest['artifacts']:
        assert sha(HERE/item['path']) == item['sha256Bytes'], item['path']
    value = dict(status='PASS', readOnlyVerification=True, nativeOrJvmRerun=False, originalPreparationArtifactsStillMatch=len(source_manifest['artifacts']), runtimeCpPrePostIdentical=True, runtimeEntries=97, loadedActualClassesVerified=9, productionOverrides=0, resultSha256Bytes=sha(RUN/'proof/result.json'), acceptedSha256Bytes=sha(RUN/'accepted.json'), actualTexturePngSha256Bytes=sha(RUN/'proof/actual-offscreen-native-texture.png'), independentImageReader='Pillow', actualOffscreenSamples=samples, sourcePreparationManifestSha256Bytes=sha(HERE/'prepared-fixture-handoff.json'))
    (HERE/'actual33-readonly-verification.json').write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(value,indent=2))
if __name__=='__main__': main()

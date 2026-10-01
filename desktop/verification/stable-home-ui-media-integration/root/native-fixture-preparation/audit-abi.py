"""Pure bytes/source audit; no DLL loading, native calls, JVM fixture, or media process."""
from pathlib import Path
import hashlib, importlib.util, json, re, struct, sys, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
PREPARED = MAIN / 'desktop/.local/stable-home-platform-media-parity'
NATIVE = MAIN / 'desktop/native/windows-x64'
def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def pin(path): return dict(path=str(path), sha256Bytes=sha(path))
def read(path): return safe(path).read_text(encoding='utf-8')
def pe_exports(path):
    data = safe(path).read_bytes()
    assert data[:2] == b'MZ'
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    assert data[pe:pe+4] == b'PE\0\0'
    machine, sections = struct.unpack_from('<HH', data, pe+4)
    optional_bytes = struct.unpack_from('<H', data, pe+20)[0]
    optional = pe+24; assert struct.unpack_from('<H', data, optional)[0] == 0x20b
    table = optional+optional_bytes
    mapping = []
    for i in range(sections):
        item = table+i*40
        virtual_size, virtual_address, raw_size, raw_offset = struct.unpack_from('<IIII', data, item+8)
        mapping.append((virtual_address, max(virtual_size, raw_size), raw_offset))
    def offset(rva):
        for address, length, start in mapping:
            if address <= rva < address+length: return start+rva-address
        raise AssertionError(('Unmapped PE RVA', rva))
    export_rva, export_bytes = struct.unpack_from('<II', data, optional+112)
    export = offset(export_rva)
    names_count = struct.unpack_from('<I', data, export+24)[0]
    names_rva = struct.unpack_from('<I', data, export+32)[0]
    names = []
    for i in range(names_count):
        name = offset(struct.unpack_from('<I', data, offset(names_rva)+i*4)[0])
        names.append(data[name:data.index(b'\0', name)].decode('ascii'))
    return dict(machineHex=hex(machine), peOptionalMagic='0x20b', namedExportCount=names_count, exportTableBytes=export_bytes), set(names)
def anchor(path, text):
    source = read(path); start = source.index(text)
    return dict(path=str(path), line=source[:start].count('\n')+1, token=text)
def main():
    spec = importlib.util.spec_from_file_location('runner', HERE / 'run.py')
    runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
    for name, value in runner.EXPECTED_NATIVE.items(): assert sha(NATIVE/name) == value
    header = PREPARED / 'official-mpv-headers/render.h'
    client = PREPARED / 'official-mpv-headers/client.h'
    software = PREPARED / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/player/MpvSoftwareFrames.kt'
    player = PREPARED / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt'
    binding = PREPARED / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/player/MpvNative.kt'
    home = PREPARED / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeOwnedMedia.kt'
    signatures = {
        'mpv_render_context_create': 'fun mpv_render_context_create(result: PointerByReference, handle: Pointer, params: Pointer): Int',
        'mpv_render_context_set_update_callback': 'fun mpv_render_context_set_update_callback(context: Pointer, callback: MpvRenderUpdateCallback, data: Pointer?)',
        'mpv_render_context_update': 'fun mpv_render_context_update(context: Pointer): Long',
        'mpv_render_context_render': 'fun mpv_render_context_render(context: Pointer, params: Pointer): Int',
        'mpv_render_context_free': 'fun mpv_render_context_free(context: Pointer)',
    }
    info, exports = pe_exports(NATIVE / 'libmpv-2.dll'); assert info['machineHex'] == '0x8664'
    for name, signature in signatures.items():
        assert name in exports, name
        assert signature in read(binding), signature
        assert re.search(r'MPV_EXPORT\s+(?:int|void|uint64_t)\s+'+name+r'\(', read(header)), name
    constants = {'API_TYPE': 1, 'SKIP_RENDERING': 13, 'SW_SIZE': 17, 'SW_FORMAT': 18, 'SW_STRIDE': 19, 'SW_POINTER': 20}
    for suffix, value in constants.items(): assert re.search('MPV_RENDER_PARAM_'+suffix+r'\s*=\s*'+str(value)+r'\b', read(header))
    assert 'uninitialized garbage' in read(header) and '"bgr0"' in read(software)
    assert 'typedef void (*mpv_render_update_fn)(void *cb_ctx);' in read(header)
    assert 'ColorAlphaType.OPAQUE' in read(home)
    assert read(player).index('try { softwareRenderer?.close() }') < read(player).index('native.mpv_terminate_destroy(handle)')
    anchors = [anchor(header, token) for token in [' * Threading', ' * Software renderer', ' * You must free the context with mpv_render_context_free() before', 'MPV_RENDER_PARAM_API_TYPE = 1', 'MPV_RENDER_PARAM_SKIP_RENDERING = 13', 'MPV_RENDER_PARAM_SW_SIZE = 17', 'MPV_RENDER_PARAM_SW_FORMAT = 18', 'MPV_RENDER_PARAM_SW_STRIDE = 19', 'MPV_RENDER_PARAM_SW_POINTER = 20', 'typedef void (*mpv_render_update_fn)', 'MPV_EXPORT uint64_t mpv_render_context_update']]
    anchors += [anchor(software, token) for token in ['internal class MpvSoftwareTarget', 'internal class MpvSoftwareRenderer', 'private val callback =', 'params.string(18, "bgr0")', 'copied[y * stride + x * 4 + 3] = 0xff.toByte()', 'native.mpv_render_context_free(it)', 'private class RenderParameters']]
    anchors += [anchor(home, token) for token in ['internal class DesktopHomeMediaLifetime', 'commitIfCurrent {', 'override fun close()', 'internal fun DesktopHomeMpvTexture', 'ColorAlphaType.OPAQUE', 'LaunchedEffect(own, frame?.sourceVersion)']]
    snapshot = MAIN / 'desktop/.local/stable-product-snapshot-32'
    cp = runner.verify_cp(snapshot, '28118f431eb78418791426c914690143faedeeaef14cc11fb216ac648caad413', 'aba962efa0ce8cdd882f692e87a95d533e229766e091b4b5fa6f9d68f8b3101c')
    with zipfile.ZipFile(safe(snapshot / 'main-kotlin.jar')) as jar:
        missing = [name for name in runner.REQUIRED_CLASSES if name.replace('.', '/')+'.class' not in jar.namelist()]
    assert len(cp) == 97 and missing, 'If media has been installed, obtain a new Root-authorized product graph before running'
    result = dict(status='PASS_SOURCE_AND_ABI_ONLY', nativeBinary=pin(NATIVE/'libmpv-2.dll'), nativePE=info, checkedRenderExports=sorted(signatures), originalHeaderPins=[pin(header), pin(client)], originalHeaderCommit='69e63f425a531f814431fba12750bdb3721357f2', sourcePins=[pin(path) for path in [software, player, binding, home, HERE/'NativeHomeMediaFixture.kt', HERE/'run.py']], sourceAnchors=anchors, scalarAbi=dict(platform='Windows x64 LLP64', cEnumAndIntBytes=4, pointerBytes=8, sizeTBytes=8, uint64ReturnJna='Long', inferredRenderParamBytes=16, fieldOffsets=dict(type=0,data=8), note='Layout derived from fixed x64 PE and official C fields; no native sizeof test has run'), softwareParameters=constants, bgr0FourthByte='Officially uninitialized; production copies and sets per-pixel alpha to 255, then uses Skia OPAQUE', threading='Separate dedicated render thread; callback only wakes; render context freed and joined before core terminate. Source review, not runtime handle counter proof.', nativeArtifacts=[pin(NATIVE/name) for name in runner.EXPECTED_NATIVE], preparedCompileResults=[pin(HERE/'runs'/run/'compile-result.json') for run in ['compile-01','compile-02']], actual32Preflight=dict(manifest=pin(snapshot/'manifest.json'), orderedCp=pin(snapshot/'ordered-runtime-cp.json'), runtimeEntries=len(cp), requiredMediaClassesMissing=missing, executionAllowed=False), nativeExecution=False, dllLoaded=False, ffmpegExecuted=False, actualProductRuntimeProof=False)
    safe(HERE/'abi-source-review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(dict(status=result['status'],checkedExports=len(signatures),actual32Missing=len(missing),abiReviewSha256Bytes=sha(HERE/'abi-source-review.json')),indent=2))
if __name__ == '__main__': main()

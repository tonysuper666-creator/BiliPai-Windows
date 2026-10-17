"""Complete pinned v043 Home card models and native navigation, in existing producers.

The v041 status-model lineage is verified before replacing ListModels. Original
archives are immutable. Platform hooks retain the established Windows ownership
and request graph; native card identities must never be submitted to AV services.
"""
from pathlib import Path
import difflib
import hashlib
import json
import re

COMMIT = '35e2fac8a3870a2227a323e19f29f1e93d7e591c'
ARCHIVE = 'desktop/upstream-slices/v043-home-native-cards'
MANIFEST_SHA256 = '6ef313927e77acc7e0e682b29b0cd43f1c2af876514660b33be82ce26a63592d'
BASE_SHA256 = '3da884027dae2c641ac4deb90f5149dc9462d1b5d786f8e7db547f16f94e75f1'
PINS = [{'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/ListModels.kt', 'archiveFile': 'ListModels.kt', 'bytes': 29374, 'sha256': '5cb726df0e15c1ffcd79f3722cf9ffae12ffe30a8f0fb784ad223419bb578d1a', 'gitBlob': 'ad7776d2fae444def57a7034ad373018e0515c25', 'referenceOnly': False}, {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/RecommendationReason.kt', 'archiveFile': 'RecommendationReason.kt', 'bytes': 2446, 'sha256': '62af39b81966634667b2af5ca926dd1e1cbf9942425f4a87a8b869cc00b4b7b5', 'gitBlob': 'b469358558af729b5d2d5fc6ee3e007ee2eede57', 'referenceOnly': False}, {'path': 'core-data/src/test/java/com/android/purebilibili/data/model/response/RecommendationNativeCardTest.kt', 'archiveFile': 'RecommendationNativeCardTest.kt', 'bytes': 3658, 'sha256': '816f03df043673eeb1552c556fb36e9f9ca8c94b33e341cb2addecbb86a47f24', 'gitBlob': '4d9663558ad69dba768fa8943e952c244920e44c', 'referenceOnly': False}, {'path': 'core-data/src/test/java/com/android/purebilibili/data/model/response/RecommendationReasonTest.kt', 'archiveFile': 'RecommendationReasonTest.kt', 'bytes': 2329, 'sha256': '295ad5516feca92e780add7994eb48429f5f271669d2c8c4231113bf2d949e54', 'gitBlob': '2b25f48354c4c016a34ce5cdd0a7f1847607b4bc', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeVideoClickRequest.kt', 'archiveFile': 'HomeVideoClickRequest.kt', 'bytes': 563, 'sha256': '6fd5bf47f2d6a08aadf785fb043515aa8ef8a54b788a2adfabe24a5955ca7957', 'gitBlob': 'd71160f9da0b8db906197adca89eaaf8d965d362', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/navigation/HomeVideoNavigationPolicy.kt', 'archiveFile': 'HomeVideoNavigationPolicy.kt', 'bytes': 3024, 'sha256': '3be20c6fd9dce31ab488a94bf42001b8efe0e948b3ae3e81a33c896e8385eb93', 'gitBlob': 'c5c85dfe39982092fc600f2969bfe22d7df93e15', 'referenceOnly': False}, {'path': 'app/src/test/java/com/android/purebilibili/navigation/HomeVideoNavigationPolicyTest.kt', 'archiveFile': 'HomeVideoNavigationPolicyTest.kt', 'bytes': 5094, 'sha256': '545f1a3fa95b577e4de61e79509b8178ba15047d482d349c3b87e3259be005bd', 'gitBlob': '1b6b9aafcb10ab16f971e432f2562ce46df39728', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt', 'archiveFile': 'HomeScreen.kt', 'bytes': 179052, 'sha256': '503e5c049b1f7878bbca29f2f93984b5f3715687e77b71c9ce0b3e73fb7f432c', 'gitBlob': '6d2224c68dc56bf93351acc9bfadd720c8f1c1c0', 'referenceOnly': True}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt', 'archiveFile': 'HomeViewModel.kt', 'bytes': 100651, 'sha256': 'e9d69b15875589dc474623f3d6e262faa9241d3491d661f2f3a204f4462d5366', 'gitBlob': 'd4db07904ead890fdf87edbe080ae5a7191a65c9', 'referenceOnly': True}, {'path': 'app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt', 'archiveFile': 'VideoRepository.kt', 'bytes': 89576, 'sha256': '53a97695342686ea554491ef4b6640e5a556bcf8b001acfbc0987e2a2dbf447a', 'gitBlob': 'a523a9b531beb88ee4e62ddcd76281bc2f4e10eb', 'referenceOnly': True}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeCategoryPage.kt', 'archiveFile': 'HomeCategoryPage.kt', 'bytes': 68370, 'sha256': '673adbd50bfbd512ef0d60a94cff2b51e611f1e6ebbaad2e5b536599dba6c519', 'gitBlob': '8f5146755e83112d6facd328125f0838b7dd9b3f', 'referenceOnly': True}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/HomeHeroCarousel.kt', 'archiveFile': 'HomeHeroCarousel.kt', 'bytes': 33849, 'sha256': 'af00e86c0568b08230d22057e690651ac03d9c19a8b37d5b125867d141891363', 'gitBlob': 'cfa883fc625fe895aa41b3fa76424e8cd49f595b', 'referenceOnly': True}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCard.kt', 'archiveFile': 'VideoCard.kt', 'bytes': 102735, 'sha256': 'f0e5784f4adbb7718e717184c9d42a632ac708cce3c7939594182f271947e32a', 'gitBlob': '2ab61d81d41e3d0ae95f23525a6ddd91aebdf5d1', 'referenceOnly': True}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/cards/HomeStyleSingleColumnVideoCard.kt', 'archiveFile': 'HomeStyleSingleColumnVideoCard.kt', 'bytes': 18311, 'sha256': 'a367f0e4bb1fd3ee8b0ba810d8e77cba767f3d5a7fa77b5c558d9450cc7be82e', 'gitBlob': '25d5578324115928c65c9fff99cf64e7fc397581', 'referenceOnly': True}]
REVIEWED_LIST_DELTAS = [['insert', 238, 238, 238, 240, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'd206a2453db76c1efdfe887e9fb050997561de1db66e8ea160359fb9d33bb52d'], ['replace', 251, 252, 253, 259, '6a15a5ac6b7282edacf1b6f1e942808958a8fe23adc2511d04c3bc349d53caf6', '898a50501d79f7fd20ea2c373eba8b2f8d9df7c47e278984822eddd2417c6790'], ['replace', 375, 376, 382, 403, 'a8b21372f1e632ade926144931f0958be491c01db604ce2c7f941c716fb09d81', 'bb108573682848ce02d07fcbe2fe395c077940b5122a7fb74314a8b77b12d7f7'], ['insert', 378, 378, 405, 408, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'fa8f7bd711970da744123f788881280dc8acaacde1ba5c13e5d741d346fa847c'], ['replace', 380, 383, 410, 422, 'a43a529a73526d515b9fa14a36c59897d4d91c85148a78a01907ca028998ee4e', 'ad20b0a7982bf5aafb8d7d683ac2104085f5a3f3c1e51d7006bec0653803da8c'], ['replace', 394, 395, 433, 434, '81e0b5e319df87af6b9299fb7e2e0c344e923b79d219e4b3771fd70a41e2a8d7', 'fcb3e98c6481f4ad60ffac29fd83b5833a1f96dd0cb26307b7b0d75c57a03d9e'], ['replace', 397, 398, 436, 443, '818b156e4655595096ead9d32832b26d29e5beb747a11acc8b49a4d06d3cf5d6', '794c937b9a07c2bc73003c4195ffd47222e9eef0e3f0047fab995f19b59fd006'], ['replace', 691, 692, 736, 758, 'd969bed3463eaff14266fd8808b2fd0af36ab7c73a32f48857a6febdd5e326c8', '9d1ea2faa0d98988bfdac4788398af33d75667d3b872825b60c679fa4cfbe651'], ['insert', 693, 693, 759, 763, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '208b2ca3fa280ddaf31166746c734c327b247e35b2673ed21a9609d6d3c994b6'], ['insert', 694, 694, 764, 767, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '6f0c46e40d86a50789aebbd68b681aa16c48e74f76f8a671bb4cca75c624c4c9'], ['replace', 710, 713, 783, 796, 'fbccecff58b206253d6a2eae2693b32c3deab20b2707978d7236b7e32ab72a59', 'a5b3396c10f67fb2466f7133e7d8f433ded7b2b3e5139b49b9ad47fa33512583'], ['replace', 717, 718, 800, 801, 'ccd26a6581d58eb9caad1334e5caee084ef0dbc6f6f90a7db104f7b414cebb71', '2b459f17f7f9780cca44b771c6e673e4c0e9ac5a89efd432e6aac427c661d4b3'], ['insert', 721, 721, 804, 805, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'f8c9454c47fa916eebf844314d714b4a37031ed4887218f0b456ed4c2cf664e2'], ['replace', 726, 727, 810, 816, 'b5ea427dd9b045e92c6be8d93b656bd6f4b78af52e7b842dc241bfa31966e75f', '405f5f6b001f917d5a55ccab7da7c13038137ffc00e211bd8e539eb58192a474'], ['replace', 730, 731, 819, 820, 'c30773d8c305bf4d37742990cfe83e22c4eefa37cfbe01ed75322fc9bcc13a5b', '0d1a2b75f3bbf8af2dadb91ed161aef1d3d84520087464bd19dad87da4465d1f'], ['insert', 732, 732, 821, 823, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '6a121507ec4487751b6f328748ec71ad2a4ed8d230ca99ffe1debc1020c75c76'], ['replace', 733, 734, 824, 825, 'd6816d9585ca18ff7403220970a08ee7e2384c12166b4e206ec21e6e59a61b23', 'ff35d1e0203fa4ffea56ecadceaf42af2bace5fc83d336aaf0fa1abc80a6ce4c'], ['replace', 757, 758, 848, 849, '6de43f2ed6de92d4425f5520778cf7d972115ad35c870ebf70b8d4bee5b70e15', 'abd105aee5e8cdb1018a68bbc459529cd42a7fe199134e6047964915e1b3a3e1'], ['insert', 773, 773, 864, 867, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '7130b93912008fc0ed232bfb7c2d346ac20efb5d61694e61b8fb6e652dba5608']]
TEST_CASES = {'RecommendationNativeCardTest.kt': 6, 'RecommendationReasonTest.kt': 4,
              'HomeVideoNavigationPolicyTest.kt': 10}
TEST_IMPORTS = ((b'import org.junit.Assert.*\n', b'import kotlin.test.*\n'),
                (b'import org.junit.Test\n', b'import kotlin.test.Test\n'))


def sha(raw):
    return hashlib.sha256(raw.encode('utf-8') if isinstance(raw, str) else raw).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def verified_raw(repo):
    root = Path(repo).resolve() / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing or linked v043 Home archive')
    expected = {'manifest.json', *(row['archiveFile'] for row in PINS)}
    need({p.name for p in root.iterdir()} == expected, 'Unexpected v043 Home archive leaves')
    for name in expected:
        need((root/name).is_file() and not (root/name).is_symlink(), 'Linked/non-file Home input: ' + name)
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'v043 Home manifest pin changed')
    manifest = json.loads(raw)
    need((manifest['schemaVersion'], manifest['repository'], manifest['tag'], manifest['commit'], manifest['files']) ==
         (1, 'jay3-yy/BiliPai', 'v0.4.3', COMMIT, PINS), 'v043 Home manifest identity changed')
    result = {}
    for row in PINS:
        raw = (root/row['archiveFile']).read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'v043 Home bytes changed: ' + row['archiveFile'])
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode('ascii') + b'\0' + raw).hexdigest()
        need(blob == row['gitBlob'], 'v043 Home Git blob changed: ' + row['archiveFile'])
        result[row['archiveFile']] = raw
    return result


def advance_model(repo, before):
    need(sha(before) == BASE_SHA256, 'The complete v041 ListModels predecessor changed')
    after = verified_raw(repo)['ListModels.kt']
    old, new = before.splitlines(keepends=True), after.splitlines(keepends=True)
    ops = [op for op in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes() if op[0] != 'equal']
    reviewed = [[tag, a, b, c, d, sha(b''.join(old[a:b])), sha(b''.join(new[c:d]))]
                for tag, a, b, c, d in ops]
    need(reviewed == REVIEWED_LIST_DELTAS, 'Unreviewed changes outside native Home model hunks')
    forward, inverse = old[:], new[:]
    for _, a, b, c, d in reversed(ops):
        forward[a:b] = new[c:d]
        inverse[c:d] = old[a:b]
    need(b''.join(forward) == after and b''.join(inverse) == before,
         'Home model inverse did not restore the entire predecessor')
    return after, dict(tag='v0.4.3', upstreamCommit=COMMIT, archive=ARCHIVE,
        manifestSha256=MANIFEST_SHA256, sha256=sha(after), bytes=len(after),
        predecessorSha256=BASE_SHA256, reviewedHunks=reviewed,
        identicalToArchive=True, completeOriginalInverse=True)


def source_receipt(name, raw):
    row = next(row for row in PINS if row['archiveFile'] == name)
    return dict(mode='raw-v043', source=row['path'], upstreamCommit=COMMIT,
                sha256=sha(raw), bytes=len(raw), identicalToArchive=True)


def emit_support(repo):
    raw = verified_raw(repo)['RecommendationReason.kt']
    return {'com/android/purebilibili/data/model/response/RecommendationReason.kt':
            (raw, source_receipt('RecommendationReason.kt', raw))}


def emit_tests(repo):
    files = verified_raw(repo)
    result = {}
    for name, count in TEST_CASES.items():
        raw, edits = files[name], []
        data = raw
        for before, after in TEST_IMPORTS:
            if before in data:
                need(data.count(before) == 1, 'Original Home test import is not unique: ' + name)
                data = data.replace(before, after, 1)
                edits.append((before, after))
        restored = data
        for before, after in reversed(edits):
            need(restored.count(after) == 1, 'Adapted Home test import is not unique: ' + name)
            restored = restored.replace(after, before, 1)
        need(restored == raw and raw.count(b'    @Test\n') == data.count(b'    @Test\n') == count,
             'Original Home test bodies/count changed: ' + name)
        need(b'org.junit' not in data, 'JUnit4 reference remains in Home tests: ' + name)
        row = next(row for row in PINS if row['archiveFile'] == name)
        relative = row['path'].split('/src/test/java/', 1)[1]
        result[relative] = (data, dict(mode='whole-v043-test-import-adaptation', source=row['path'],
            upstreamCommit=COMMIT, sha256=sha(data), cases=count, completeOriginalInverse=True,
            importEdits=[dict(before=a.decode().strip(), after=b.decode().strip()) for a, b in edits]))
    return result


LEGACY_STATUS_TEST = 'core-data/src/test/java/com/android/purebilibili/data/model/response/VideoStatusLabelsTest.kt'
LEGACY_STATUS_EMITTED_SHA256 = '5802d3ae505a73ab3eb79f31fcbd4ce961a8778f1ff0a39923e9ebad02765b92'
LEGACY_STATUS_EXPECTATION = '        assertEquals(listOf("独家"), pgc.statusLabels)\n'
NATIVE_STATUS_EXPECTATION = '        assertEquals(listOf("独家", "番剧"), pgc.statusLabels)\n'


def compatible_legacy_status_test(repo, path, data, receipt):
    """The old test survived upstream043, unlike its new native-card expectation.

    Keep its six real conversion cases and duration assertion; record the sole
    expected-list migration separately from the original JUnit import adapter.
    No original archive or production model is modified for this compatibility.
    """
    if path != LEGACY_STATUS_TEST:
        return data, receipt
    originals = verified_raw(repo)
    need(b'"bangumi" -> listOf("\xe7\x95\xaa\xe5\x89\xa7")' in originals['ListModels.kt'],
         'Original native Bangumi label policy changed')
    need('assertTrue(card.statusLabels.containsAll(listOf("番剧", "更新至12话")))'.encode() in
         originals['RecommendationNativeCardTest.kt'], 'Original native label assertion changed')
    need(sha(data) == LEGACY_STATUS_EMITTED_SHA256, 'Legacy status test predecessor changed')
    before, after = LEGACY_STATUS_EXPECTATION.encode(), NATIVE_STATUS_EXPECTATION.encode()
    need(data.count(before) == 1, 'Legacy native label expectation is not unique')
    result = data.replace(before, after, 1)
    need(result.count(after) == 1 and result.replace(after, before, 1) == data,
         'Legacy native label expectation inverse changed')
    proof = dict(receipt, mode='original-test-imports-and-explicit-v043-native-kind-expectation',
        sha256=sha(result), previousProducerReceipt=dict(receipt),
        expectationCompatibility=dict(upstreamCommit=COMMIT, predecessorSha256=sha(data),
            before=LEGACY_STATUS_EXPECTATION, after=NATIVE_STATUS_EXPECTATION,
            onlyExpectedLabelsChanged=True, completePreviousProducerInverse=True,
            reason='043 native card test requires Bangumi kind label; retained older test still expects only the server label'))
    return result, proof


def replace(text, before, after, count, label, audits):
    need(text.count(before) == count, 'Home native-card anchor changed: ' + label)
    result = text.replace(before, after)
    need(result.count(after) == count and result.replace(after, before) == text,
         'Home native-card hook inverse changed: ' + label)
    audits.append(dict(id=label, occurrences=count, before=before, after=after))
    return result


def home_page_delta(repo, name, text, audits):
    sources = verified_raw(repo)
    if name == 'HomeVideoClickRequest.kt':
        # Existing sole Home producer keeps the complete enum, request and route-source helper.
        original = sources[name].decode('utf-8')
        need('val episodeId:' not in text and text.count('data class HomeVideoClickRequest(') == 1,
             'Existing Home request producer changed')
        audits.append(dict(id='home-native-request', mode='whole-v043', sha256=sha(original)))
        return original
    if name == 'DesktopOriginalHomeScreen.kt':
        before = '        { request ->\n            val activeGridState = if (currentCategory == HomeCategory.POPULAR) {'
        after = '''        homeClick@{ request ->
            if (request.episodeId > 0L ||
                (request.dynamicId.isNotBlank() && !request.bvid.startsWith("BV", ignoreCase = true))) {
                onVideoClick(request)
                return@homeClick
            }
            val activeGridState = if (currentCategory == HomeCategory.POPULAR) {'''
        need(after.split('            val activeGridState')[0].strip() in sources['HomeScreen.kt'].decode('utf-8'),
             'Original native navigation guard changed')
        text = replace(text, before, after, 1, 'home-native-bypass-av-transition', audits)
        for subject, count in [('video', 2), ('item', 1)]:
            pattern = re.compile(r'(HomeVideoClickRequest\(\n(?P<indent> +)bvid = ' + subject + r'\.bvid,\n)')
            need(len(list(pattern.finditer(text))) == count, 'Home screen click subjects changed')
            text = pattern.sub(lambda m: m[1] + m['indent'] + 'dynamicId = ' + subject + '.dynamicId,\n' +
                               m['indent'] + 'episodeId = ' + subject + '.episodeId,\n', text)
            audits.append(dict(id='home-native-screen-click-' + subject, occurrences=count))
    elif name == 'HomeCategoryPage.kt':
        before = '''        val isDynamicDetailCard = video.dynamicId.isNotBlank() &&
            !video.bvid.startsWith("BV", ignoreCase = true)'''
        after = '''        val isNativeNonVideoCard = video.contentType in setOf("bangumi", "picture") ||
            (video.dynamicId.isNotBlank() && !video.bvid.startsWith("BV", ignoreCase = true))'''
        need(after in sources[name].decode('utf-8'), 'Original Home native-card classification changed')
        text = replace(text, before, after, 1, 'home-native-category-kind', audits)
        text = replace(text, 'if (isDynamicDetailCard)', 'if (isNativeNonVideoCard)', 3,
                       'home-native-category-actions', audits)
        pattern = re.compile(r'(?P<indent> +)dynamicId = video.dynamicId,\n')
        need(len(list(pattern.finditer(text))) == 4, 'Home grid/hero click subjects changed')
        text = pattern.sub(lambda m: m[0] + m['indent'] + 'episodeId = video.episodeId,\n', text)
        audits.append(dict(id='home-native-grid-and-hero-episode', occurrences=4))
        # The original optional TodayWatch callback is also a mounted caller when
        # no outer override is supplied; retain native identity there as well.
        pattern = re.compile(r'(HomeVideoClickRequest\(\n(?P<indent> +)bvid = video\.bvid,\n)(?P=indent)cid =')
        need(len(list(pattern.finditer(text))) == 1, 'Home category default TodayWatch click changed')
        text = pattern.sub(lambda m: m[1] + m['indent'] + 'dynamicId = video.dynamicId,\n' +
                           m['indent'] + 'episodeId = video.episodeId,\n' + m['indent'] + 'cid =', text)
        audits.append(dict(id='home-native-default-today-watch-identity', occurrences=1))
        text = replace(text, 'showOnlineCount = showOnlineCount,',
                       'showOnlineCount = showOnlineCount && !isNativeNonVideoCard,', 2,
                       'home-native-no-av-online-count', audits)
    return text


def home_protocol_delta(repo, text, audits):
    source = verified_raw(repo)['VideoRepository.kt'].decode('utf-8')
    before = '?.filter { it.goto == "av" }  // 只保留视频类型'
    after = '?.filter { it.isSupportedRecommendation }'
    need(source.count(after) == 2, 'Original mobile native-card filter changed')
    text = replace(text, before, after, 2, 'home-native-mobile-and-merged-admission', audits)
    before = 'feedResp.data?.item?.map { it.toVideoItem() }'
    after = 'feedResp.data?.item?.filter { it.adInfo == null }?.map { it.toVideoItem() }'
    need(source.count(after) == 2, 'Original web ad filter changed')
    return replace(text, before, after, 2, 'home-native-web-and-merged-ad-filter', audits)

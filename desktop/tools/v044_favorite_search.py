"""Current original favorite-search identity on the existing navigation producers."""
from pathlib import Path
import hashlib
import json
from v025_source_paths import canonical_source
from v044_favorite_detail import inverse_proof

COMMIT = '070ab41608213cd981fd7e51df357295f9a537bf'
ARCHIVE = 'desktop/upstream-slices/v044-favorite-search'
MANIFEST_SHA256 = '3ddef87c3e56a4b343777371569cdb962f3095edac2b8a45a8cb3771bcd1d7c8'
NAV = 'app/src/main/java/com/android/purebilibili/navigation3/'
EXPECTED = {'BiliPaiNavKey.kt', 'BiliPaiNavKeyMappingPolicy.kt',
            'BiliPaiNavKeyMappingPolicyTest.kt', 'AppNavigation.kt'}
BASE_MAPPING_SHA256 = '6e8c956f101e679ad0a96786ce8c114845ddb3c3e27cc41551c261f5407a586d'
BASE_UI_SHA256 = '3f047707334e6df293bec45844fa4b22d265bec6d03a82509b7a487bd9abf4be'

def sha(raw):
    return hashlib.sha256(raw.encode('utf8') if isinstance(raw, str) else raw).hexdigest()

def need(condition, message):
    if not condition:
        raise ValueError(message)

def fixed(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing or linked favorite-search originals')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json'}, 'Favorite-search leaves changed')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Favorite-search manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.4', COMMIT, 'raw'), 'Favorite-search identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {r['filename'] for r in manifest['files']} == EXPECTED,
         'Favorite-search input inventory changed')
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous favorite-search input')
        raw = file.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Favorite-search bytes changed: '+file.name)
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'],
             'Favorite-search Git blob changed: '+file.name)
        sources[file.name] = raw.decode('utf8')
    return sources

def favorite_declaration(source):
    marker = '    @Serializable\n    data class FavoriteSearch('
    need(source.count(marker) == 1, 'FavoriteSearch declaration is ambiguous')
    first = source.index(marker)
    last = source.index('\n    }', first)+len('\n    }')
    body = source[first:last]
    need(body.count('{') == body.count('}') == 1 and 'override val routeBase: String = "favorite_search"' in body,
         'FavoriteSearch declaration boundary changed')
    return body

def between(source, first, last):
    need(source.count(first) == source.count(last) == 1, 'Favorite-search original branch boundary changed')
    a, b = source.index(first), source.index(last)
    need(a < b, 'Reordered favorite-search branch')
    return source[a:b]

def emit_mapping(repo, output):
    sources = fixed(repo)
    original = sources['BiliPaiNavKeyMappingPolicy.kt']
    before = canonical_source(repo, NAV+'BiliPaiNavKeyMappingPolicy.kt').read_text(encoding='utf8').replace('\r\n', '\n')
    need(sha(before) == BASE_MAPPING_SHA256, 'Canonical route-mapping predecessor changed')
    body = before
    selected = []
    for first, last in [
        ('        is BiliPaiNavKey.FavoriteSearch ->', '        is BiliPaiNavKey.LikedVideos ->'),
        ('        routeBase == "favorite_search" ->', '        routeBase == ScreenRoutes.LikedVideos.route ||'),
    ]:
        old, new = between(body, first, last), between(original, first, last)
        body = body.replace(old, new, 1)
        selected.append(dict(originalSHA256=sha(new), originalOffset=original.index(new), completeOriginalBranch=True))
    need('&subscribed=$subscribed' in body and 'subscribed = query["subscribed"].toBoolean(),' in body,
         'Favorite-search original round-trip flag missing')
    from v044_audio_routes import advance_mapping
    body, audio = advance_mapping(repo, body)
    target = Path(output)/'com/android/purebilibili/navigation3/BiliPaiNavKeyMappingPolicy.kt'
    archive = (Path(repo)/ARCHIVE).resolve()
    need(not target.resolve().is_relative_to(archive) and not archive.is_relative_to(target.resolve()),
         'Route output overlaps originals')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding='utf8', newline='\n')
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        output='com/android/purebilibili/navigation3/BiliPaiNavKeyMappingPolicy.kt',
        completeOriginalBranches=selected, originalAudioCollectionMapping=audio,
        canonicalRoutesOutsideFavoriteAndAudioUnchanged=True,
        retainedToCurrent=inverse_proof(before, body))
    (Path(output)/'v044-favorite-search-mapping-proof.json').write_text(
        json.dumps(proof, ensure_ascii=False, indent=2)+'\n', encoding='utf8', newline='\n')
    return proof

def advance_common_list(repo, before, original):
    """Invoked at the end of the sole current subscription UI adapter."""
    fixed(repo)
    need(sha(before) == BASE_UI_SHA256, 'Favorite-search actual UI predecessor changed')
    body = before
    changes = [
        ('onOpenSearchDestination: ((String) -> Unit)? = null,',
         'onOpenSearchDestination: ((String, Boolean) -> Unit)? = null,'),
        ('AppIconButton(onClick = { openSearch(searchQuery) }) {',
         'AppIconButton(onClick = { openSearch(searchQuery, isSubscribedBrowse) }) {'),
        ('                            isFavoriteDetailBatchMode -> "已选: ${selectedFavoriteDetailKeys.size}"\n',
         '                            isFavoriteDetailBatchMode -> "已选: ${selectedFavoriteDetailKeys.size}"\n'
         '                            isSearchDestination && isSubscribedBrowse -> "搜索订阅"\n'),
        ('                    if (favoriteViewModel != null) {\n                        val favoriteSectionOptions = remember {',
         '                    if (favoriteViewModel != null && !(isSearchDestination && isSubscribedBrowse)) {\n'
         '                        val favoriteSectionOptions = remember {'),
        ('                        favoriteViewModel != null &&\n                        isSearchDestination &&\n'
         '                        favoriteSection == FavoriteSection.VIDEO\n',
         '                        favoriteViewModel != null &&\n                        isSearchDestination &&\n'
         '                        !isSubscribedBrowse &&\n                        favoriteSection == FavoriteSection.VIDEO\n'),
    ]
    selected = []
    for old, new in changes:
        need(body.count(old) == 1 and original.count(new) == 1, 'Favorite-search actual UI callback seam changed')
        body = body.replace(old, new, 1)
        selected.append(dict(originalOffset=original.index(new), originalSHA256=sha(new), originalText=new))
    first = '    LaunchedEffect(\n        searchQuery,\n'
    last = '    // [New] 动态顶栏高度测量'
    old_effect = between(body, first, last)
    original_effect = between(original, first, last)
    identity_key = '        accountIdentity,\n'
    need(original_effect.count(identity_key) == 1 and original_effect.count('LaunchedEffect(') == 1,
         'Original search effect account boundary changed')
    # The actual Root replaces the complete retained entry on credential epoch
    # changes; it supplies no Android TokenManager composition identity.
    effect = original_effect.replace(identity_key, '', 1)
    need('if (!isCurrentPage) return@LaunchedEffect' in effect and
         'if (isSearchDestination && favoriteViewModel != null && !isSubscribedBrowse)' in effect,
         'Original search effect active-page or subscription guard changed')
    body = body.replace(old_effect, effect, 1)
    # Keep the complete original desktop-suitable upstream caller as evidence;
    # this is not a second navigation implementation or a second account owner.
    caller = fixed(repo)['AppNavigation.kt']
    need('favoriteSearchKey?.subscribed == true' in caller and
         'BiliPaiNavKey.FavoriteSearch(query = query, subscribed = subscribed)' in caller,
         'Current original AppNavigation callback contract changed')
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        originalCallbackSegments=selected, activeBrowseModeCapturedAtClick=True,
        completeOriginalSearchEffect=True, rootCredentialEpochRetiresWholeEntry=True,
        originalSearchEffectSHA256=sha(original_effect),
        originalSearchEffectPlatformBinding=inverse_proof(original_effect, effect),
        retainedToCurrent=inverse_proof(before, body))

def emit_original_test(repo, output):
    source = fixed(repo)['BiliPaiNavKeyMappingPolicyTest.kt']
    marker = '    @Test\n    fun personalListSearchKeysRoundTripThroughLegacyRoutes() {'
    need(source.count(marker) == 1, 'Original favorite-search regression changed')
    first = source.index(marker)
    last = source.index('\n    }', first)+len('\n    }')
    method = source[first:last]
    need('BiliPaiNavKey.FavoriteSearch(query = "教程 + 合集", subscribed = true)' in method,
         'Original subscribed route regression missing')
    target = Path(output)/'com/android/purebilibili/navigation3/V044FavoriteSearchOriginalPolicyTest.kt'
    need(not target.resolve().is_relative_to((Path(repo)/ARCHIVE).resolve()), 'Test output overlaps originals')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('package com.android.purebilibili.navigation3\n\nimport kotlin.test.*\n\n'
        'class V044FavoriteSearchOriginalPolicyTest {\n'+method+'\n}\n', encoding='utf8', newline='\n')
    from v044_audio_routes import emit_original_test as emit_audio_test
    audio_test = emit_audio_test(repo, output)
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                originalMethodSHA256=sha(method), wholeOriginalMethod=True, casesSelected=1,
                originalAudioCollectionRegression=audio_test, testsExecuted=False)

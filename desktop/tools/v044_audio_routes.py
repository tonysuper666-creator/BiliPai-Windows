"""Complete original collection audio routing on the sole existing Nav3 codec."""
from pathlib import Path
import json
from v044_favorite_detail import inverse_proof

BEFORE_MAPPING_SHA256 = '13658bee2f50134d243121967d525b4dfd57e451eedc3c03c62448182cc05cbe'

def advance_mapping(repo, before):
    # Function-local import keeps the shared UI adapter independent of this
    # navigation-only producer; both original branches share the fixed archive.
    from v044_favorite_search import fixed, between, need, sha, COMMIT, MANIFEST_SHA256
    original = fixed(repo)['BiliPaiNavKeyMappingPolicy.kt']
    need(sha(before) == BEFORE_MAPPING_SHA256, 'Collection audio mapping predecessor changed')
    body = before
    selections = []
    for first, last in [
        ('        is BiliPaiNavKey.AudioMode ->', '        is BiliPaiNavKey.SeasonSeriesDetail ->'),
        ('        routeBase == "audio_mode" ->', '        segments.firstOrNull() == "season_series_detail" &&'),
    ]:
        old, new = between(body, first, last), between(original, first, last)
        need(old != new and body.count(old) == 1, 'Collection audio route has already advanced')
        body = body.replace(old, new, 1)
        selections.append(dict(originalOffset=original.index(new), originalSHA256=sha(new),
                               originalText=new, completeOriginalBranch=True))
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalAudioBranches=selections, existingVideoAudioFallbackUnchanged=True,
        subscribedFavoriteSearchMappingUnchanged=True, retainedToCurrent=inverse_proof(before, body))

def emit_original_test(repo, output):
    from v044_favorite_search import fixed, need, sha, COMMIT, MANIFEST_SHA256, ARCHIVE
    original = fixed(repo)['BiliPaiNavKeyMappingPolicyTest.kt']
    marker = '    @Test\n    fun audioCollectionKeysPreserveSourceAndTitleAcrossRouteRestoration() {'
    need(original.count(marker) == 1, 'Original collection audio route regression changed')
    first = original.index(marker)
    last = original.index('\n    }', first)+len('\n    }')
    method = original[first:last]
    need('collectionMediaId = 42L' in method and 'collectionSeasonId = 81L' in method,
         'Original media/season route regression missing')
    target = Path(output)/'com/android/purebilibili/navigation3/V044AudioCollectionRouteOriginalTest.kt'
    need(not target.resolve().is_relative_to((Path(repo)/ARCHIVE).resolve()), 'Audio test output overlaps originals')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('package com.android.purebilibili.navigation3\n\nimport kotlin.test.*\n\n'
        'class V044AudioCollectionRouteOriginalTest {\n'+method+'\n}\n', encoding='utf8', newline='\n')
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                wholeOriginalMethod=True, originalMethodSHA256=sha(method), casesSelected=1, testsExecuted=False)

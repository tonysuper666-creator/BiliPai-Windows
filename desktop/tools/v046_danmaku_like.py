"""Complete original v0.4.6 danmaku-like method on the existing sole API owner."""
from pathlib import Path
from v046_favorite_editor import fixed, parser, sha, restore, COMMIT, ARCHIVE, MANIFEST_SHA256
from v044_favorite_detail import inverse_proof
from v044_favorite_subscription import need

BEFORE_METHOD_SHA256 = 'c671dfc6f5f7afbf6be603d7b72f4d1d15242ddfd4a3c01076679104cb1d6334'
AFTER_METHOD_SHA256 = '74896d7b422923eedf5796ba616f51b8f0a398a2a131bda03085889981b5de90'


def validate_outputs(repo, *outputs):
    fixed(repo)  # Reuse the already sealed raw ApiClient, manifest and license.
    archive = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not target.is_relative_to(archive) and not archive.is_relative_to(target),
             'Output overlaps original danmaku-like inputs')


def method_span(source, syntax):
    tokens = syntax.kotlin_tokens(source)
    matches = [i for i, token in enumerate(tokens[:-1])
               if token[0] == 'fun' and tokens[i+1][0] == 'likeDanmaku']
    need(len(matches) == 1, 'Ambiguous original likeDanmaku declaration')
    at = matches[0]
    first = source.rfind('    @retrofit2.http.FormUrlEncoded', 0, tokens[at][1])
    need(first >= 0, 'Original likeDanmaku annotation missing')
    opening = next(i for i in range(at, len(tokens)) if tokens[i][0] == '(')
    depth = 0
    for token in tokens[opening:]:
        depth += (token[0] == '(')-(token[0] == ')')
        if depth == 0:
            last = source.index('\n', token[2])
            need(source[token[2]:last].strip() == ': DanmakuActionResponse',
                 'Original likeDanmaku response changed')
            need(source[first:last].count('suspend fun ') == 1,
                 'Original likeDanmaku annotation boundary changed')
            return first, last
    raise ValueError('Unbounded original likeDanmaku arguments')


def original_method(repo):
    sources, _ = fixed(repo)
    source = sources['ApiClient.kt']
    first, last = method_span(source, parser(repo))
    selected = source[first:last]
    need(sha(selected) == AFTER_METHOD_SHA256, 'Original v046 likeDanmaku method changed')
    return selected


def advance_api(repo, before):
    syntax = parser(repo)
    first, last = method_span(before, syntax)
    prior = before[first:last]
    need(sha(prior) == BEFORE_METHOD_SHA256, 'Danmaku-like API predecessor changed')
    selected = original_method(repo)
    body = before[:first]+selected+before[last:]
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalMethod=True, originalMethodSHA256=AFTER_METHOD_SHA256,
        beforeMethodSHA256=BEFORE_METHOD_SHA256, soleApiOwner='BilibiliApi',
        existingRootRequestCsrfAndResultHandlingUnchanged=True,
        retainedToCurrent=inverse_proof(before, body))

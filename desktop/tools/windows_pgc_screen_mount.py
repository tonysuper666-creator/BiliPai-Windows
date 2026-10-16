"""Retain the original PGC Screen's one attach/load effect across Windows remounts.

All original effect statements stay intact. Only the retained Entry decides if
this is its first Screen load; every other Screen effect and every user/VM load
path is unchanged. Append after the existing complete pinned UI producer stages.
"""
from pathlib import Path
import hashlib
import json

TARGET = 'com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerScreen.kt'
INPUT_SHA256_LF = '07dddfa996e7e5f4d5de1e03c08011df7e4daa8714bd4ff842c3043699dfeb0a'
OUTPUT_SHA256_LF = 'f7a11790bb9dbb96e40f158eb99f16fc698b85b6b330a142403431ce6b22cad2'
HEADER = '    LaunchedEffect(exoPlayer, seasonId, epId, resumePositionMs, isCourse, preferredAid) {\n'
NEXT = '    LaunchedEffect(viewModel, context) {\n'


def sha(text): return hashlib.sha256(text.encode('utf-8')).hexdigest()


def adapt(path, body):
    if path != TARGET: return body, None
    if sha(body) != INPUT_SHA256_LF: raise ValueError('PGC Screen mount stage input changed; inspect the actual producer chain')
    if body.count(HEADER) != 1 or body.count(NEXT) != 1:
        raise ValueError('PGC original initial Screen effect is ambiguous')
    start, end = body.index(HEADER), body.index(NEXT)
    original = body[start:end]
    if not original.endswith('    }\n\n'):
        raise ValueError('PGC original initial Screen effect terminator changed')
    inside = original[len(HEADER):-len('    }\n\n')]
    if inside.count('viewModel.attachPlayer(exoPlayer)') != 1 or inside.count('viewModel.loadBangumiPlay(') != 1:
        raise ValueError('PGC original initial attach/load body changed')
    replacement = ('    LaunchedEffect(platform, viewModel, exoPlayer, seasonId, epId, resumePositionMs, isCourse, preferredAid) {\n'
        '        platform.runInitialScreenLoad(viewModel) {\n' +
        ''.join('    ' + line if line.strip() else line for line in inside.splitlines(keepends=True)) +
        '        }\n    }\n\n')
    result = body[:start] + replacement + body[end:]
    if result.replace(replacement, original, 1) != body:
        raise ValueError('PGC Screen mount adapter does not reverse to the original effect')
    if sha(result) != OUTPUT_SHA256_LF:
        raise ValueError('PGC Screen mount output differs from the reviewed complete source')
    return result, dict(schema=1, originalInputSHA256LF=sha(body), generatedOutputSHA256LF=sha(result),
        originalEffectSHA256LF=sha(original), adaptedEffectSHA256LF=sha(replacement),
        inverseByteEqual=True, originalAttachLoadStatementsRetained=True,
        inferredEpisodeReplay=False, wholeScreenOriginalBodyRetained=True,
        kotlinCompiled=False, applicationExecuted=False)


def apply_and_record(path, body, output, edits=None):
    result, proof = adapt(path, body)
    if proof is not None:
        destination = Path(output) / 'windows-pgc-screen-mount-proof.json'
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8', newline='\n')
        if edits is not None:
            edits.append(dict(label='retained Windows Entry initial attach/load only',
                              inputSHA256LF=proof['originalInputSHA256LF'],
                              outputSHA256LF=proof['generatedOutputSHA256LF']))
    return result

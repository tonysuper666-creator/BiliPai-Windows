"""Finish the two original v029 reply renderers without replacing source pins.

The existing sole producers still own UI and shared rich-text policies. This
stage advances their reviewed earlier selections to the complete fixed bodies;
each edit is reversible and the fixed raw source remains independently pinned.
"""
import difflib
import hashlib

from v029_comment_time import BASE, COMMIT, fixed_sources

INPUTS = {
    BASE + 'ReplyComponents.kt': 'd7c45dbc33dc114f4855b566319d5935e270fed2afce32f57defb06953ca4c8e',
    BASE + 'SubReplyDetailComponents.kt': 'cd7ef1ed078befae4a93e89cdc0ff6716636a6b5fb51508d752072b765f4fd5d',
}


def digest(text):
    return hashlib.sha256(text.encode('utf8')).hexdigest()


def inverse(body, edits):
    for edit in reversed(edits):
        offset = edit['offset']
        if body[offset:offset + len(edit['after'])] != edit['after']:
            raise ValueError('Reply renderer inverse differs at an original edit')
        body = body[:offset] + edit['before'] + body[offset + len(edit['after']):]
    return body


def _advance(before, after, path, scope):
    old_lines = before.splitlines(keepends=True)
    new_lines = after.splitlines(keepends=True)
    operations = difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False).get_opcodes()
    edits = []
    body = before
    # Descending offsets leave every earlier original offset unchanged.
    for kind, a, b, x, y in reversed(operations):
        if kind == 'equal':
            continue
        offset = len(''.join(old_lines[:a]))
        old = ''.join(old_lines[a:b])
        new = ''.join(new_lines[x:y])
        if body[offset:offset + len(old)] != old:
            raise ValueError('Reply renderer original edit does not match')
        edits.append(dict(offset=offset, before=old, after=new))
        body = body[:offset] + new + body[offset + len(old):]
    if body != after or inverse(body, edits) != before:
        raise ValueError('Reply renderer does not restore its complete prior source')
    return body, dict(fixedUpstreamCommit=COMMIT, originalPath=path, scope=scope,
                      inputSha256LF=digest(before), outputSha256LF=digest(body),
                      completeInverse=True, edits=edits)


def renderer(repo, path, selected):
    if path not in INPUTS or digest(selected) != INPUTS[path]:
        raise ValueError('Unknown earlier reply renderer selection')
    latest = fixed_sources(repo)[path]
    return _advance(selected, latest, path, 'Complete fixed v029 renderer before existing platform seams')


def rich_link_policy(repo, selected):
    # The editor remains the sole policy producer. Its other selections,
    # including the separately-owned charged resolver, are left intact.
    path = BASE + 'ReplyComponents.kt'
    if digest(selected) != '6b8a60c3a50d1b50a5fe467ab9469ada9bb405915791c7a8b66f4e2353b08d49':
        raise ValueError('Unknown earlier rich-comment policy selection')
    latest = fixed_sources(repo)[path]
    begin = 'internal fun resolveReplyContentUrlNavigationUrl('
    end = '\nprivate fun isReplyDynamicNavigationUrl('
    def declaration(text):
        if text.count(begin) != 1 or text.count(end) != 1:
            raise ValueError('Ambiguous original reply navigation policy')
        return text[text.index(begin):text.index(end)]
    old = declaration(selected)
    new = declaration(latest)
    return _advance(selected, selected.replace(old, new, 1), path,
                    'Complete fixed v029 rich-link navigation function in the sole policy producer')

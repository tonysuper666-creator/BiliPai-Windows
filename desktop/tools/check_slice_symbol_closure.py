#!/usr/bin/env python3
"""Static symbol-closure gate for generated and desktop-owned Kotlin (no compiler, no JVM).

Compilation is deliberately deferred while upstream slices are authored, so dependency cascades only show up
late. This tool finds the cheap-to-detect ones early. It never writes into the checkout. It mirrors the
repository into a throw-away directory, runs every source-producing generator there exactly the way Gradle
runs it (same tool, same arguments, desktop/build as the output root), reads the generator outputs, the
desktop-owned Kotlin and the carried upstream slice archives, and reports the failure classes that the
Source260 cloud compile (head aad198489, 24 errors) showed, plus their siblings:

  duplicate-declaration   two top-level declarations with the same package, kind, name and signature in one
                          source set (Source260: the two BottomInputBar helpers, 8 errors)
  duplicate-file-class    two files with the same package and base name that both create a JVM file facade
  unresolved-import       an import of com.android.purebilibili.* or com.bilipai.desktop.* that no source
                          file declares
  unresolved-android-import
                          an android.* import without a desktop-owned shim (compiled sources only)
  unresolved-qualified    a fully qualified reference to such a symbol with no definer
                          (Source260: com.android.purebilibili.core.ui.components.userVipPink(), 4 errors)
  missing-copied-import   a name a copied declaration uses that the ORIGINAL upstream file imported but the
                          retained or generated file does not (Source260: AppContentColors, mutableIntStateOf)
  unresolved-name         a bare name inside a generated declaration that is not declared, imported, in the
                          same package or ambient (Source260: pinnedReplyIds)
  unresolved-member       a member read on a project type that has no such member or extension
                          (Source260: AppBottomSheetMotion.reducedMotion, 2 errors)
  producer-failed         a producer exited non-zero, so its output is missing from the universe
  producer-output-missing a Kotlin source directory registered in build.gradle.kts does not exist after the producers
                          ran and no producer this run skipped owns it (a producer that silently stops emitting a
                          leaf directory would otherwise pass)

Scopes. "compiled" is what Gradle would hand to compileKotlin/compileTestKotlin (desktop/src, the direct
canonical v0.2.5 files minus the Sync exclusions, every srcDir fed by a producer). "carried" is the verbatim
upstream slice archives under desktop/upstream-slices and any --candidate-path file read from the upstream
git objects: they are checked for imports and qualified references only, as a preview of what a slice that
emits them whole will need.

Known gaps are explicit. desktop/tools/symbol-closure-expected-gaps.json lists them with a reason and the
roadmap slice that closes them (closedByEvidence says whether the roadmap names the symbol or the gap's author only
proposed the slice). A gap that is listed but no longer observed is STALE and fails the gate, so every slice both
extends and prunes the list. A partial run (--no-carried, --checks, --names-scope none, --mirror,
--only-producers, --skip-producers) judges staleness only for the gaps its enabled checks and scopes can observe:
with --only-producers or --skip-producers no compiled-scope gap is judged, because the sources of every producer
left out are missing from the scan, and a retired seed whose definer is missing is reported UNJUDGED, not LOST.
"latent" entries are symbols that planned slices need and that must not have a definer yet; one that gains a
definer is stale too. "retiredSeeds" are roadmap seeds a generated shim already defines; the gate fails if such a
symbol loses its definer in a complete run.

Exit codes: 0 pass, 1 findings (an unexpected finding, a stale gap, a defined latent symbol, a lost retired seed),
2 the gate could not judge (bad option, an unknown --checks or producer name, a missing --mirror, an unreadable file,
a producer that timed out, git unavailable). A traceback is never an exit code the caller could mistake for 1.

Usage (from the repository root, one process at a time; about 2.5 minutes and under 200 MB):
  python desktop/tools/check_slice_symbol_closure.py                        # the gate; exit 0 pass, 1 findings, 2 tool error
  python desktop/tools/check_slice_symbol_closure.py --revision aad198489 --expected-gaps none --no-carried
                                                                              # calibrate on a historical head (git archive)
  python desktop/tools/check_slice_symbol_closure.py --candidate-path app/src/main/java/.../VideoCard.kt
                                                                              # what does a planned v0.4.6 file need?
  python desktop/tools/check_slice_symbol_closure.py --keep-scratch --scratch C:/bp-sc   # keep the path short on Windows
  python desktop/tools/check_slice_symbol_closure.py --mirror D:/bp-sc/r      # re-scan a kept mirror in 30 seconds

HEURISTIC LIMITS (printed with every report; see HEURISTIC_LIMITS):
  * A lexical scan, not a compiler: no overloads, typealiases, generics, library inheritance, implicit
    receivers or visibility; a declaration is found by simple name inside its package.
  * Only com.android.purebilibili.*, com.bilipai.desktop.* and android.* are resolved by name; AndroidX and
    third-party imports are never checked; a star import is checked for its package and, for a known project
    package, for the names it supplies (library and unknown-package stars stay lenient).
  * unresolved-name and unresolved-member run on generated sources only and learn their ambient names from the
    vendored v0.2.5 corpus (any name) and the carried upstream archives (lowercase names only, and never a name a
    scanned file imports from a project package); a lowercase name no scanned file imports is never reported.
  * Producers that need an unavailable toolchain are not run (JS worker JDK, MSVC diagnostic DLL).
  * A pass is evidence of nothing but the absence of these classes. It is not a compile.
"""
from __future__ import annotations

import argparse
import bisect
import fnmatch
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
from collections import Counter, defaultdict
from pathlib import Path

TARGET_COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
PROJECT_PREFIXES = ('com.android.purebilibili', 'com.bilipai.desktop')
SDK_PREFIX = 'android.'
EXPECTED_GAPS_FILE = 'desktop/tools/symbol-closure-expected-gaps.json'

# --------------------------------------------------------------------------------------
# Kotlin lexical layer
# --------------------------------------------------------------------------------------

_NOT_NL = re.compile(r'[^\n]')
_CODE_SPECIAL = re.compile(r"`[^`\n]*`|//|/\*|\"\"\"|\"|'")
_TMPL_SPECIAL = re.compile(r"`[^`\n]*`|//|/\*|\"\"\"|\"|'|[{}]")
_CHAR_LITERAL = re.compile(r"'(?:\\u[0-9A-Fa-f]{4}|\\.|[^\\'\n])'")
_STR_PLAIN = re.compile(r'\\.|"|\$\{|\$[A-Za-z_]\w*|\n')
_STR_RAW = re.compile(r'"{3,}|\$\{|\$[A-Za-z_]\w*')
_TOKEN = re.compile(r"`[^`\n]*`|[^\W\d]\w*|\d\w*(?:\.\d\w*)?|->|::|\?\.|\?:|!!|[^\s\w]|\n")
_IDENT = re.compile(r'[^\W\d]\w*$|`[^`\n]*`$')


def _blank(text):
    return _NOT_NL.sub(' ', text) if text else text


def _scan_string(text, k, raw, out):
    """Append the masked form of the string literal starting at text[k]; return the end."""
    quote = 3 if raw else 1
    out.append(' ' * quote)
    pattern = _STR_RAW if raw else _STR_PLAIN
    n = len(text)
    i = k + quote
    while True:
        m = pattern.search(text, i)
        if m is None:
            out.append(_blank(text[i:]))
            return n
        j = m.start()
        token = m.group()
        out.append(_blank(text[i:j]))
        if token == '${':
            out.append('  ')
            end = _scan_code(text, m.end(), True, out)
            if end >= n:
                return n
            out.append(' ')
            i = end + 1
        elif token[0] == '$':
            out.append(' ' + token[1:])
            i = m.end()
        elif token == '\n':
            return j  # unterminated plain string: stop before the newline
        elif token[0] == '"':
            out.append(' ' * len(token))
            return m.end()
        else:  # a backslash escape in a plain string
            out.append(' ' * len(token))
            i = m.end()


def _scan_code(text, i, in_template, out):
    """Append masked code from text[i:]. In template mode stop at the closing brace."""
    special = _TMPL_SPECIAL if in_template else _CODE_SPECIAL
    n = len(text)
    depth = 0
    last = i
    while True:
        m = special.search(text, i)
        if m is None:
            out.append(text[last:])
            return n
        k = m.start()
        token = m.group()
        first = token[0]
        if first == '`':
            i = m.end()
            continue
        if first == '{':
            depth += 1
            i = k + 1
            continue
        if first == '}':
            if depth == 0:
                out.append(text[last:k])
                return k
            depth -= 1
            i = k + 1
            continue
        out.append(text[last:k])
        if token == '//':
            e = text.find('\n', k)
            e = n if e < 0 else e
            out.append(' ' * (e - k))
            i = last = e
        elif token == '/*':
            level, j = 1, k + 2
            while level and j < n:
                a = text.find('/*', j)
                b = text.find('*/', j)
                if b < 0:
                    j = n
                    break
                if 0 <= a < b:
                    level += 1
                    j = a + 2
                else:
                    level -= 1
                    j = b + 2
            out.append(_blank(text[k:j]))
            i = last = j
        elif first == "'":
            cm = _CHAR_LITERAL.match(text, k)
            if cm:
                out.append(' ' * (cm.end() - k))
                i = last = cm.end()
            else:
                out.append("'")
                i = last = k + 1
        else:
            i = last = _scan_string(text, k, token == '"""', out)


def mask_kotlin(text):
    """Blank comments, string and char literal contents (template code is kept); same length."""
    out = []
    _scan_code(text, 0, False, out)
    return ''.join(out)


def tokenize(masked):
    """Tokens without newlines, plus the 1-based source line of every token."""
    toks, lines = [], []
    line = 1
    for m in _TOKEN.finditer(masked):
        token = m.group()
        if token == '\n':
            line += 1
            continue
        toks.append(token)
        lines.append(line)
    return toks, lines


HARD_KEYWORDS = frozenset(
    'as break class continue do else false for fun if in interface is null object package return super '
    'this throw true try typealias typeof val var when while it'.split())
# Soft keywords are legal names (a method called get) but are never a use of a project symbol.
SOFT_KEYWORDS = frozenset('by get set init where constructor field import'.split())
MODIFIERS = frozenset(
    'public private internal protected open abstract final sealed data enum annotation inner inline value '
    'const lateinit override suspend operator infix tailrec external expect actual vararg noinline '
    'crossinline reified'.split())
DECL_KEYWORDS = frozenset(['class', 'interface', 'object', 'fun', 'val', 'var', 'typealias'])
ANY_MEMBERS = frozenset(['toString', 'hashCode', 'equals'])
DATA_MEMBERS = frozenset(['copy'])
ENUM_MEMBERS = frozenset(['values', 'valueOf', 'entries', 'name', 'ordinal', 'compareTo'])


def is_ident(token):
    return bool(token) and _IDENT.match(token) is not None and token not in HARD_KEYWORDS


class Decl:
    """One top-level declaration of a Kotlin file."""
    __slots__ = ('kind', 'name', 'receiver', 'sig', 'ret', 'visibility', 'modifiers', 'line', 'start', 'end',
                 'header', 'members', 'member_ext', 'supers', 'ctor_params', 'is_enum', 'is_data')

    def __init__(self, kind, name, line, header):
        self.kind = kind
        self.name = name
        self.receiver = ''
        self.sig = ''
        self.ret = ''
        self.visibility = 'public'
        self.modifiers = frozenset()
        self.line = line
        self.start = header
        self.end = header
        self.header = header
        self.members = frozenset()
        self.member_ext = frozenset()
        self.supers = ()
        self.ctor_params = 0
        self.is_enum = False
        self.is_data = False

    @property
    def classifier(self):
        return self.kind in ('class', 'interface', 'object', 'typealias')

    @property
    def callable(self):
        return self.kind in ('fun', 'val')

    def key(self):
        if self.classifier:
            return ('type', self.name)
        return (self.kind, self.receiver, self.name, self.sig)

    def label(self):
        if self.kind == 'fun':
            return '%s%s(%s)' % ((self.receiver + '.') if self.receiver else '', self.name, self.sig)
        if self.kind == 'val':
            return '%s%s' % ((self.receiver + '.') if self.receiver else '', self.name)
        return self.name


class Import:
    __slots__ = ('path', 'alias', 'star', 'line')

    def __init__(self, path, alias, star, line):
        self.path = path
        self.alias = alias
        self.star = star
        self.line = line

    @property
    def simple(self):
        return self.alias or self.path.rsplit('.', 1)[-1]


_PACKAGE = re.compile(r'(?m)^[ \t]*package[ \t]+([\w.`]+)')
_IMPORT = re.compile(r'(?m)^[ \t]*import[ \t]+([\w.`]+?)(\.\*)?(?:[ \t]+as[ \t]+(\w+))?[ \t]*;?[ \t]*$')
_JVM_NAME = re.compile(r'@file:JvmName\(\s*"([^"]+)"\s*\)')


def _match(toks, i, open_, close):
    depth = 0
    n = len(toks)
    while i < n:
        t = toks[i]
        if t == open_:
            depth += 1
        elif t == close:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n - 1


def _skip_angle(toks, i):
    """toks[i] == '<': return the index after the matching '>' (stops at structural tokens)."""
    depth = 0
    n = len(toks)
    while i < n:
        t = toks[i]
        if t == '<':
            depth += 1
        elif t == '>':
            depth -= 1
            if depth == 0:
                return i + 1
        elif t in ('{', '}', ';', '=', '(') and depth <= 0:
            return i
        i += 1
    return n


def _norm(tokens):
    out = []
    previous = ''
    for t in tokens:
        if out and (t[0].isalnum() or t[0] in '_`') and (previous[-1:].isalnum() or previous[-1:] in '_`'):
            out.append(' ')
        out.append(t)
        previous = t
    return ''.join(out)


def _split_params(toks, a, b):
    """Parameter types of the parenthesis group toks[a+1:b]; also the parameter names."""
    types, names = [], []
    mode = 'head'
    item_head, item_type = [], []
    depth = angle = 0

    def flush():
        if item_type or item_head:
            types.append(_norm(item_type))
            declared = [t for t in item_head if is_ident(t) and t not in MODIFIERS]
            if declared:
                names.append(declared[-1])

    for t in toks[a + 1:b]:
        if t in ('(', '[', '{'):
            depth += 1
        elif t in (')', ']', '}'):
            depth -= 1
        if depth == 0 and t == ',' and angle == 0:
            flush()
            item_head, item_type, mode, angle = [], [], 'head', 0
            continue
        if mode == 'head':
            if depth == 0 and t == ':':
                mode = 'type'
            else:
                item_head.append(t)
        elif mode == 'type':
            if depth == 0 and angle == 0 and t == '=':
                mode = 'default'
                continue
            if t == '<':
                angle += 1
            elif t == '>':
                angle = max(0, angle - 1)
            item_type.append(t)
    flush()
    return types, names


def _is_decl_start(toks, lines, i, n):
    t = toks[i]
    prev = toks[i - 1] if i else ''
    nxt = toks[i + 1] if i + 1 < n else ''
    if t == 'class':
        if prev == '::' or not is_ident(nxt):
            return False
    elif t == 'interface':
        if not is_ident(nxt):
            return False
    elif t == 'object':
        if not is_ident(nxt):
            return False
    elif t == 'fun':
        if nxt == '(' or nxt == 'interface':
            return False
    elif t in ('val', 'var'):
        if not (is_ident(nxt) or nxt == '<' or nxt == '('):
            return False
    elif t == 'typealias':
        if not is_ident(nxt):
            return False
    if i == 0 or lines[i] != lines[i - 1]:
        return True
    if prev in MODIFIERS or prev in (';', '}'):
        return True
    if prev == 'fun' and t == 'interface':
        return True
    if prev == ')':
        return True
    if i >= 2 and toks[i - 2] == '@' and is_ident(prev):
        return True
    return False


def _preamble_start(toks, i):
    """Index of the first modifier/annotation token belonging to the declaration keyword at i."""
    k = i
    while True:
        if k > 0 and toks[k - 1] in MODIFIERS:
            k -= 1
            continue
        if k > 0 and toks[k - 1] == ')':
            depth = 0
            j = k - 1
            while j >= 0:
                if toks[j] == ')':
                    depth += 1
                elif toks[j] == '(':
                    depth -= 1
                    if depth == 0:
                        break
                j -= 1
            if j >= 2 and is_ident(toks[j - 1]) and toks[j - 2] == '@':
                k = j - 2
                continue
            break
        if k >= 2 and is_ident(toks[k - 1]) and toks[k - 2] == '@':
            k -= 2
            continue
        if k >= 4 and toks[k - 2] == ':' and toks[k - 4] == '@':
            k -= 4
            continue
        break
    return k


def _parse_fun(toks, i, d):
    n = len(toks)
    j = i + 1
    if j < n and toks[j] == '<':
        j = _skip_angle(toks, j)
    k = j
    while k < n and toks[k] != '(' and toks[k] not in ('{', '}', '=', ';'):
        k += 1
    if k >= n or toks[k] != '(' or k == j:
        return False
    head = toks[j:k]
    d.name = head[-1]
    if len(head) >= 3 and head[-2] in ('.', '?.'):
        d.receiver = _norm(head[:-2])
    close = _match(toks, k, '(', ')')
    types, _ = _split_params(toks, k, close)
    d.sig = ','.join(types)
    after = close + 1
    if after < n and toks[after] == ':':
        r = after + 1
        angle = 0
        collected = []
        while r < n:
            t = toks[r]
            if t == '<':
                angle += 1
            elif t == '>':
                angle = max(0, angle - 1)
            if angle == 0 and t in ('{', '=', ';', 'where') or (angle == 0 and t == '}'):
                break
            collected.append(t)
            r += 1
            if len(collected) > 80:
                break
        d.ret = _norm(collected)
    return True


def _parse_property(toks, i, d):
    n = len(toks)
    j = i + 1
    if j < n and toks[j] == '<':
        j = _skip_angle(toks, j)
    if j < n and toks[j] == '(':
        return False
    k = j
    while k < n and toks[k] not in (':', '=', ';', '{', '}', 'by', 'get', 'set'):
        if toks[k] == '(':
            k = _match(toks, k, '(', ')')
        k += 1
        if k - j > 60:
            break
    head = toks[j:k]
    if not head or not is_ident(head[-1]):
        return False
    d.name = head[-1]
    if len(head) >= 3 and head[-2] in ('.', '?.'):
        d.receiver = _norm(head[:-2])
    if k < n and toks[k] == ':':
        r = k + 1
        angle = 0
        collected = []
        while r < n:
            t = toks[r]
            if t == '<':
                angle += 1
            elif t == '>':
                angle = max(0, angle - 1)
            if angle == 0 and t in ('=', ';', '{', '}', 'by'):
                break
            if angle == 0 and t in ('get', 'set') and toks[r - 1] != '.' and r > k + 1:
                break
            collected.append(t)
            r += 1
            if len(collected) > 80:
                break
        d.ret = _norm(collected)
    return True


def _member_head(toks, q, limit, keyword):
    """(name, is_extension) of the member a `keyword` header starting at toks[q] declares; (None, False) if none."""
    if keyword == 'fun' and q < limit and toks[q] == '<':
        q = _skip_angle(toks, q)
    stops = ('(', ':', '=', '{', '}', ';') if keyword == 'fun' else ('(', ':', '=', '{', '}', ';', 'by', 'get', 'set')
    r = q
    while r < limit and toks[r] not in stops and r - q < 40:
        r += 1
    head = toks[q:r]
    if not head or not is_ident(head[-1]):
        return None, False
    return head[-1], len(head) >= 3 and head[-2] in ('.', '?.')


def _enum_entries(toks, j, close):
    """Entry names of an enum body whose opening brace is toks[j]."""
    entries = set()
    p = j + 1
    while p < close:
        while p < close and toks[p] == '@':
            p += 2
            if p < close and toks[p] == '(':
                p = _match(toks, p, '(', ')') + 1
        if p < close and is_ident(toks[p]):
            entries.add(toks[p])
            p += 1
        else:
            break
        if p < close and toks[p] == '(':
            p = _match(toks, p, '(', ')') + 1
        if p < close and toks[p] == '{':
            p = _match(toks, p, '{', '}') + 1
        if p < close and toks[p] == ',':
            p += 1
            continue
        break
    return entries


def _class_body(toks, i, limit):
    """Index of the opening brace of the class-like declaration whose keyword is toks[i], or None."""
    j = i + 2
    if j < limit and toks[j] == '<':
        j = _skip_angle(toks, j)
    k = j
    while k < limit and (toks[k] in MODIFIERS or toks[k] == 'constructor' or
                         (is_ident(toks[k]) and k > 0 and toks[k - 1] == '@')):
        k += 1
    if k < limit and toks[k] == '(':
        j = _match(toks, k, '(', ')') + 1
    depth = 0
    while j < limit:
        t = toks[j]
        if t in ('(', '['):
            depth += 1
        elif t in (')', ']'):
            depth -= 1
        elif t == '{' and depth == 0:
            return j
        elif t in (';', '}') and depth == 0:
            return None
        j += 1
    return None


def _scan_members(toks, j, close, members, member_ext):
    """Names declared directly in the body toks[j..close] (and in its companion object)."""
    depth = 0
    pending = False
    in_companion = False
    for p in range(j, close + 1):
        t = toks[p]
        if t == '{':
            depth += 1
            if pending and depth == 2:
                in_companion = True
                pending = False
        elif t == '}':
            if in_companion and depth == 2:
                in_companion = False
            depth -= 1
        elif (depth == 1 or (in_companion and depth == 2)) and t in DECL_KEYWORDS:
            if t == 'object' and depth == 1 and p > j and toks[p - 1] == 'companion':
                pending = True
            q = p + 1
            if t in ('fun', 'val', 'var'):
                name, extension = _member_head(toks, q, close, t)
                if name:
                    members.add(name)
                    if extension:
                        member_ext.add(name)
            elif q < close and is_ident(toks[q]):
                members.add(toks[q])


def _class_info(toks, d, end):
    """Fill members, supertypes and constructor parameter facts for a class-like declaration."""
    i = d.header
    n = min(end, len(toks))
    j = i + 1
    if j >= n:
        return
    j += 1  # the name
    if j < n and toks[j] == '<':
        j = _skip_angle(toks, j)
    members = set()
    member_ext = set()
    k = j
    while k < n and (toks[k] in MODIFIERS or toks[k] == 'constructor' or
                     (is_ident(toks[k]) and k > 0 and toks[k - 1] == '@')):
        k += 1
    if k < n and toks[k] == '(':
        close = _match(toks, k, '(', ')')
        types, _ = _split_params(toks, k, close)
        d.ctor_params = len(types)
        depth = 0
        item_start = k + 1
        for p in range(k + 1, close + 1):
            t = toks[p]
            if t in ('(', '[', '{'):
                depth += 1
            elif t in (')', ']', '}'):
                depth -= 1
            if (depth == 0 and t == ',') or p == close:
                chunk = toks[item_start:p]
                for q in range(len(chunk) - 1):
                    if chunk[q] in ('val', 'var') and is_ident(chunk[q + 1]):
                        members.add(chunk[q + 1])
                        break
                item_start = p + 1
        j = close + 1
    supers = []
    if j < n and toks[j] == ':':
        p = j + 1
        depth = 0
        angle = 0
        current = []
        while p < n:
            t = toks[p]
            if t in ('(', '['):
                depth += 1
            elif t in (')', ']'):
                depth -= 1
            elif t == '<':
                angle += 1
            elif t == '>':
                angle = max(0, angle - 1)
            if depth == 0 and angle == 0 and t in ('{', 'where', ';'):
                break
            if depth == 0 and angle == 0 and t == ',':
                supers.append(current)
                current = []
            elif depth == 0 and angle == 0:
                current.append(t)
            p += 1
        if current:
            supers.append(current)
        j = p
    names = []
    for item in supers:
        words = [t for t in item if is_ident(t) and t != 'by']
        if words:
            # keep the last simple name of the (possibly qualified) type path
            names.append(words[0] if '.' not in item else [t for t in item if is_ident(t)][-1])
    d.supers = tuple(names)
    if j < n and toks[j] == 'where':
        while j < n and toks[j] != '{':
            j += 1
    if j < n and toks[j] == '{':
        close = _match(toks, j, '{', '}')
        if d.is_enum:
            members.update(_enum_entries(toks, j, close))
        _scan_members(toks, j, close, members, member_ext)
    if d.is_data:
        members.update(DATA_MEMBERS)
        members.update('component%d' % (x + 1) for x in range(d.ctor_params))
    if d.is_enum:
        members.update(ENUM_MEMBERS)
    members.update(ANY_MEMBERS)
    d.members = frozenset(members)
    d.member_ext = frozenset(member_ext)


class KFile:
    """Summary of one Kotlin or Java source file. Tokens are kept only on request."""
    __slots__ = ('path', 'origin', 'set', 'package', 'imports', 'decls', 'jvm_name', 'toks', 'lines', 'language',
                 'size')

    def __init__(self, path, origin, source_set, language='kotlin'):
        self.path = path
        self.origin = origin
        self.set = source_set
        self.package = ''
        self.imports = []
        self.decls = []
        self.jvm_name = None
        self.toks = None
        self.lines = None
        self.language = language
        self.size = 0

    @property
    def facade(self):
        """The JVM file-facade class simple name, or None when the file has no top-level callable."""
        if self.language != 'kotlin' or not any(d.callable for d in self.decls):
            return None
        return self.jvm_name or (Path(self.path).stem + 'Kt')


def _line_of(masked_line_offsets, pos):
    return bisect.bisect_right(masked_line_offsets, pos) + 1


def parse_kotlin(text, path='', origin='generated', source_set='main', keep_tokens=False):
    if text.startswith('\ufeff'):
        text = text[1:]
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    info = KFile(path, origin, source_set)
    info.size = len(text)
    masked = mask_kotlin(text)
    pm = _PACKAGE.search(masked)
    if pm:
        info.package = pm.group(1).replace('`', '')
    jm = _JVM_NAME.search(text)
    if jm:
        info.jvm_name = jm.group(1)
    newlines = [m.start() for m in re.finditer('\n', masked)]
    for m in _IMPORT.finditer(masked):
        info.imports.append(Import(m.group(1).replace('`', ''), m.group(3), bool(m.group(2)),
                                   _line_of(newlines, m.start())))
    toks, lines = tokenize(masked)
    n = len(toks)
    decls = []
    db = dp = ds = 0
    for i, t in enumerate(toks):
        if t == '{':
            db += 1
        elif t == '}':
            db = max(0, db - 1)
        elif t == '(':
            dp += 1
        elif t == ')':
            dp = max(0, dp - 1)
        elif t == '[':
            ds += 1
        elif t == ']':
            ds = max(0, ds - 1)
        elif db == 0 and dp == 0 and ds == 0 and t in DECL_KEYWORDS and _is_decl_start(toks, lines, i, n):
            d = _make_decl(toks, lines, i, n)
            if d is not None:
                decls.append(d)
    starts = [_preamble_start(toks, d.header) for d in decls]
    for index, d in enumerate(decls):
        d.start = starts[index]
        d.end = starts[index + 1] if index + 1 < len(decls) else n
    for d in decls:
        if d.classifier and d.kind != 'typealias':
            _class_info(toks, d, d.end)
    info.decls = decls
    if keep_tokens:
        info.toks = toks
        info.lines = lines
    return info


def _make_decl(toks, lines, i, n):
    t = toks[i]
    if t == 'fun' and i + 1 < n and toks[i + 1] == 'interface':
        return None  # `fun interface X`: the declaration is made at the `interface` token
    d = Decl('fun' if t == 'fun' else 'val' if t in ('val', 'var') else t, '', lines[i], i)
    mods = set()
    k = i - 1
    if t == 'interface' and k >= 0 and toks[k] == 'fun':
        k -= 1
    while k >= 0 and toks[k] in MODIFIERS:
        mods.add(toks[k])
        k -= 1
    d.modifiers = frozenset(mods)
    for vis in ('private', 'internal', 'protected'):
        if vis in mods:
            d.visibility = vis
    d.is_enum = 'enum' in mods
    d.is_data = 'data' in mods
    if t == 'fun':
        if not _parse_fun(toks, i, d):
            return None
    elif t in ('val', 'var'):
        if not _parse_property(toks, i, d):
            return None
    else:
        if i + 1 >= n or not is_ident(toks[i + 1]):
            return None
        d.name = toks[i + 1]
    return d


# --------------------------------------------------------------------------------------
# Gradle wiring model (desktop/build.gradle.kts) and producer runner
# --------------------------------------------------------------------------------------

class ToolError(RuntimeError):
    """The gate itself could not be evaluated (a distinct failure from findings)."""


class Task:
    __slots__ = ('name', 'tool', 'args', 'deps', 'skip')

    def __init__(self, name, tool, args, deps, skip):
        self.name = name
        self.tool = tool
        self.args = args
        self.deps = deps
        self.skip = skip


class GradleModel:
    def __init__(self):
        self.variables = {}      # name -> relative build path
        self.tasks = {}          # name -> Task (Exec tasks only, file order)
        self.source_dirs = {'main': [], 'test': []}   # (kind, relative path)
        self.unresolved_source_dirs = []
        self.sync_excludes = []


_TASK_HEAD = re.compile(r'val (\w+) by tasks\.registering\((\w+)::class\)\s*\{')
_VARIABLE = re.compile(r'(?m)^val (\w+) = layout\.buildDirectory\.(?:dir|file)\("([^"]+)"\)\s*$')
_SOURCE_SET = re.compile(r'(?:kotlin\.)?sourceSets\.named\("(main|test)"\)\s*\{')
# Producers that need a toolchain the gate must not start (JDK download, MSVC/Windows SDK compile).
UNRUNNABLE_TASKS = {
    'fetchJsWorkerJdk': 'downloads a JDK archive (network)',
    'prepareJsWorker': 'resolves the GraalJS Maven graph and a JDK (network)',
    'prepareNativeDiagnosticShare': 'compiles a native DLL with MSVC',
}
# The build-relative directories those producers would fill. The js-worker command line is built from computed Gradle
# variables the model cannot follow, so the directories are named here; a test pins them against build.gradle.kts and
# against UNRUNNABLE_TASKS, so a new unrunnable producer cannot be added without saying which directory it owns.
UNRUNNABLE_OUTPUTS = {
    'fetchJsWorkerJdk': (),
    'prepareJsWorker': ('generated/js-worker',),
    'prepareNativeDiagnosticShare': ('generated/native-diagnostic-share',),
}


def _block_end(masked, open_index):
    depth = 0
    for i in range(open_index, len(masked)):
        c = masked[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
    raise ToolError('Unbalanced braces in the Gradle file')


def _paren_end(masked, open_index):
    depth = 0
    for i in range(open_index, len(masked)):
        c = masked[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i
    raise ToolError('Unbalanced parentheses in the Gradle file')


def _split_args(raw, masked):
    parts, depth, last = [], 0, 0
    for i, c in enumerate(masked):
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c == ',' and depth == 0:
            parts.append(raw[last:i].strip())
            last = i + 1
    tail = raw[last:].strip()
    if tail:
        parts.append(tail)
    return parts


def _eval_arg(raw, variables):
    a = ' '.join(raw.split())
    if a.startswith('System.getenv("PYTHON_EXECUTABLE")'):
        return ('python', '')
    m = re.fullmatch(r'"([^"$]*)"', a)
    if m:
        return ('literal', m.group(1))
    if a in ('repositoryRoot.absolutePath', 'rootProject.projectDir.parentFile.absolutePath'):
        return ('repo', '')
    if a == 'projectDir.absolutePath':
        return ('desktop', '')
    m = re.fullmatch(r'layout\.buildDirectory\.(?:dir|file)\("([^"]+)"\)\.get\(\)\.asFile\.absolutePath', a)
    if m:
        return ('build', m.group(1))
    m = re.fullmatch(r'(\w+)\.get\(\)\.asFile\.absolutePath', a)
    if m and m.group(1) in variables:
        return ('build', variables[m.group(1)])
    m = re.fullmatch(r'file\("([^"]+)"\)\.absolutePath', a)
    if m:
        return ('desktop', m.group(1))
    return ('unresolved', a)


def parse_gradle(text):
    """Model the producer wiring of desktop/build.gradle.kts without executing it."""
    text = text.replace('\r\n', '\n')
    masked = mask_kotlin(text)
    model = GradleModel()
    for m in _VARIABLE.finditer(text):
        model.variables[m.group(1)] = m.group(2)
    for m in _TASK_HEAD.finditer(text):
        name, kind = m.group(1), m.group(2)
        open_index = m.end() - 1
        end = _block_end(masked, open_index)
        raw_block = text[open_index:end + 1]
        masked_block = masked[open_index:end + 1]
        if kind == 'Sync' and name == 'prepareUpstreamSources':
            model.sync_excludes = re.findall(r'exclude\(canonicalOriginalIdentity\("([^"]+)"\)\)', raw_block)
            if len(model.sync_excludes) != masked_block.count('exclude('):
                raise ToolError('prepareUpstreamSources has exclusions this gate does not model')
        if kind != 'Exec':
            continue
        cl = masked_block.find('commandLine(')
        if cl < 0:
            continue
        a = cl + len('commandLine')
        b = _paren_end(masked_block, a)
        args = [_eval_arg(x, model.variables) for x in _split_args(raw_block[a + 1:b], masked_block[a + 1:b])]
        deps = []
        for dm in re.finditer(r'dependsOn\(', masked_block):
            e = _paren_end(masked_block, dm.end() - 1)
            deps.extend(x.strip() for x in raw_block[dm.end():e].replace('\n', ' ').split(',') if x.strip())
        tool = None
        for kind_, value in args:
            if kind_ == 'literal' and value.endswith('.py'):
                tool = value
                break
        skip = None
        if name in UNRUNNABLE_TASKS:
            skip = UNRUNNABLE_TASKS[name]
        elif name.startswith('verify'):
            skip = 'verifier: reads other producers output, produces no Kotlin'
        elif tool is None:
            skip = 'not a python tool'
        elif any(k == 'unresolved' for k, _ in args):
            skip = 'unresolved Gradle expression in the command line'
        model.tasks[name] = Task(name, tool, args, deps, skip)
    blocks = [(m.start(), _block_end(masked, m.end() - 1), m.group(1)) for m in _SOURCE_SET.finditer(text)]
    for m in re.finditer(r'(?<![\w.])kotlin\.srcDir\(', masked):
        e = _paren_end(masked, m.end() - 1)
        expr = ' '.join(text[m.end():e].split())
        owner = None
        for start, stop, which in blocks:
            if start < m.start() < stop:
                owner = which
        if owner is None:
            continue
        found = None
        mm = re.fullmatch(r'layout\.buildDirectory\.dir\("([^"]+)"\)', expr)
        if mm:
            found = ('build', mm.group(1))
        elif expr in model.variables:
            found = ('build', model.variables[expr])
        else:
            mm = re.fullmatch(r'(\w+)\.map \{ it\.dir\("([^"]+)"\) \}', expr)
            if mm and mm.group(1) in model.variables:
                found = ('build', model.variables[mm.group(1)] + '/' + mm.group(2))
            else:
                mm = re.fullmatch(r'(?:file\()?"([^"$]+)"\)?', expr)
                if mm:
                    found = ('project', mm.group(1))
        if found is None:
            model.unresolved_source_dirs.append((owner, expr))
        elif found not in model.source_dirs[owner]:
            model.source_dirs[owner].append(found)
    return model


def _under(child, parent):
    return child == parent or child.startswith(parent.rstrip('/') + '/')


def select_producers(model, only=None, skip=None):
    """(runnable tasks in dependency order, [(name, reason)] skipped producers).

    A producer is a python Exec task whose build-dir arguments overlap a Kotlin source directory.
    Verifiers are not producers. Producers that need an unavailable toolchain are reported as skipped.
    """
    if model.unresolved_source_dirs:
        raise ToolError('Kotlin source directories this gate cannot resolve: %s' %
                        ', '.join('%s %s' % pair for pair in model.unresolved_source_dirs))
    wanted = [rel for owner in ('main', 'test') for kind, rel in model.source_dirs[owner] if kind == 'build']
    runnable, skipped = {}, []
    for name, task in model.tasks.items():
        if (only is not None and name not in only) or name.startswith('verify'):
            continue
        if task.skip and task.skip.startswith('unresolved Gradle expression') and name not in UNRUNNABLE_TASKS:
            raise ToolError('Producer %s has a command line this gate cannot resolve' % name)
        outputs = [v for k, v in task.args if k == 'build']
        if not any(_under(o, w) or _under(w, o) for o in outputs for w in wanted) and name not in UNRUNNABLE_TASKS:
            continue
        if skip and name in skip:
            skipped.append((name, 'skipped by request'))
        elif task.skip:
            skipped.append((name, task.skip))
        else:
            runnable[name] = task
    order, seen = [], set()

    def visit(name):
        if name in seen or name not in runnable:
            return
        seen.add(name)
        for dep in runnable[name].deps:
            visit(dep)
        order.append(name)

    for name in runnable:
        visit(name)
    return [runnable[n] for n in order], sorted(skipped)


# Paths no producer or source set reads. Everything else is mirrored so that producers see the
# same bytes they would see in the real checkout, at the same repo-relative paths.
SCRATCH_EXCLUDES = ('.git', '.github', 'desktop/verification', 'desktop/build', 'desktop/.gradle', 'artwork', 'docs',
                    'plugins/samples', 'baselineprofile', 'examples', 'scripts', 'dolby-ffmpeg-decoder')


_LONG_PREFIX = '\\\\?\\'


def _long(path):
    """Windows long-path form (the producers and the archive tree exceed MAX_PATH-adjacent depths)."""
    path = os.path.abspath(str(path))
    if os.name == 'nt' and not path.startswith(_LONG_PREFIX):
        return _LONG_PREFIX + path
    return path


def _plain(path):
    path = str(path)
    return path[len(_LONG_PREFIX):] if path.startswith(_LONG_PREFIX) else path


def _remove_tree(path):
    """Remove a directory tree, read-only entries (git object files) included; a missing path is not an error."""
    def clear(function, target, *_):
        try:
            os.chmod(target, stat.S_IRWXU)
            function(target)
        except OSError:
            pass

    if sys.version_info >= (3, 12):
        shutil.rmtree(_long(path), onexc=clear)
    else:
        shutil.rmtree(_long(path), onerror=clear)


# The producers are separate Python processes that open their outputs by plain path, which Windows limits to 259
# characters. The longest repo-relative path any producer writes (all 141 measured on 2026-10-18) is
# desktop\build\generated\original-background-playback-policy\original-lf\app\src\test\java\...\
# PlaybackLifecycleCoordinatorTest.kt at 181 characters; the margin leaves room for a somewhat longer one.
WINDOWS_MAX_PATH = 259
LONGEST_PRODUCER_PATH = 181
PRODUCER_PATH_MARGIN = 8
MAX_SCRATCH_ROOT = WINDOWS_MAX_PATH - 1 - LONGEST_PRODUCER_PATH - PRODUCER_PATH_MARGIN


def check_scratch_root(root, windows=None):
    """Refuse a mirror root so deep that the producers cannot write below it (the real cause would be invisible).

    Without this a deep --scratch makes most producers exit 1 within a second and the scan then dies on a
    missing file with an unrelated message. `windows` defaults to the running platform.
    """
    windows = (os.name == 'nt') if windows is None else windows
    length = len(_plain(os.path.abspath(str(root))))
    if windows and length > MAX_SCRATCH_ROOT:
        raise ToolError(
            'The scratch mirror root is %d characters long (%s); the producers write paths up to %d characters below it '
            'and Windows rejects paths beyond %d (MAX_PATH). Use a short --scratch directory such as C:/bp-sc '
            '(the mirror root, which adds "r", may be at most %d characters).' % (
                length, _plain(os.path.abspath(str(root))), LONGEST_PRODUCER_PATH, WINDOWS_MAX_PATH, MAX_SCRATCH_ROOT))


def git_text(repo, *args, check=False):
    try:
        done = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(repo), *args],
                              capture_output=True, check=False)
    except OSError as error:
        if check:
            raise ToolError('git is not available: %s' % error)
        return None
    if done.returncode != 0:
        if check:
            raise ToolError('git %s failed: %s' % (' '.join(args), done.stderr.decode('utf8', 'replace').strip()))
        return None
    return done.stdout.decode('utf8', 'replace')


def git_bytes(repo, *args):
    done = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(repo), *args], capture_output=True)
    if done.returncode != 0:
        raise ToolError('git %s failed: %s' % (' '.join(args), done.stderr.decode('utf8', 'replace').strip()))
    return done.stdout


def materialize_candidates(repo, commit, paths, directory):
    """Planned upstream files read with `git cat-file blob <commit>:<path>` (offline, read-only) as Sources.

    They are checked like carried files: this answers "what does this file need that the tree lacks?" before
    a slice emits it.
    """
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ToolError('Use the full 40-hex upstream commit for candidate files')
    sources = []
    Path(directory).mkdir(parents=True, exist_ok=True)
    for index, path in enumerate(paths):
        name = path.replace('\\', '/').strip('/')
        if not name.endswith('.kt') or '..' in name.split('/'):
            raise ToolError('Candidate path must be a repository relative .kt file: ' + path)
        real = Path(directory) / ('%03d-%s' % (index, name.rsplit('/', 1)[-1]))
        real.write_bytes(git_bytes(repo, 'cat-file', 'blob', '%s:%s' % (commit, name)))
        sources.append(Source('candidate/%s/%s' % (commit[:9], name), real, 'candidate', 'carried'))
    return sources


def repo_state(repo):
    """Porcelain status including untracked files; None when repo is not a git checkout."""
    return git_text(repo, 'status', '--porcelain', '--untracked-files=all')


def make_scratch_repo(repo, scratch, revision=None):
    """Mirror the repository into scratch/r (a working-tree copy or `git archive <revision>`).

    Producers run against this mirror, so nothing they write can reach the real checkout. The
    mirror is a throw-away git repository that borrows the real object store through
    objects/info/alternates (read-only), because producers read pinned upstream objects with
    `git show <commit>:<path>` and locate the repository by its `.git` entry.
    Returns (scratch repo root, environment overrides).
    """
    repo = Path(repo)
    root = Path(scratch) / 'r'
    check_scratch_root(root)
    root.mkdir(parents=True, exist_ok=False)
    common = git_text(repo, 'rev-parse', '--git-common-dir')
    if common is None:
        raise ToolError('%s is not a git checkout; the producers read pinned upstream objects with git' % repo)
    common = Path(common.strip())
    if not common.is_absolute():
        common = Path(os.path.abspath(str(repo / common)))
    if revision is None:
        excluded = [Path(x) for x in SCRATCH_EXCLUDES]

        def ignore(directory, names):
            relative = Path(os.path.relpath(_plain(directory), os.path.abspath(str(repo))))
            skip = []
            for name in names:
                candidate = (relative / name) if str(relative) != '.' else Path(name)
                if candidate in excluded or name == '__pycache__':
                    skip.append(name)
            return skip

        shutil.copytree(_long(repo), _long(root), ignore=ignore, dirs_exist_ok=True, symlinks=False)
    else:
        pathspec = ['.'] + [':(exclude)' + x for x in SCRATCH_EXCLUDES if x != '.git']
        command = ['git', '-c', 'core.autocrlf=false', '-C', str(repo), 'archive', '--format=tar', revision, '--'] + pathspec
        broken = False
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
            try:
                with tarfile.open(fileobj=process.stdout, mode='r|') as tar:
                    for member in tar:
                        if member.isdir():
                            os.makedirs(_long(root / member.name), exist_ok=True)
                        elif member.isfile():
                            target = _long(root / member.name)
                            os.makedirs(os.path.dirname(target), exist_ok=True)
                            with open(target, 'wb') as handle:
                                shutil.copyfileobj(tar.extractfile(member), handle)
            except tarfile.TarError:
                broken = True  # the exit status and stderr below carry the reason
            # drain the stream padding after the end-of-archive marker and stderr together; reading stderr
            # alone would deadlock against a git process that is still writing padding to stdout
            _, stderr_bytes = process.communicate()
            error = stderr_bytes.decode('utf8', 'replace').strip()
            status = process.returncode
        if status != 0 or broken:
            raise ToolError('git archive %s failed: %s' % (revision, error or 'unreadable archive stream'))
    git_text(root, 'init', '-q', check=True)
    alternates = root / '.git/objects/info/alternates'
    alternates.parent.mkdir(parents=True, exist_ok=True)
    alternates.write_bytes(((common / 'objects').as_posix() + '\n').encode('utf8'))
    return root, {}


def run_producers(root, tasks, env_overrides, timeout=1800, log=None):
    """Run every task the way Gradle does: python <desktop>/<tool> <args>, cwd=<desktop>."""
    root = Path(root)
    desktop = root / 'desktop'
    build = desktop / 'build'
    env = dict(os.environ)
    env.update(env_overrides)
    env['PYTHONUTF8'] = '1'
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    env.pop('PYTHON_EXECUTABLE', None)
    results = []
    for task in tasks:
        command = [sys.executable, '-X', 'utf8', '-B']
        for kind, value in task.args:
            if kind == 'python':
                continue
            if kind == 'literal':
                command.append(str(desktop / value) if value == task.tool else value)
            elif kind == 'repo':
                command.append(str(root))
            elif kind == 'desktop':
                command.append(str(desktop / value) if value else str(desktop))
            elif kind == 'build':
                command.append(str(build / value))
        start = time.monotonic()
        try:
            done = subprocess.run(command, cwd=str(desktop), env=env, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            # not a finding: a producer that never finished gives no output to judge, so the gate cannot judge either
            raise ToolError('producer %s did not finish within %d seconds' % (task.name, timeout))
        elapsed = time.monotonic() - start
        stderr = done.stderr.decode('utf8', 'replace').strip().splitlines()[-12:]
        results.append(dict(task=task.name, tool=task.tool, returncode=done.returncode,
                            seconds=round(elapsed, 1), stderrTail=stderr))
        if log:
            log('  %-52s rc=%d %6.1fs' % (task.name, done.returncode, elapsed))
    return results


# --------------------------------------------------------------------------------------
# Compile universe
# --------------------------------------------------------------------------------------

class Source:
    __slots__ = ('logical', 'real', 'origin', 'set')

    def __init__(self, logical, real, origin, source_set):
        self.logical = logical
        self.real = real
        self.origin = origin
        self.set = source_set


_JAVA_PACKAGE = re.compile(r'(?m)^\s*package\s+([\w.]+)\s*;')
_JAVA_TYPE = re.compile(r'(?m)^(?:public\s+|final\s+|abstract\s+|sealed\s+|strictfp\s+)*(class|interface|enum|record|@interface)\s+(\w+)')
_JAVA_COMMENT = re.compile(r'/\*.*?\*/|//[^\n]*', re.S)


def parse_java(text, path, origin, source_set):
    info = KFile(path, origin, source_set, 'java')
    text = _JAVA_COMMENT.sub(lambda m: re.sub(r'[^\n]', ' ', m.group()), text)
    pm = _JAVA_PACKAGE.search(text)
    if pm:
        info.package = pm.group(1)
    for m in _JAVA_TYPE.finditer(text):
        d = Decl('interface' if m.group(1) in ('interface', '@interface') else 'class', m.group(2),
                 text.count('\n', 0, m.start()) + 1, 0)
        info.decls.append(d)
    return info


def read_source(source):
    return source.real.read_bytes().decode('utf8', 'replace')


def analyze_source(source, keep_tokens=False):
    text = read_source(source)
    if source.real.suffix == '.java':
        return parse_java(text, source.logical, source.origin, source.set)
    return parse_kotlin(text, source.logical, source.origin, source.set, keep_tokens)


def canonical_identity_function(root):
    catalog = json.loads((Path(root) / 'desktop/tools/v025-canonical-sources.json').read_text(encoding='utf8'))
    roots = list(catalog['sourceRoots'])
    previous = catalog['previousPaths']
    pins = catalog['paths']

    def identity(relative):
        name = relative.replace('\\', '/')
        if not any(name.startswith(r) for r in roots):
            return name
        found = previous[name] if name in previous else name
        if found is None:
            raise ToolError('Upstream removed original source: ' + name)
        if found not in pins:
            raise ToolError('Unknown canonical original source: ' + found)
        return found

    return identity, roots


def walk_sources(directory, suffixes=('.kt', '.java')):
    directory = Path(directory)
    found = []
    if not os.path.isdir(_long(directory)):
        return found
    for current, folders, files in os.walk(_long(directory)):
        folders.sort()
        for name in sorted(files):
            if name.endswith(suffixes):
                found.append(Path(current) / name)
    return found


NOT_PRODUCED_NOTE = 'source directory not produced: build/'
MISSING_DIRECTORY_NOTE = 'source directory missing: desktop/'


def collect_sources(root, model, extra_dirs=()):
    """Every file Gradle would hand to compileKotlin / compileTestKotlin, plus the diagnostic corpora.

    Returns (sources, notes). Sets: main, test (compiled); carried (upstream slice archives);
    other (vendored v0.2.5 files that are not compiled, used only to say "defined, but not compiled").
    Notes name registered source directories that are absent; analyze() turns the ones no skipped producer owns
    into findings.
    """
    root = Path(root)
    notes = []
    sources = []
    identity, roots = canonical_identity_function(root)
    manifest = json.loads((root / 'desktop/upstream-sources.json').read_text(encoding='utf8'))
    direct = {identity(entry['path']) for entry in manifest['sources'] if entry.get('mode', 'direct') == 'direct'}
    direct -= {identity(path) for path in model.sync_excludes}
    compiled_real = set()
    for relative in sorted(direct):
        if not relative.endswith(('.kt', '.java')):
            continue
        real = root / relative
        # the long-path form keeps the existence test and later reads honest below a deep mirror root
        if not os.path.isfile(_long(real)):
            raise ToolError('Direct upstream source is missing: ' + relative)
        sources.append(Source(relative, Path(_long(real)), 'vendored', 'main'))
        compiled_real.add(os.path.normcase(str(real)))
    for source_set in ('main', 'test'):
        for sub in ('kotlin', 'java'):
            for real in walk_sources(root / 'desktop/src' / source_set / sub):
                relative = Path(os.path.relpath(str(real).replace('\\\\?\\', ''), str(root))).as_posix()
                sources.append(Source(relative, real, 'desktop', source_set))
    build = root / 'desktop/build'
    for source_set in ('main', 'test'):
        for kind, relative in model.source_dirs[source_set]:
            if kind == 'build':
                if relative == 'generated/upstream':
                    continue  # the Sync of direct vendored files, modelled above
                folder = build / relative
                if not os.path.isdir(_long(folder)):
                    notes.append(NOT_PRODUCED_NOTE + relative)
                    continue
                origin = 'generated'
                logical_root = 'build/' + relative
            else:
                folder = root / 'desktop' / relative
                if not os.path.isdir(_long(folder)):
                    notes.append(MISSING_DIRECTORY_NOTE + relative)
                    continue
                origin = 'third-party'
                logical_root = 'desktop/' + relative
            for real in walk_sources(folder):
                inner = Path(os.path.relpath(str(real).replace('\\\\?\\', ''), str(folder))).as_posix()
                sources.append(Source(logical_root + '/' + inner, real, origin, source_set))
    for index, folder in enumerate(extra_dirs):
        for real in walk_sources(folder):
            inner = Path(os.path.relpath(str(real).replace('\\\\?\\', ''), str(folder))).as_posix()
            sources.append(Source('extra%d/%s' % (index, inner), real, 'generated', 'main'))
    for real in walk_sources(root / 'desktop/upstream-slices'):
        relative = Path(os.path.relpath(str(real).replace('\\\\?\\', ''), str(root))).as_posix()
        sources.append(Source(relative, real, 'archive', 'carried'))
    for vendored_root in roots:
        for real in walk_sources(root / vendored_root.rstrip('/')):
            if os.path.normcase(str(real).replace('\\\\?\\', '')) in compiled_real:
                continue
            relative = Path(os.path.relpath(str(real).replace('\\\\?\\', ''), str(root))).as_posix()
            if relative in direct:
                continue
            sources.append(Source(relative, real, 'vendored-other', 'other'))
    return sources, notes


class ScopeIndex:
    """Declarations of one source set, by package and by simple name."""

    def __init__(self):
        self.packages = defaultdict(lambda: defaultdict(list))
        self.by_name = defaultdict(list)
        self.extensions = defaultdict(list)
        self.nested_extensions = set()
        self.files = []

    def add(self, info):
        self.files.append(info)
        pkg = self.packages[info.package]
        for d in info.decls:
            if d.member_ext:
                self.nested_extensions.update(d.member_ext)
            pkg[d.name].append((info, d))
            self.by_name[d.name].append((info, d))
            if d.receiver:
                self.extensions[d.name].append((info, d))


class Universe:
    def __init__(self):
        self.scopes = {name: ScopeIndex() for name in ('main', 'test', 'carried', 'other')}
        self.synthetic = defaultdict(set)   # package -> names declared by producers that cannot run offline
        self.identifiers = {}               # identifier -> first compiled path (only for identifier-kind latent entries)

    def add(self, info):
        self.scopes[info.set].add(info)

    def lookup(self, scope_names, package, name):
        hits = []
        for scope in scope_names:
            for entry in self.scopes[scope].packages.get(package, {}).get(name, ()):
                hits.append((scope,) + entry)
        if not hits and name in self.synthetic.get(package, ()):
            hits.append(('main', None, None))
        return hits

    def known_package(self, scope_names, package):
        for scope in scope_names:
            if package in self.scopes[scope].packages:
                return True
        return package in self.synthetic

    def named_anywhere(self, name):
        return [(scope,) + entry for scope in self.scopes for entry in self.scopes[scope].by_name.get(name, ())]

    def extension_exists(self, scope_names, name):
        return any(self.scopes[s].extensions.get(name) or name in self.scopes[s].nested_extensions
                   for s in scope_names)


VISIBLE = {'main': ('main',), 'test': ('main', 'test'), 'carried': ('main', 'test', 'carried', 'other'),
           'other': ('main', 'test', 'carried', 'other')}


def resolve_path(universe, scope, parts):
    """Resolve a dotted path as a top-level declaration (plus an optional member).

    Returns ('ok', hit, rest) | ('member', hit, member) | ('missing', None). `rest` is what remains of the path
    after the top-level declaration (a nested member when non-empty). Names are matched by simple name inside
    the package; the longest package wins.
    """
    visible = VISIBLE[scope]
    for k in range(len(parts) - 1, 0, -1):
        package = '.'.join(parts[:k])
        hits = universe.lookup(visible, package, parts[k])
        if not hits:
            continue
        rest = parts[k + 1:]
        if not rest:
            return ('ok', hits[0], rest)
        member = rest[0]
        for hit in hits:
            d = hit[2]
            if d is None or hit[1] is None or hit[1].language == 'java' or d.kind == 'typealias' or not d.classifier:
                return ('ok', hit, rest)
            if d.classifier and (member in d.members or member == 'Companion'):
                return ('ok', hit, rest)
            if d.classifier and d.supers and not any(
                    universe.lookup(visible, package, s) or universe.named_anywhere(s) for s in d.supers):
                return ('ok', hit, rest)
        return ('member', hits[0], member)
    return ('missing', None)


# --------------------------------------------------------------------------------------
# Findings
# --------------------------------------------------------------------------------------

class Finding:
    __slots__ = ('check', 'scope', 'symbol', 'file', 'line', 'detail', 'gap')

    def __init__(self, check, scope, symbol, file, line, detail=''):
        self.check = check
        self.scope = scope
        self.symbol = symbol
        self.file = file
        self.line = line
        self.detail = detail
        self.gap = None  # the expected-gaps entry that accepted this finding

    def key(self):
        return (self.scope, self.check, self.symbol)

    def sort_key(self):
        return (self.scope, self.check, self.symbol, self.file, self.line)


def _missing_detail(universe, name, base):
    """Explain an unresolved name: where else the simple name is declared (a hint, never a resolution)."""
    compiled, other = [], []
    for scope, info, _ in universe.named_anywhere(name):
        if scope in ('main', 'test'):
            entry = info.package or '(default package)'
            if entry not in compiled:
                compiled.append(entry)
        elif info.path not in other:
            other.append('%s:%s' % (scope, info.path))
    detail = base
    if compiled:
        detail += '; the simple name is declared in compiled package %s (relocated upstream?)' % ', '.join(compiled[:2])
    if other and not compiled:
        detail += '; the simple name is declared only in non-compiled sources: ' + ', '.join(other[:3])
    return detail


def check_duplicates(universe):
    findings = []
    for scope in ('main', 'test'):
        groups = defaultdict(list)
        facades = defaultdict(list)
        for info in universe.scopes[scope].files:
            facade = info.facade
            if facade:
                facades[(info.package, facade)].append(info)
            for d in info.decls:
                if 'expect' in d.modifiers or 'actual' in d.modifiers:
                    continue
                groups[(info.package, d.key())].append((info, d))
        for (package, _), entries in groups.items():
            if len(entries) < 2:
                continue
            d0 = entries[0][1]
            if d0.classifier:
                clash = entries
            else:
                public = [e for e in entries if e[1].visibility != 'private']
                clash = list(public) if len(public) > 1 else []
                by_file = defaultdict(list)
                for e in entries:
                    by_file[e[0].path].append(e)
                for same_file in by_file.values():
                    if len(same_file) > 1:
                        clash = clash + [e for e in same_file if e not in clash]
            if len(clash) < 2:
                continue
            clash.sort(key=lambda e: (e[0].path, e[1].line))
            symbol = (package + '.' if package else '') + d0.label()
            where = ', '.join('%s:%d' % (e[0].path, e[1].line) for e in clash)
            for info, d in clash:
                findings.append(Finding('duplicate-declaration', scope, symbol, info.path, d.line,
                                        'declared %d times in one source set: %s' % (len(clash), where)))
        for (package, facade), infos in facades.items():
            if len(infos) > 1:
                where = ', '.join(sorted(i.path for i in infos))
                for info in infos:
                    findings.append(Finding('duplicate-file-class', scope, (package + '.' if package else '') + facade,
                                            info.path, 1, 'same JVM file facade from several files: ' + where))
    return findings


def _project_path(path):
    return path.startswith(tuple(p + '.' for p in PROJECT_PREFIXES))


# Classes the Android Gradle plugin generates; no source file declares them, and carried upstream files use them.
ANDROID_GENERATED = ('com.android.purebilibili.R', 'com.android.purebilibili.BuildConfig',
                     'com.android.purebilibili.designsystem.R', 'com.android.purebilibili.designsystem.BuildConfig')


def _android_generated(path):
    return any(path == g or path.startswith(g + '.') for g in ANDROID_GENERATED)


def check_imports(universe, infos):
    """Imports of project symbols and of the Android SDK namespace that nothing declares."""
    findings = []
    for info in infos:
        scope = info.set
        for imp in info.imports:
            sdk = imp.path.startswith(SDK_PREFIX) and not imp.path.startswith(('android.compose', 'androidx.'))
            if not (_project_path(imp.path) or sdk):
                continue
            if scope == 'carried' and (sdk or _android_generated(imp.path)):
                continue  # carried files are Android sources; SDK and generated classes are expected there
            parts = imp.path.split('.')
            if imp.star:
                parent = '.'.join(parts)
                if universe.known_package(VISIBLE[scope], parent):
                    continue
                state = resolve_path(universe, scope, parts)
                if state[0] == 'ok':
                    continue
                symbol = imp.path + '.*'
            else:
                state = resolve_path(universe, scope, parts)
                if state[0] == 'ok':
                    continue
                symbol = imp.path
            check = 'unresolved-android-import' if sdk else 'unresolved-import'
            if state[0] == 'member':
                detail = 'declaring type found but it has no member "%s"' % state[2]
            else:
                detail = _missing_detail(universe, parts[-1], 'import not declared by any compiled source')
            findings.append(Finding(check, scope, symbol, info.path, imp.line, detail))
    return findings


# --------------------------------------------------------------------------------------
# Qualified references (regex over masked text; imports and the package line are skipped)
# --------------------------------------------------------------------------------------

_QUALIFIED = re.compile(r'(?<![\w.])com\s*\.\s*(?:android\s*\.\s*purebilibili|bilipai\s*\.\s*desktop)'
                        r'(?:\s*\.\s*(?:`[^`\n]+`|\w+))+')
_RECEIVER_CONTEXT = re.compile(r'(?:\bfun|\bval|\bvar)\s+(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>\s*)?$')
_WHITESPACE = re.compile(r'\s+')


def qualified_references(masked, drop_generated=False):
    """(chain parts, 1-based line) of every com.android.purebilibili / com.bilipai.desktop chain.

    A chain written as the receiver of an extension declaration (`fun com.x.Type.name()`) ends in the
    declared name, which is not a reference; that last part is dropped.
    """
    found = []
    for m in _QUALIFIED.finditer(masked):
        start = masked.rfind('\n', 0, m.start()) + 1
        head = masked[start:m.start()].strip()
        if head.startswith(('import', 'package')) or head.endswith(('import', 'package')):
            continue
        parts = _WHITESPACE.sub('', m.group()).replace('`', '').split('.')
        if drop_generated and _android_generated('.'.join(parts)):
            continue
        if _RECEIVER_CONTEXT.search(masked[max(0, m.start() - 80):m.start()]):
            parts = parts[:-1]
        found.append((parts, masked.count('\n', 0, m.start()) + 1))
    return found


def unresolved_symbol(universe, scope, parts):
    """The declaration a fully qualified chain names: package prefix plus the first part after it."""
    visible = VISIBLE[scope]
    for k in range(len(parts) - 1, 2, -1):
        # a known package followed by a sub-package segment is not yet the declaration
        if universe.known_package(visible, '.'.join(parts[:k])) and (parts[k][:1].isupper() or k == len(parts) - 1):
            return '.'.join(parts[:k + 1])
    for index, part in enumerate(parts):
        if index > 2 and part[:1].isupper():
            return '.'.join(parts[:index + 1])
    return '.'.join(parts)


def check_qualified(universe, scope, path, masked):
    findings = []
    seen = set()
    for parts, line in qualified_references(masked, drop_generated=(scope == 'carried')):
        if len(parts) <= 3:
            continue
        state = resolve_path(universe, scope, parts)
        if state[0] == 'ok':
            continue
        symbol = unresolved_symbol(universe, scope, parts)
        if state[0] == 'member':
            symbol = '.'.join(parts[:parts.index(state[2]) + 1]) if state[2] in parts else symbol
        if (symbol, line) in seen:
            continue
        seen.add((symbol, line))
        if state[0] == 'member':
            detail = 'declaring type found but it has no member "%s"' % state[2]
        else:
            detail = _missing_detail(universe, parts[-1],
                                     'fully qualified reference with no declaration in the compiled sources')
        findings.append(Finding('unresolved-qualified', scope, symbol, path, line, detail))
    return findings


# --------------------------------------------------------------------------------------
# Region facts (names a declaration declares / uses), shared by the name-level checks
# --------------------------------------------------------------------------------------

_LAMBDA_FILLER = frozenset([',', ':', '(', ')', '<', '>', '?', '.', '*'])
STDLIB_DOTTED = frozenset(['let', 'also', 'apply', 'run', 'takeIf', 'takeUnless', 'to', 'javaClass', 'equals',
                           'hashCode', 'toString', 'copy'])
ALWAYS_BOUND = frozenset(['Companion', '_'])


def _declare_type_params(toks, j, b, declared):
    """toks[j] == '<' after fun/class/...: declare the type parameter names; return the index after '>'."""
    depth = 0
    i = j
    expect = True
    while i < b:
        t = toks[i]
        if t == '<':
            depth += 1
            expect = depth == 1
        elif t == '>':
            depth -= 1
            if depth == 0:
                return i + 1
        elif t == ',' and depth == 1:
            expect = True
        elif expect and depth == 1 and is_ident(t) and t not in ('reified', 'in', 'out'):
            declared.add(t)
            expect = False
        elif t in ('{', '}', ';', '=', '(') and depth <= 1:
            return i
        i += 1
    return b


class RegionFacts:
    __slots__ = ('declared', 'counts', 'uses', 'dotted')

    def __init__(self):
        self.declared = set()
        self.counts = Counter()
        self.uses = []      # (name, token index) of bare uses
        self.dotted = []    # (name, token index) of names after a dot


def analyze_region(toks, lines, a, b, skip_lines, extra_declared=()):
    """Names declared inside toks[a:b] and the bare / dotted identifier uses of the region."""
    facts = RegionFacts()
    declared = facts.declared
    declared.update(extra_declared)
    counts = facts.counts
    n = len(toks)
    for i in range(a, b):
        t = toks[i]
        if t in ('val', 'var', 'fun', 'class', 'interface', 'object', 'typealias'):
            j = i + 1
            if j < b and toks[j] == '<':
                j = _declare_type_params(toks, j, b, declared)
            if t in ('class', 'interface', 'typealias') and i + 2 < b and toks[i + 2] == '<':
                _declare_type_params(toks, i + 2, b, declared)
            if t in ('val', 'var') and j < b and toks[j] == '(':
                close = _match(toks, j, '(', ')')
                for q in range(j + 1, min(close, b)):
                    if is_ident(toks[q]) and toks[q + 1] in (',', ')', ':'):
                        declared.add(toks[q])
                        counts[toks[q]] += 1
            elif t in ('val', 'var', 'fun'):
                k = j
                while k < b and toks[k] not in ('(', ':', '=', 'by', 'get', 'set', ';', '{', '}', ')', ',') and k - j < 40:
                    k += 1
                head = toks[j:k]
                if head and is_ident(head[-1]):
                    declared.add(head[-1])
                    counts[head[-1]] += 1
            elif j < b and is_ident(toks[j]):
                declared.add(toks[j])
                counts[toks[j]] += 1
            if t == 'class' and i > a and toks[i - 1] == 'enum':
                body = _class_body(toks, i, b)
                if body is not None:
                    declared.update(_enum_entries(toks, body, _match(toks, body, '{', '}')))
        elif t == 'for' and i + 1 < b and toks[i + 1] == '(':
            close = _match(toks, i + 1, '(', ')')
            for q in range(i + 2, min(close, b)):
                if toks[q] == 'in':
                    break
                if is_ident(toks[q]):
                    declared.add(toks[q])
                    counts[toks[q]] += 1
        elif t == '->' and i > a:
            depth = 0
            q = i - 1
            limit = max(a, i - 40)
            while q >= limit:
                u = toks[q]
                if u == ')':
                    depth += 1
                elif u == '(':
                    depth -= 1
                    if depth < 0:
                        break
                elif u == '{':
                    if depth == 0:
                        previous = toks[q - 1] if q > 0 else ''
                        is_when = previous == 'when'
                        if previous == ')':
                            open_paren = q - 1
                            level = 0
                            while open_paren >= 0:
                                if toks[open_paren] == ')':
                                    level += 1
                                elif toks[open_paren] == '(':
                                    level -= 1
                                    if level == 0:
                                        break
                                open_paren -= 1
                            is_when = open_paren > 0 and toks[open_paren - 1] == 'when'
                        if not is_when:
                            for w in toks[q + 1:i]:
                                if is_ident(w):
                                    declared.add(w)
                                    counts[w] += 1
                    break
                elif not (is_ident(u) or u in _LAMBDA_FILLER or u == '_'):
                    break
                q -= 1
        elif is_ident(t) and i + 1 < n and toks[i + 1] == ':':
            declared.add(t)
            counts[t] += 1
    for i in range(a, b):
        t = toks[i]
        if not is_ident(t) or t in SOFT_KEYWORDS or lines[i] in skip_lines:
            continue
        previous = toks[i - 1] if i > 0 else ''
        following = toks[i + 1] if i + 1 < n else ''
        if previous in ('.', '?.'):
            facts.dotted.append((t, i))
            continue
        if previous == '::':
            before = toks[i - 2] if i >= 2 else ''
            if before and (is_ident(before) or before in (')', '>', ']', '?')):
                continue
        if following == ':':
            continue
        if following == '=' and (i + 2 >= n or toks[i + 2] != '='):
            continue
        if following == '@':
            continue
        if previous == '@':
            before = toks[i - 2] if i >= 2 else ''
            if before in ('return', 'this', 'break', 'continue', 'super'):
                continue
        facts.uses.append((t, i))
    return facts


class FileContext:
    """What a subject file makes visible by name, independent of any one declaration."""

    def __init__(self, universe, info, providers):
        self.info = info
        scope = info.set
        visible = VISIBLE[scope]
        self.visible = visible
        self.providers = providers
        self.imports = {}
        self.stars = []
        self.external_star = False
        for imp in info.imports:
            if imp.star:
                self.stars.append(imp.path)
                if not _project_path(imp.path):
                    self.external_star = True
            else:
                self.imports[imp.simple] = imp.path
        names = set()
        for s in visible:
            names.update(universe.scopes[s].packages.get(info.package, ()))
        names.update(universe.synthetic.get(info.package, ()))
        for star in self.stars:
            if _project_path(star):
                for s in visible:
                    names.update(universe.scopes[s].packages.get(star, ()))
                names.update(universe.synthetic.get(star, ()))
                state = resolve_path(universe, scope, star.split('.'))
                if state[0] == 'ok' and state[1][2] is not None and not state[2]:
                    names.update(state[1][2].members)
        self.package_names = names
        # A project star package the universe knows is fully enumerated in package_names above, so a name absent
        # from it is unresolved no matter which other scanned file imports that name explicitly from the package.
        # Stars of other packages (AndroidX, libraries) and of unknown project packages stay lenient.
        self.closed_stars = frozenset(star for star in self.stars
                                      if _project_path(star) and universe.known_package(visible, star))
        self.import_lines = {imp.line for imp in info.imports}

    def star_provides(self, name, path=None):
        """True when a star import of this file brings `name` into scope."""
        providers = self.providers.get(name, ())
        for star in self.stars:
            if star in providers or (path is not None and star == path.rsplit('.', 1)[0]):
                return True
        return False

    def resolves(self, name, facts, ambient, locals_elsewhere=(), learning=False):
        """True when `name` is bound in this file. `learning` is the ambient-name pass over the vendored and
        carried corpus: there a name some file imports from a star-imported package is the project's, never
        ambient, so every matching star counts and the corpus cannot teach a missing project symbol as ambient."""
        if name in facts.declared or name in self.imports or name in self.package_names:
            return True
        if name in ALWAYS_BOUND or name in ambient:
            return True
        providers = self.providers.get(name)
        if providers:
            # some scanned file imports this name explicitly: a star import of that package supplies it only when
            # the star cannot be enumerated (external or unknown package); a known project package would already
            # have contributed the name to package_names
            return any(star in providers and (learning or star not in self.closed_stars) for star in self.stars)
        # nothing imports this name anywhere: only a star import could supply it, but a star import cannot
        # supply a name this very file declares as a local of another declaration
        return self.external_star and name not in locals_elsewhere


def unresolved_uses(ctx, facts, ambient, locals_elsewhere=(), learning=False):
    result = []
    seen = set()
    for name, index in facts.uses:
        if name in seen:
            continue
        if not ctx.resolves(name, facts, ambient, locals_elsewhere, learning):
            seen.add(name)
            result.append((name, index))
    return result


def build_providers(parsed_infos):
    """simple name -> packages it is imported from anywhere in the scanned sources (what a star could supply)."""
    providers = defaultdict(set)
    for info in parsed_infos:
        for imp in info.imports:
            if not imp.star:
                providers[imp.simple].add(imp.path.rsplit('.', 1)[0])
    return providers


# Corpus origins whose files may teach CAPITALIZED ambient names. Both are vendored v0.2.5 files, so a capitalized
# name one of them uses without declaring or importing it is a stdlib, Java, Android or Compose name (Array,
# IllegalStateException, Runnable, ...), never a project symbol whose definer is missing from this universe:
# 'vendored' is the compile-proven main source set, 'vendored-other' the rest of the same vendored tree. The
# capitalized names only 'vendored-other' teaches were reviewed on the real tree: library names, plus OverlayClip,
# which is androidx.compose.animation.SharedTransitionScope.OverlayClip reached through an implicit
# SharedTransitionScope receiver. An 'archive' is an upstream snapshot taken at a LATER commit whose same-package
# neighbours are missing, so everything it references bare and undeclared looks "ambient"; teaching capitalized
# names from it would silence exactly the missing-definer class this gate exists for (CommentFraudFeedbackHost,
# VideoLoadTimeline, StartupAnimationStyle, ...).
AMBIENT_CAPITAL_ORIGINS = ('vendored', 'vendored-other')


def _project_package(package):
    """True for a project package or the project root package itself (providers hold packages, not symbols)."""
    return _project_path(package + '.')


def teaches_ambient(origin, name, providers):
    """True when a corpus file of `origin` that uses `name` bare and unresolved may make it an ambient name.

    Vendored files teach every name. An archive teaches only a lowercase name (a library extension or an implicit
    receiver member such as drawBackdrop) that no scanned file imports from a project package: a lowercase name a
    project package supplies is the project's, so an archive that references it bare must not silence a generated
    file that references it the same way.
    """
    if origin in AMBIENT_CAPITAL_ORIGINS:
        return True
    if not name[:1].islower():
        return False
    return not any(_project_package(package) for package in providers.get(name, ()))


def learn_ambient(universe, corpus_sources, providers):
    """Names the corpus uses without declaring or importing them (stdlib, library, implicit receivers).

    Only star-import-free corpus files teach: there a name that nothing declares or imports can only be ambient.
    Which origin may teach which name is decided by teaches_ambient.
    """
    ambient = set()
    for source in corpus_sources:
        if source.real.suffix != '.kt':
            continue
        text = read_source(source)
        info = parse_kotlin(text, source.logical, source.origin, source.set, keep_tokens=True)
        if any(imp.star and not _project_path(imp.path) for imp in info.imports):
            continue
        ctx = FileContext(universe, info, providers)
        toks, lines = info.toks, info.lines
        for d in info.decls:
            facts = analyze_region(toks, lines, d.start, d.end, ctx.import_lines, d.members if d.classifier else ())
            for name, _ in unresolved_uses(ctx, facts, frozenset(), learning=True):
                if teaches_ambient(source.origin, name, providers):
                    ambient.add(name)
    return ambient


# --------------------------------------------------------------------------------------
# Name-level checks on generated sources
# --------------------------------------------------------------------------------------

def _ids(toks, a, b):
    return frozenset(t for t in toks[a:b] if is_ident(t))


class OriginalCache:
    """Lazily parsed original (archive / vendored) files, keyed by logical path."""

    def __init__(self, sources_by_path, limit=24):
        self.sources = sources_by_path
        self.limit = limit
        self.cache = {}
        self.order = []

    def get(self, path):
        if path in self.cache:
            return self.cache[path]
        source = self.sources[path]
        info = analyze_source(source, keep_tokens=True)
        self.cache[path] = info
        self.order.append(path)
        if len(self.order) > self.limit:
            self.cache.pop(self.order.pop(0), None)
        return info


def _simple_type(text):
    """Simple class name of a type text like `Foo`, `a.b.Foo?` or `Foo<Bar>`; None for function types etc."""
    m = re.fullmatch(r'(?:[A-Za-z_]\w*\.)*([A-Z]\w*)(?:<.*>)?\??', text)
    return m.group(1) if m else None


def _closed_type(decl):
    """A class no subtype of which can be smart-cast to: not sealed, open, abstract or an interface."""
    return decl.kind in ('class', 'object') and not (decl.modifiers & {'sealed', 'open', 'abstract'})


def _smart_cast_names(toks, a, b):
    names = set()
    for i in range(a, b - 1):
        if toks[i + 1] in ('is', 'as') and is_ident(toks[i]):
            names.add(toks[i])
        if toks[i] == 'when' and toks[i + 1] == '(' and i + 3 < b and toks[i + 3] == ')' and is_ident(toks[i + 2]):
            names.add(toks[i + 2])
    return names


def check_names(universe, info, ctx, ambient, originals_index, cache, enable):
    """Checks missing-copied-import, unresolved-name and unresolved-member for one generated file."""
    findings = []
    toks, lines = info.toks, info.lines
    scope = info.set
    visible = ctx.visible
    reported = set()
    class_cache = {}

    def classifier_for(name):
        if name in class_cache:
            return class_cache[name]
        found = None
        path = ctx.imports.get(name)
        if path:
            state = resolve_path(universe, scope, path.split('.'))
            if state[0] == 'ok' and not state[2] and state[1][2] is not None and state[1][2].classifier \
                    and state[1][1].language == 'kotlin':
                found = state[1][2]
        else:
            hits = [h for h in universe.lookup(visible, info.package, name)
                    if h[2] is not None and h[2].classifier and h[1].language == 'kotlin']
            if len(hits) == 1:
                found = hits[0][2]
            if found is None:
                for star in ctx.stars:
                    if _project_path(star):
                        hits = [h for h in universe.lookup(visible, star, name)
                                if h[2] is not None and h[2].classifier and h[1].language == 'kotlin']
                        if len(hits) == 1:
                            found = hits[0][2]
                            break
        class_cache[name] = found
        return found

    def classifier_from_file(owner_info, name):
        """Resolve a simple type name the way it is written in owner_info (the file that declares a callee)."""
        key = (owner_info.path, name)
        if key in class_cache:
            return class_cache[key]
        found = None
        explicit = [imp.path for imp in owner_info.imports if not imp.star and imp.simple == name]
        if explicit:
            state = resolve_path(universe, scope, explicit[0].split('.'))
            if state[0] == 'ok' and not state[2] and state[1][2] is not None and state[1][2].classifier \
                    and state[1][1].language == 'kotlin':
                found = state[1][2]
        else:
            hits = [h for h in universe.lookup(visible, owner_info.package, name)
                    if h[2] is not None and h[2].classifier and h[1].language == 'kotlin']
            if len(hits) == 1:
                found = hits[0][2]
            if found is None:
                for imp in owner_info.imports:
                    if imp.star and _project_path(imp.path):
                        hits = [h for h in universe.lookup(visible, imp.path, name)
                                if h[2] is not None and h[2].classifier and h[1].language == 'kotlin']
                        if len(hits) == 1:
                            found = hits[0][2]
                            break
        class_cache[key] = found
        return found

    def member_closure(decl, depth=0):
        """Members of decl and of its project supertypes; None when a supertype is not a project type."""
        members = set(decl.members)
        for sup in decl.supers:
            hits = []
            for s_ in visible:
                hits.extend(h for h in universe.scopes[s_].by_name.get(sup, ()) if h[1].classifier)
            if not hits or depth > 4:
                return None
            for owner_info, hit in hits:
                if owner_info.language == 'java':
                    return None
                inner = member_closure(hit, depth + 1)
                if inner is None:
                    return None
                members |= inner
        return members

    regions = []
    declared_count = Counter()
    for d in info.decls:
        inherited = ()
        lenient = False
        if d.classifier and d.kind != 'typealias':
            closure = member_closure(d)
            # a class with a library supertype or member extensions has implicit receivers the scan cannot see
            lenient = closure is None or bool(d.member_ext)
            inherited = closure if closure is not None else d.members
        elif d.receiver:
            simple = _simple_type(d.receiver)
            receiver_decl = classifier_for(simple) if simple else None
            if receiver_decl is not None and receiver_decl.kind != 'typealias':
                closure = member_closure(receiver_decl)
                inherited = closure if closure is not None else receiver_decl.members
        facts = analyze_region(toks, lines, d.start, d.end, ctx.import_lines, inherited)
        regions.append((d, facts, lenient))
        if d.kind in ('fun', 'val'):
            declared_count.update(facts.declared - set(inherited))

    for d, facts, lenient in regions:
        # --- missing-copied-import: bare names the original imported and this file does not
        copied_names = set()
        if 'copied' in enable:
            ids = _ids(toks, d.start, d.end)
            if len(ids) >= 8:
                kind = 'type' if d.classifier else d.kind
                best = None
                for original_info, _ in originals_index.get((kind, d.name), ()):
                    if original_info.path == info.path:
                        continue
                    original_file = cache.get(original_info.path)
                    for od in original_file.decls:
                        if od.name != d.name or od.key() != d.key():
                            continue
                        oids = _ids(original_file.toks, od.start, od.end)
                        union = len(ids | oids)
                        score = len(ids & oids) / union if union else 0.0
                        if score >= 0.55 and (best is None or score > best[0]):
                            best = (score, original_file, od)
                if best is not None:
                    original_file = best[1]
                    imported = {imp.simple: imp.path for imp in original_file.imports if not imp.star}
                    for name, index in facts.uses:
                        path = imported.get(name)
                        if not path or name in copied_names:
                            continue
                        if name in facts.declared or name in ctx.imports or name in ctx.package_names:
                            continue
                        if ctx.star_provides(name, path) or name in ALWAYS_BOUND:
                            continue
                        copied_names.add(name)
                        key = ('missing-copied-import', path)
                        if key in reported:
                            continue
                        reported.add(key)
                        findings.append(Finding(
                            'missing-copied-import', scope, path, info.path, lines[index],
                            '%s uses "%s", which the original %s imports (as %s) and this file does not' %
                            (d.label(), name, original_file.path, path)))
        # --- unresolved-name: names nothing declares or imports
        if 'names' in enable:
            for name, index in unresolved_uses(ctx, facts, ambient, declared_count):
                if name in copied_names:
                    continue
                if name[:1].islower() and (lenient or not (name in ctx.providers or declared_count.get(name))):
                    # a lowercase name nothing in the project knows is most likely a member of a library receiver
                    continue
                key = ('unresolved-name', name)
                if key in reported:
                    continue
                reported.add(key)
                findings.append(Finding('unresolved-name', scope, name, info.path, lines[index],
                                        'used in %s but not declared, imported, in the same package or ambient' % d.label()))
        # --- unresolved-member: members a project type does not have
        if 'members' in enable:
            casts = _smart_cast_names(toks, d.start, d.end)
            types = defaultdict(set)
            for name, text in _local_types(toks, d):
                simple = _simple_type(text)
                types[name].add(classifier_for(simple) if simple else None)
            for name, callee in _local_calls(toks, d):
                decl = None
                if callee[:1].isupper():
                    decl = classifier_for(callee)
                else:
                    candidates = []
                    path = ctx.imports.get(callee)
                    pkg = path.rsplit('.', 1)[0] if path else info.package
                    for s_ in visible:
                        candidates.extend(h for h in universe.scopes[s_].packages.get(pkg, {}).get(callee, ())
                                          if h[1].kind == 'fun' and not h[1].receiver)
                    returns = {c[1].ret for c in candidates if c[1].ret}
                    if len(returns) == 1 and all(c[1].ret for c in candidates):
                        simple = _simple_type(next(iter(returns)))
                        decl = classifier_from_file(candidates[0][0], simple) if simple else None
                types[name].add(decl)
            for member, index in facts.dotted:
                if index < 2 or toks[index - 1] != '.':
                    continue
                owner = toks[index - 2]
                if not is_ident(owner) or (index >= 3 and toks[index - 3] in ('.', '?.')):
                    continue
                decl = None
                if owner[:1].isupper() and owner not in facts.declared:
                    decl = classifier_for(owner)
                elif owner in types and len(types[owner]) == 1 and facts.counts.get(owner, 0) <= 1 \
                        and owner not in casts:
                    decl = next(iter(types[owner]))
                    if decl is not None and not _closed_type(decl):
                        decl = None
                if decl is None or decl.kind == 'typealias' or decl.name == member:
                    continue
                if member in STDLIB_DOTTED or member in decl.members or member in ALWAYS_BOUND:
                    continue
                closure = member_closure(decl)
                if closure is None or member in closure:
                    continue
                if universe.extension_exists(visible, member) or member in ctx.providers:
                    continue
                key = ('unresolved-member', decl.name + '.' + member)
                if key in reported:
                    continue
                reported.add(key)
                findings.append(Finding('unresolved-member', scope, decl.name + '.' + member, info.path, lines[index],
                                        '%s has no member or extension "%s" in the compiled sources' % (decl.name, member)))
    return findings


def _local_types(toks, d):
    """(name, type text) from `val|var name: Type` and function parameters of declaration d."""
    pairs = []
    a, b = d.start, d.end
    for i in range(a, b - 3):
        t = toks[i]
        if t in ('val', 'var') and is_ident(toks[i + 1]) and toks[i + 2] == ':':
            j = i + 3
            collected = []
            angle = 0
            while j < b:
                u = toks[j]
                if u == '<':
                    angle += 1
                elif u == '>':
                    angle = max(0, angle - 1)
                if angle == 0 and u in ('=', ';', '{', '}', 'by', ',', ')'):
                    break
                collected.append(u)
                j += 1
                if len(collected) > 40:
                    break
            pairs.append((toks[i + 1], _norm(collected)))
    if d.kind == 'fun':
        k = d.header + 1
        while k < b and toks[k] != '(':
            k += 1
        if k < b:
            close = _match(toks, k, '(', ')')
            item = []
            depth = 0
            angle = 0
            for q in range(k + 1, close + 1):
                u = toks[q]
                if u in ('(', '[', '{'):
                    depth += 1
                elif u in (')', ']', '}'):
                    depth -= 1
                if q == close or (depth == 0 and u == ',' and angle == 0):
                    if ':' in item:
                        c = item.index(':')
                        names = [x for x in item[:c] if is_ident(x) and x not in MODIFIERS]
                        type_tokens = item[c + 1:]
                        if '=' in type_tokens:
                            type_tokens = type_tokens[:type_tokens.index('=')]
                        if names:
                            pairs.append((names[-1], _norm(type_tokens)))
                    item, angle = [], 0
                    continue
                if u == '<':
                    angle += 1
                elif u == '>':
                    angle = max(0, angle - 1)
                item.append(u)
    return pairs


def _local_calls(toks, d):
    """(name, callee) from `val|var name = callee(...)` where the call is the whole initializer."""
    pairs = []
    n = len(toks)
    for i in range(d.start, d.end - 4):
        if toks[i] in ('val', 'var') and is_ident(toks[i + 1]) and toks[i + 2] == '=' and is_ident(toks[i + 3]) \
                and toks[i + 4] == '(':
            close = _match(toks, i + 4, '(', ')')
            following = toks[close + 1] if close + 1 < n else ''
            if following in ('.', '?.', '?:', '!!', '[', '{', 'as') or following in ('+', '-', '*', '/', '%'):
                continue
            pairs.append((toks[i + 1], toks[i + 3]))
    return pairs


# --------------------------------------------------------------------------------------
# Expected gaps
# --------------------------------------------------------------------------------------

KNOWN_CHECKS = ('duplicate-declaration', 'duplicate-file-class', 'unresolved-import', 'unresolved-android-import',
                'unresolved-qualified', 'missing-copied-import', 'unresolved-name', 'unresolved-member',
                'producer-failed', 'producer-output-missing')
KNOWN_SCOPES = ('compiled', 'carried')
# How sure the owner is that the closing slice really is the one that will bring the symbol: 'roadmap' = the roadmap
# text of that slice names the symbol; 'proposed' = an inference by the gap's author that the slice owner must
# confirm or reassign when the slice is reached; 'unassigned' = no roadmap slice owns it (closedBy says none).
KNOWN_EVIDENCE = ('roadmap', 'proposed', 'unassigned')
# Which Options.checks group produces each finding kind. producer-failed and producer-output-missing do not
# depend on the options: they come from the producer run itself.
CHECK_GROUP = {'duplicate-declaration': 'duplicates', 'duplicate-file-class': 'duplicates',
               'unresolved-import': 'imports', 'unresolved-android-import': 'imports',
               'unresolved-qualified': 'qualified', 'missing-copied-import': 'copied',
               'unresolved-name': 'names', 'unresolved-member': 'members'}
NAME_GROUPS = ('copied', 'names', 'members')
# The --checks groups, in report order. A selection naming anything else, or nothing, is a ToolError: a typo must
# never run zero checks and print PASS.
CHECK_NAMES = ('duplicates', 'imports', 'qualified', 'copied', 'names', 'members')
# Which sources the name-level checks inspect. 'all' (desktop-owned and third-party code too) is a library-level
# option the unit tests use; the command line does not offer it because on the real tree it reports JDK and
# library names (ProcessBuilder, Shader, assertNull, ...) and member names of desktop-owned types as unresolved
# (measured: 107 findings in 38 groups on a tree that passes), so it would read as a stricter gate and is not one.
NAMES_SCOPES = ('generated', 'all', 'none')
CLI_NAMES_SCOPES = ('generated', 'none')

HEURISTIC_LIMITS = (
    'This is a lexical scan, not a compiler: no overload resolution, typealias expansion, generics, '
    'inheritance across libraries, implicit receivers or visibility rules.',
    'Only symbols in com.android.purebilibili.*, com.bilipai.desktop.* and android.* are resolved by name; '
    'AndroidX and third-party imports are never checked; a star import is checked for its package and, for a known '
    'project package, for the names it supplies (library and unknown-package stars stay lenient).',
    'A declaration is found by simple name inside its package; extension receivers and members are matched by '
    'name only, so a same-named declaration on an unrelated type can hide a missing one.',
    'unresolved-name and unresolved-member run only on generated sources; their ambient-name list is learned from '
    'the vendored v0.2.5 corpus (capitalized and lowercase names) and the carried upstream archives (lowercase '
    'names only, and never a name some file imports from a project package), so a library name that corpus never '
    'uses unbound can be reported falsely.',
    'A lowercase name that no scanned file imports (and that is not a local of another declaration) is never '
    'reported as unresolved-name, so a generated file that bare-uses a missing lowercase project helper nothing '
    'imports can pass; a lowercase name an archive uses unbound is ambient unless some scanned file imports it from '
    'a project package. A missing capitalized project symbol is reported unless a library star import could '
    'supply it.',
    'missing-copied-import pairs a generated declaration with an original by name and identifier similarity; a '
    'rewritten declaration below the similarity threshold is not compared.',
    'Producers that need an unavailable toolchain (JS worker JDK, MSVC diagnostic DLL) are not run; their few '
    'declarations are listed as synthetic definers in the expected-gaps file.',
    'Carried (archive) and candidate findings are snapshots of upstream references: verbatim v0.2.9-v0.4.6 files '
    'that producers adapt or never emit; Android generated classes (R, BuildConfig) and android.* imports are '
    'ignored there.',
    'A pass is evidence of nothing but the absence of these classes; it is not a compile.',
)


def empty_expected():
    return {'gaps': [], 'latent': [], 'retired': [], 'synthetic': {}}


def load_expected(path):
    """Read the seedable expected-gaps list. A missing path means "no expected gaps"."""
    if path is None:
        return empty_expected()
    path = Path(path)
    if not path.is_file():
        raise ToolError('Expected-gaps file not found: %s' % path)
    try:
        data = json.loads(path.read_bytes().decode('utf8'))
    except ValueError as error:
        raise ToolError('Expected-gaps file is not valid JSON: %s' % error)
    if data.get('schemaVersion') != 1:
        raise ToolError('Expected-gaps schemaVersion must be 1')
    result = empty_expected()
    for index, group in enumerate(data.get('groups', [])):
        reason = group.get('reason')
        closed_by = group.get('closedBy')
        if not reason or not closed_by:
            raise ToolError('Expected-gaps group %d needs a reason and closedBy' % index)
        for gap in group.get('gaps', []):
            if gap.get('scope') not in KNOWN_SCOPES or gap.get('check') not in KNOWN_CHECKS or not gap.get('symbol'):
                raise ToolError('Malformed expected gap in group %d: %r' % (index, gap))
            entry = dict(gap)
            entry['reason'] = gap.get('reason') or reason
            entry['closedBy'] = gap.get('closedBy') or closed_by
            evidence = gap.get('closedByEvidence') or group.get('closedByEvidence')
            if evidence is not None:
                if evidence not in KNOWN_EVIDENCE:
                    raise ToolError('Expected-gaps group %d: closedByEvidence must be one of %s, not %r' %
                                    (index, ', '.join(KNOWN_EVIDENCE), evidence))
                entry['closedByEvidence'] = evidence
            result['gaps'].append(entry)
    for latent in data.get('latent', []):
        if not latent.get('symbol') or not latent.get('reason') or not latent.get('closedBy') \
                or latent.get('kind', 'declaration') not in ('declaration', 'member', 'identifier') \
                or not latent.get('evidence'):
            raise ToolError('Malformed latent gap: %r' % latent)
        if latent.get('closedByEvidence') is not None and latent['closedByEvidence'] not in KNOWN_EVIDENCE:
            raise ToolError('Malformed latent gap (closedByEvidence): %r' % latent)
        result['latent'].append(dict(latent))
    for retired in data.get('retiredSeeds', []):
        if not all(retired.get(key) for key in ('symbol', 'reason', 'retiredBy', 'guardedBy', 'closedBy', 'evidence')) \
                or retired.get('kind', 'declaration') not in ('declaration', 'member', 'identifier') \
                or (retired.get('closedByEvidence') is not None and retired['closedByEvidence'] not in KNOWN_EVIDENCE):
            raise ToolError('Malformed retired seed: %r' % retired)
        result['retired'].append(dict(retired))
    for synthetic in data.get('syntheticDefiners', []):
        if not synthetic.get('package') or not synthetic.get('names') or not synthetic.get('reason'):
            raise ToolError('Malformed synthetic definer: %r' % synthetic)
        result['synthetic'].setdefault(synthetic['package'], set()).update(synthetic['names'])
    keys = [(g['scope'], g['check'], g['symbol'], tuple(g.get('paths', ()))) for g in result['gaps']]
    if len(keys) != len(set(keys)):
        raise ToolError('Expected-gaps file lists a gap twice')
    return result


def scan_identifiers(parsed, names):
    """{name: first compiled path} for identifiers that occur in compiled sources (comments and strings ignored)."""
    found = {}
    if not names:
        return found
    pattern_any = re.compile(r'(?<!\w)(?:%s)(?!\w)' % '|'.join(re.escape(n) for n in sorted(names)))
    for source, info in parsed:
        if info.set not in ('main', 'test') or info.language != 'kotlin':
            continue
        text = read_source(source)
        if not any(n in text for n in names):
            continue
        for m in pattern_any.finditer(mask_kotlin(text.replace('\r\n', '\n'))):
            found.setdefault(m.group(), info.path)
    return found


def latent_defined(universe, entry):
    """True when the latent symbol now has a definer in the compiled sources (the entry is stale)."""
    visible = ('main', 'test')
    name = entry['symbol']
    owner = entry.get('owner')
    if entry.get('kind') == 'identifier':
        return name in universe.identifiers
    if owner:
        owners = [h for s in visible for h in universe.scopes[s].by_name.get(owner, ()) if h[1].classifier]
        for _, decl in owners:
            if name in decl.members:
                return True
        return universe.extension_exists(visible, name) and any(
            d.receiver.split('<')[0].rstrip('?') == owner for s in visible
            for _, d in universe.scopes[s].extensions.get(name, ()))
    package = entry.get('package')
    if package:
        return bool(universe.lookup(visible, package, name))
    return any(universe.scopes[s].by_name.get(name) for s in visible)


# --------------------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------------------

def validate_checks(checks):
    """The set of check groups to run. None selects all; an unknown name or an empty selection is a ToolError."""
    if checks is None:
        return set(CHECK_NAMES)
    selected = list(checks)
    unknown = sorted({c for c in selected if c not in CHECK_NAMES})
    if unknown or not selected:
        raise ToolError('Unknown or empty --checks selection %s; choose from: %s' % (
            ', '.join(repr(c) for c in unknown) if unknown else '(nothing)', ', '.join(CHECK_NAMES)))
    return set(selected)


def parse_checks(text):
    """--checks text -> validated group names (a stray empty element between commas is ignored)."""
    if text is None:
        return None
    return validate_checks([part.strip() for part in text.split(',') if part.strip()])


class Options:
    def __init__(self, names_scope='generated', carried=True, checks=None, extra_dirs=()):
        if names_scope not in NAMES_SCOPES:
            raise ToolError('Unknown names scope %r; choose from: %s' % (names_scope, ', '.join(NAMES_SCOPES)))
        self.names_scope = names_scope
        self.carried = carried
        self.checks = validate_checks(checks)
        self.extra_dirs = list(extra_dirs)


class Result:
    def __init__(self):
        self.findings = []
        self.unexpected = []
        self.known = []
        self.stale = []
        self.latent = []
        self.retired = []
        self.notes = []
        self.producers = {}
        self.counts = {}
        self.revision = None
        self.universe = None
        self.ambient = frozenset()   # the learned ambient names the unresolved-name check trusted (empty when it did not run)
        self.providers = {}          # simple name -> packages some scanned file imports it from (see build_providers)

    @property
    def passed(self):
        return not (self.unexpected or self.stale or any(x['status'] == 'defined' for x in self.latent)
                    or any(x['status'] == 'lost' for x in self.retired))


def _scope_name(set_name):
    return 'carried' if set_name == 'carried' else 'compiled'


def _is_test_path(path):
    return '/src/test/' in path or '/src/androidTest/' in path or '/src/debug/' in path


def source_directory_findings(notes, allowed_unproduced=()):
    """A registered Kotlin source directory that is absent is a finding, not a note.

    A producer that silently stops emitting a leaf directory (nothing imports from it) would otherwise leave the
    scan green while the declarations are gone. Only directories that belong to a producer this run deliberately
    did not start (UNRUNNABLE_TASKS, --skip-producers, --only-producers) may be missing; `allowed_unproduced` holds
    their build-relative output directories.
    """
    findings = []
    for note in notes:
        if note.startswith(NOT_PRODUCED_NOTE):
            relative = note[len(NOT_PRODUCED_NOTE):]
            if any(_under(relative, owned) or _under(owned, relative) for owned in allowed_unproduced):
                continue
            findings.append(Finding(
                'producer-output-missing', 'compiled', 'build/' + relative, 'desktop/build.gradle.kts', 0,
                'a Kotlin source directory registered in build.gradle.kts was not produced by any producer that ran; '
                'its declarations are missing from this scan'))
        elif note.startswith(MISSING_DIRECTORY_NOTE):
            findings.append(Finding(
                'producer-output-missing', 'compiled', 'desktop/' + note[len(MISSING_DIRECTORY_NOTE):],
                'desktop/build.gradle.kts', 0,
                'a Kotlin source directory registered in build.gradle.kts does not exist in the checkout'))
    return findings


def gap_observable(gap, options, producers_ran=True, producers_partial=False):
    """False when this run did not look for the kind of finding `gap` accepts, so not seeing it proves nothing.

    A partial run (--no-carried, --checks, --names-scope none, --mirror, --only-producers, --skip-producers) must
    not call the expected gaps of the parts it skipped stale. `producers_partial` is True when only or skip chose
    the producers: the generated sources of every producer left out are missing from the scan, so no compiled-scope
    gap can be judged. A carried gap stays observable: leaving a producer out only removes definers.
    """
    if gap['scope'] == 'carried' and not options.carried:
        return False
    if gap['scope'] == 'compiled' and producers_partial:
        return False
    check = gap['check']
    if check == 'producer-failed':
        return producers_ran
    group = CHECK_GROUP.get(check)
    if group is None:
        return True
    if group not in options.checks:
        return False
    return not (group in NAME_GROUPS and options.names_scope == 'none')


def analyze(sources, notes, expected, options, log=None, extra_findings=(), producers_ran=True, allowed_unproduced=(),
            producers_partial=False):
    """Run every enabled check over `sources`; return findings classified against `expected`.

    `producers_ran` is False for a re-scan of a finished mirror (no producer exit status exists, so a listed
    producer-failed gap cannot be judged). `allowed_unproduced` is documented at source_directory_findings.
    `producers_partial` is True when --only-producers or --skip-producers chose the producers: compiled-scope gaps
    and retired seeds then cannot be judged (see gap_observable and classify).
    """
    say = log or (lambda text: None)
    universe = Universe()
    for package, names in expected['synthetic'].items():
        universe.synthetic[package].update(names)
    parsed = []
    by_path = {}
    for source in sources:
        info = analyze_source(source)
        universe.add(info)
        parsed.append((source, info))
        by_path[info.path] = source
    say('  parsed %d source files' % len(parsed))
    providers = build_providers([info for _, info in parsed])
    findings = []
    enabled = options.checks
    scanned = [(source, info) for source, info in parsed
               if info.set in ('main', 'test')
               or (info.set == 'carried' and (options.carried or source.origin == 'candidate'))]
    if 'duplicates' in enabled:
        findings += check_duplicates(universe)
    if 'imports' in enabled:
        findings += check_imports(universe, [info for _, info in scanned])
    if 'qualified' in enabled:
        for source, info in scanned:
            if info.language == 'kotlin':
                masked = mask_kotlin(read_source(source).replace('\r\n', '\n'))
                findings += check_qualified(universe, info.set, info.path, masked)
    say('  imports and qualified references checked')
    name_checks = {c for c in ('copied', 'names', 'members') if c in enabled}
    ambient = set()
    if name_checks and options.names_scope != 'none':
        origins = ('generated',) if options.names_scope == 'generated' else ('generated', 'desktop', 'third-party')
        subjects = [(s, i) for s, i in parsed if i.set in ('main', 'test') and s.origin in origins and i.language == 'kotlin']
        if 'names' in name_checks:
            corpus = [s for s, i in parsed
                      if s.origin in ('vendored', 'vendored-other', 'archive') and not _is_test_path(s.logical)]
            ambient = learn_ambient(universe, corpus, providers)
            say('  learned %d ambient names from %d corpus files' % (len(ambient), len(corpus)))
        originals = defaultdict(list)
        if 'copied' in name_checks:
            for source, info in parsed:
                if source.origin in ('archive', 'vendored', 'vendored-other') and info.language == 'kotlin':
                    for d in info.decls:
                        originals[('type' if d.classifier else d.kind, d.name)].append((info, d))
        cache = OriginalCache(by_path)
        for source, info in subjects:
            text = read_source(source)
            tokenized = parse_kotlin(text, info.path, info.origin, info.set, keep_tokens=True)
            ctx = FileContext(universe, tokenized, providers)
            findings += check_names(universe, tokenized, ctx, ambient, originals, cache, name_checks)
        say('  name-level checks ran over %d generated files' % len(subjects))
    findings += list(extra_findings)
    findings += source_directory_findings(notes, allowed_unproduced)
    for f in findings:
        f.scope = _scope_name(f.scope)
    universe.identifiers = scan_identifiers(parsed, {e['symbol'] for e in expected['latent'] + expected['retired']
                                                    if e.get('kind') == 'identifier'})
    result = Result()
    result.ambient = frozenset(ambient)
    result.providers = providers
    result.findings = sorted(findings, key=Finding.sort_key)
    result.notes = sorted(notes)
    counts = Counter()
    for source, info in parsed:
        counts['%s/%s' % (info.set, source.origin)] += 1
    result.counts = dict(sorted(counts.items()))
    classify(result, expected, universe, options, producers_ran, producers_partial)
    result.universe = universe
    return result


def classify(result, expected, universe, options=None, producers_ran=True, producers_partial=False):
    groups = defaultdict(list)
    for f in result.findings:
        groups[f.key()].append(f)
    used = [0] * len(expected['gaps'])
    for key in sorted(groups):
        remaining = []
        for f in groups[key]:
            matched = False
            for index, gap in enumerate(expected['gaps']):
                if (gap['scope'], gap['check'], gap['symbol']) != key:
                    continue
                if 'paths' in gap and not any(fnmatch.fnmatch(f.file, p) for p in gap['paths']):
                    continue
                used[index] += 1
                f.gap = gap
                matched = True
                break
            (result.known if matched else remaining).append(f)
        result.unexpected.extend(remaining)
    for index, gap in enumerate(expected['gaps']):
        if not used[index] and (options is None or gap_observable(gap, options, producers_ran, producers_partial)):
            result.stale.append(gap)
    for entry in expected['latent']:
        status = 'defined' if latent_defined(universe, entry) else 'open'
        result.latent.append(dict(entry, status=status))
    for entry in expected['retired']:
        # a retired seed must keep its definer; one that lost it is an open gap again. When --only-producers or
        # --skip-producers left the producer of the definer out, its absence proves nothing: the seed is unjudged.
        if latent_defined(universe, entry):
            status = 'defined'
        else:
            status = 'unjudged' if producers_partial else 'lost'
        result.retired.append(dict(entry, status=status))


# --------------------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------------------

def _group(findings):
    groups = defaultdict(list)
    for f in findings:
        groups[f.key()].append(f)
    return [(key, groups[key]) for key in sorted(groups)]


def render_text(result, producers=None, header=None):
    out = []
    out.append('SYMBOL CLOSURE GATE (static scan; nothing was compiled and no JVM was started)')
    if header:
        out.extend(header)
    if result.revision:
        out.append('mirror of: %s' % result.revision)
    if producers is not None:
        failed = [p['task'] for p in producers['results'] if p['returncode']]
        out.append('producers: %d run, %d failed; %d skipped' % (len(producers['results']), len(failed),
                                                                 len(producers['skipped'])))
        for name, reason in producers['skipped']:
            out.append('  skipped %s: %s' % (name, reason))
    out.append('files: ' + ', '.join('%s=%d' % (k, v) for k, v in result.counts.items()))
    for note in result.notes:
        out.append('note: ' + note)
    out.append('heuristic limits:')
    for limit in HEURISTIC_LIMITS:
        out.append('  - ' + limit)
    out.append('')
    out.append('RESULT: ' + ('PASS' if result.passed else 'FAIL'))
    out.append('unexpected findings: %d in %d groups; known gaps matched: %d in %d groups; stale expected gaps: %d; '
               'latent open/defined: %d/%d' % (
                   len(result.unexpected), len(_group(result.unexpected)), len(result.known),
                   len(_group(result.known)), len(result.stale),
                   sum(1 for x in result.latent if x['status'] == 'open'),
                   sum(1 for x in result.latent if x['status'] == 'defined')) +
               ('; retired defined/lost: %d/%d' % (sum(1 for x in result.retired if x['status'] == 'defined'),
                                                  sum(1 for x in result.retired if x['status'] == 'lost')) +
                ('; unjudged: %d' % sum(1 for x in result.retired if x['status'] == 'unjudged')
                 if any(x['status'] == 'unjudged' for x in result.retired) else '')
                if result.retired else ''))
    by_check = Counter(f.check for f in result.unexpected)
    if by_check:
        out.append('unexpected by check: ' + ', '.join('%s=%d' % kv for kv in sorted(by_check.items())))

    def section(title, findings, limit=4):
        out.append('')
        out.append('%s (%d)' % (title, len(findings)))
        for (scope, check, symbol), group in _group(findings):
            out.append('  [%s] %s  (%s, %d location%s)' % (check, symbol, scope, len(group), '' if len(group) == 1 else 's'))
            out.append('      ' + group[0].detail)
            for f in group[:limit]:
                out.append('      %s:%d' % (f.file, f.line))
            if len(group) > limit:
                out.append('      ... %d more' % (len(group) - limit))

    if result.unexpected:
        section('UNEXPECTED FINDINGS', result.unexpected)
    if result.known:
        out.append('')
        out.append('KNOWN GAPS (listed in the expected-gaps file)')
        for (scope, check, symbol), group in _group(result.known):
            out.append('  [%s] %s  (%s, %d location%s; closedBy %s)' % (
                check, symbol, scope, len(group), '' if len(group) == 1 else 's', _owner(group[0].gap)))
    if result.stale:
        out.append('')
        out.append('STALE EXPECTED GAPS (listed but no longer observed; remove them) (%d)' % len(result.stale))
        for gap in result.stale:
            out.append('  [%s] %s  (%s, closedBy %s)' % (gap['check'], gap['symbol'], gap['scope'], _owner(gap)))
    if result.latent:
        out.append('')
        out.append('LATENT SYMBOLS (needed by planned slices; no definer expected yet)')
        for entry in result.latent:
            out.append('  %s %s  (closedBy %s)' % ('OPEN   ' if entry['status'] == 'open' else 'DEFINED', _target(entry),
                                                  _owner(entry)))
    if result.retired:
        out.append('')
        out.append('RETIRED SEEDS (once open; a definer exists now and the gate fails if it is lost)')
        for entry in result.retired:
            out.append('  %s %s  (retiredBy %s)' % (RETIRED_LABEL[entry['status']], _target(entry), entry['retiredBy']))
    return '\n'.join(out) + '\n'


RETIRED_LABEL = {'defined': 'DEFINED', 'lost': 'LOST   ', 'unjudged': 'UNJUDGED'}


def _owner(entry):
    """closedBy as printed: a slice the gap's author only proposed is marked so its owner confirms or reassigns it."""
    return entry['closedBy'] + (' (proposed)' if entry.get('closedByEvidence') == 'proposed' else '')


def _target(entry):
    if entry.get('owner'):
        return entry['owner'] + '.' + entry['symbol']
    return (entry['package'] + '.' if entry.get('package') else '') + entry['symbol']


def result_to_dict(result, producers=None):
    def owner(entry):
        found = {'closedBy': entry['closedBy']}
        if entry.get('closedByEvidence'):
            found['closedByEvidence'] = entry['closedByEvidence']
        return found

    def pack(findings):
        return [dict(check=k[1], scope=k[0], symbol=k[2],
                     locations=['%s:%d' % (f.file, f.line) for f in group], detail=group[0].detail,
                     **(owner(group[0].gap) if group[0].gap else {}))
                for k, group in _group(findings)]
    return dict(result='pass' if result.passed else 'fail', counts=result.counts, notes=result.notes,
                unexpected=pack(result.unexpected), known=pack(result.known),
                stale=[dict(check=g['check'], symbol=g['symbol'], scope=g['scope'], **owner(g))
                       for g in result.stale],
                latent=[dict(symbol=x['symbol'], owner=x.get('owner'), package=x.get('package'),
                             status=x['status'], **owner(x)) for x in result.latent],
                retired=[dict(symbol=x['symbol'], owner=x.get('owner'), package=x.get('package'),
                              status=x['status'], retiredBy=x['retiredBy'], **owner(x)) for x in result.retired],
                producers=None if producers is None else dict(
                    results=[dict(task=p['task'], returncode=p['returncode']) for p in producers['results']],
                    skipped=producers['skipped']),
                limits=list(HEURISTIC_LIMITS))


# --------------------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------------------

def producer_failures(results):
    return [Finding('producer-failed', 'compiled', r['task'], 'desktop/' + (r['tool'] or ''), 0,
                    'producer exited with %d: %s' % (r['returncode'], (r['stderrTail'] or ['no output'])[-1].strip()))
            for r in results if r['returncode']]


def not_run_outputs(model, tasks, skipped, only=None, results=()):
    """Build-relative output directories of the producers that did not deliver this run.

    Those are the unrunnable toolchain producers, anything --skip-producers named, (with --only-producers) every
    producer that was not selected, and every producer that ran and failed: a failed producer is already a
    producer-failed finding, so its missing directory is not reported a second time. A registered source directory
    under one of them may be absent.
    """
    names = {name for name, _ in skipped}
    if only is not None:
        names |= set(model.tasks) - {task.name for task in tasks}
    names |= {r['task'] for r in results if r['returncode']}
    owned = {value for name in names if name in model.tasks for kind, value in model.tasks[name].args if kind == 'build'}
    for name in names:
        owned.update(UNRUNNABLE_OUTPUTS.get(name, ()))
    return sorted(owned)


def check_producer_names(model, only, skip):
    """A producer name that is no Gradle task would select nothing (or skip nothing) and look like a clean run."""
    known = set(model.tasks)
    for option, names in (('--only-producers', only), ('--skip-producers', skip)):
        unknown = sorted(set(names or ()) - known)
        if unknown:
            raise ToolError('%s names no task of desktop/build.gradle.kts: %s' % (option, ', '.join(unknown)))


def run_gate(repo, revision=None, scratch=None, keep_scratch=False, mirror=None, only=None, skip=None,
             expected_path='default', options=None, log=None, candidate_paths=(), candidate_commit=TARGET_COMMIT):
    """Mirror, produce, scan, classify. Returns (Result, producers info or None).

    Raises ToolError for anything that stops the gate from judging at all (a bad --mirror, --revision, scratch
    directory or producer name); the command line maps that to exit 2, never to the findings exit 1.
    """
    say = log or (lambda text: None)
    repo = Path(repo)
    options = options or Options()
    if expected_path == 'default':
        candidate = repo / EXPECTED_GAPS_FILE
        expected_path = candidate if (revision is None and candidate.is_file()) else None
    expected = load_expected(expected_path)
    cleanup = None
    candidate_dir = None
    producers = None
    try:
        only_names = set(only) if only else None
        skip_names = set(skip) if skip else None
        if mirror is None:
            if scratch:
                parent = Path(scratch)
                check_scratch_root(parent / 'r')
                if os.path.lexists(_long(parent / 'r')):
                    raise ToolError('%s already contains "r" (a kept mirror?); remove it or pass another --scratch '
                                    'directory' % parent)
                existed = os.path.isdir(_long(parent))
                parent.mkdir(parents=True, exist_ok=True)
            else:
                parent = Path(tempfile.mkdtemp(prefix='bp-sc-'))
                existed = False
            # only what this run created is removed: a --scratch directory that already held other things keeps them
            cleanup = None if keep_scratch else (parent / 'r' if existed else parent)
            say('mirroring %s into %s' % ('revision ' + revision if revision else 'the working tree', parent))
            root, env = make_scratch_repo(repo, parent, revision)
            model = parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf8'))
            check_producer_names(model, only_names, skip_names)
            tasks, skipped = select_producers(model, only=only_names, skip=skip_names)
            say('running %d producers (%d skipped)' % (len(tasks), len(skipped)))
            results = run_producers(root, tasks, env, log=say)
            producers = dict(results=results, skipped=skipped)
        else:
            root = Path(mirror)
            if not (root / 'desktop/build.gradle.kts').is_file():
                raise ToolError('--mirror %s is not a mirror root: desktop/build.gradle.kts not found (pass the "r" '
                                'directory a --keep-scratch run kept)' % root)
            model = parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf8'))
            check_producer_names(model, only_names, skip_names)
            tasks, skipped = select_producers(model, only=only_names, skip=skip_names)
        allowed_unproduced = not_run_outputs(model, tasks, skipped, only_names, producers['results'] if producers else ())
        sources, notes = collect_sources(root, model, options.extra_dirs)
        if candidate_paths:
            candidate_dir = Path(tempfile.mkdtemp(prefix='bp-cand-'))
            sources += materialize_candidates(repo, candidate_commit, candidate_paths, candidate_dir)
        say('scanning %d sources' % len(sources))
        extra = producer_failures(producers['results']) if producers else []
        result = analyze(sources, notes, expected, options, log=say, extra_findings=extra,
                         producers_ran=producers is not None, allowed_unproduced=allowed_unproduced,
                         producers_partial=bool(only_names or skip_names))
        result.revision = revision
        return result, producers
    finally:
        for folder in (cleanup, candidate_dir):
            if folder is not None:
                _remove_tree(folder)


def _task_names(text):
    """Comma separated Gradle task names, stripped; None when the option was not given or names nothing."""
    names = [name.strip() for name in text.split(',') if name.strip()] if text else []
    return names or None


def main(argv=None):
    parser = argparse.ArgumentParser(
        description='Static symbol-closure gate for generated and desktop-owned Kotlin (no compiler, no JVM). '
                    'Producers run in a throw-away mirror; the checkout is never written.')
    parser.add_argument('--repo', default=None, help='repository root (default: this checkout)')
    parser.add_argument('--revision', default=None,
                        help='mirror `git archive <revision>` instead of the working tree (history calibration)')
    parser.add_argument('--scratch', default=None,
                        help='directory that receives the mirror (default: new temp dir); on Windows its path must '
                             'stay short, at most %d characters including the mirror directory, because the producers '
                             'write long paths below it' % MAX_SCRATCH_ROOT)
    parser.add_argument('--keep-scratch', action='store_true', help='keep the mirror for inspection or --mirror reuse')
    parser.add_argument('--mirror', default=None, help='reuse a finished mirror root (skips the copy and the producers)')
    parser.add_argument('--only-producers', default=None,
                        help='comma separated Gradle task names (development aid; the generated sources of every '
                             'other producer are missing, so compiled-scope expected gaps and retired seeds are not '
                             'judged and unexpected findings are likely)')
    parser.add_argument('--skip-producers', default=None,
                        help='comma separated Gradle task names (same caveats as --only-producers)')
    parser.add_argument('--expected-gaps', default='default',
                        help='expected-gaps JSON (default: %s of a working-tree run; "none" disables)' % EXPECTED_GAPS_FILE)
    parser.add_argument('--no-carried', action='store_true', help='do not scan the carried upstream slice archives')
    parser.add_argument('--names-scope', choices=CLI_NAMES_SCOPES, default='generated',
                        help='which sources the name-level checks (copied import, name, member) inspect: generated '
                             'sources (default) or none (desktop-owned code is not offered: its names reach the JDK '
                             'and libraries the scan cannot resolve)')
    parser.add_argument('--checks', default=None,
                        help='comma separated subset of: %s (default: all; an unknown or empty selection is an '
                             'error)' % ','.join(CHECK_NAMES))
    parser.add_argument('--extra-source-dir', action='append', default=[],
                        help='additional generated Kotlin directory to treat as main sources (repeatable)')
    parser.add_argument('--candidate-path', action='append', default=[],
                        help='planned upstream .kt file (repository path at --candidate-commit); checked like a carried '
                             'file to show what it needs that this tree lacks (repeatable)')
    parser.add_argument('--candidate-commit', default=TARGET_COMMIT,
                        help='upstream commit the candidate files are read from (default: v0.4.6)')
    parser.add_argument('--json', default=None, help='also write the machine readable report here')
    parser.add_argument('--quiet', action='store_true', help='no progress lines on stderr')
    parser.add_argument('--list-producers', action='store_true', help='print the producers that would run and stop')
    args = parser.parse_args(argv)
    repo = Path(args.repo) if args.repo else Path(__file__).resolve().parents[2]

    def log(text):
        if not args.quiet:
            sys.stderr.write(text + '\n')
            sys.stderr.flush()

    try:
        if args.list_producers:
            model = parse_gradle((repo / 'desktop/build.gradle.kts').read_text(encoding='utf8'))
            tasks, skipped = select_producers(model)
            for task in tasks:
                sys.stdout.write('%s %s\n' % (task.name, task.tool))
            for name, reason in skipped:
                sys.stdout.write('skipped %s: %s\n' % (name, reason))
            return 0
        options = Options(args.names_scope, not args.no_carried, parse_checks(args.checks), args.extra_source_dir)
        expected_path = None if args.expected_gaps == 'none' else args.expected_gaps
        result, producers = run_gate(
            repo, args.revision, args.scratch, args.keep_scratch, args.mirror,
            _task_names(args.only_producers), _task_names(args.skip_producers),
            expected_path, options, log, args.candidate_path, args.candidate_commit)
        text = render_text(result, producers)
        sys.stdout.buffer.write(text.encode('utf8'))
        sys.stdout.flush()
        if args.json:
            Path(args.json).write_bytes((json.dumps(result_to_dict(result, producers), indent=2, ensure_ascii=False,
                                                    sort_keys=True) + '\n').encode('utf8'))
        return 0 if result.passed else 1
    except ToolError as error:
        sys.stderr.write('ERROR: %s\n' % error)
        return 2
    except (OSError, subprocess.SubprocessError) as error:
        # a missing file, an unwritable --json path, a git or producer process that could not run: the gate could not
        # judge, which is exit 2 like any other tool error, never the exit 1 of findings that a traceback would give
        message = str(error).splitlines()[0] if str(error) else ''
        sys.stderr.write('ERROR: %s: %s\n' % (type(error).__name__, message))
        return 2


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Pinned v0.2.3 original History navigation and article-policy selections.
Full CommonListScreen/ViewModel/protocols remain the existing Favorites producer.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, os, re, textwrap
PIN = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
SOURCE_SHA = {
    'navigation/AppNavigation': '729021fb73c3ec4aa2aedb0d4de5706c3d72c43928f6b5f0a8da4e80c693ccca',
    'feature/search/SearchArticleNavigationPolicy': '98daed344df34c2f5a7ec69050a0959feec821445d63b7edbdd19b812ccb1495',
}

def safe(path):
    path = os.path.abspath(path)
    prefix = chr(92)*2 + '?' + chr(92)
    return Path(path if os.name != 'nt' or path.startswith(prefix) else prefix+path)

def generate(repo, output):
    repo, output = Path(repo).resolve(), Path(output).resolve()
    spec = importlib.util.spec_from_file_location('personal_source_parser', repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    emitted = []
    def source(relative):
        path = _desktop_canonical_source(repo, 'app/src/main/java/com/android/purebilibili/' + relative + '.kt')
        value = safe(path).read_text(encoding='utf-8').replace('\r\n','\n')
        if hashlib.sha256(value.encode('utf-8')).hexdigest() != SOURCE_SHA[relative]:
            raise ValueError(f'Pinned {PIN} source identity changed: {relative}')
        return value
    def emit(name, value):
        target = output/'com/bilipai/desktop/ui'/name
        safe(target).parent.mkdir(parents=True, exist_ok=True)
        safe(target).write_text(value, encoding='utf-8', newline='\n')
        emitted.append(target)
    s=source('navigation/AppNavigation')
    anchor=s.index('BiliPaiNavEntryContentRole.HISTORY ->')
    start=s.index('                                    onVideoClick = { lookupKey, cid, cover, isVertical ->',anchor)
    brace=s.index('{',start);end=parser.balanced(parser.masked(s),brace,'{','}')
    block=textwrap.dedent(s[brace:end]).strip()
    block=block.replace('pushNavigation3Route(', 'platform.pushRoute(').replace('pushNavigation3Key(', 'platform.push(').replace('navigateToVideoInNavigation3(', 'platform.video(').replace('historyNavigationScope.launch {','historyNavigationScope.launch {\n    if (!stillOwned()) return@launch').replace('resolveArticleNavigationTarget(articleId)','platform.articleTarget(articleId)')
    body = 'package com.bilipai.desktop.ui\nimport com.android.purebilibili.feature.list.*\nimport com.android.purebilibili.navigation.ScreenRoutes\nimport com.android.purebilibili.navigation3.*\nimport kotlinx.coroutines.*\nimport com.bilipai.desktop.ui.ArticleNavigationTarget\n\n/** Complete pinned AppNavigation HISTORY video callback. Only concrete Root calls/scope\n * are parameters; business dispatch, CID, resume and vertical rules remain original. */\ninternal fun desktopOriginalHistoryVideoClick(\n    historyViewModel: HistoryViewModel,\n    historyNavigationScope: CoroutineScope,\n    stillOwned: () -> Boolean,\n    platform: DesktopPersonalListNavigation,\n): (String, Long, String, Boolean) -> Unit {\n    val original: (String, Long, String, Boolean) -> Unit = ' + block + '\n    return { key, cid, cover, vertical ->\n        if (stillOwned()) original(key, cid, cover, vertical)\n    }\n}\n'
    emit('DesktopOriginalHistoryNavigation.kt',body)
    s=source('feature/search/SearchArticleNavigationPolicy')
    selected=[]
    for name in ['ArticleNavigationTarget','buildArticleWebUrl','resolveArticleNavigationTargetFromRedirect']:
     mask=parser.masked(s);m=re.search(r'(?m)^(?:internal )?(?:sealed interface|fun) '+name+r'\b',mask);assert m,name
     a=m.start();b=parser.balanced(mask,mask.index('{',m.end()),'{','}');selected.append(s[a:b])
    emit('DesktopOriginalHistoryArticlePolicy.kt','package com.bilipai.desktop.ui\n'+ '\n\n'.join(selected)+'\n')
    return emitted

if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path, required=True)
    args = cli.parse_args()
    for path in generate(args.repo, args.output):
        print(path.name)

"""Complete original Live sub-navigation pages; one retained Root owner and original DTOs.
No API/client/account/cache/preferences authority is constructed by this producer.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, textwrap
COMMIT = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
SOURCE_PINS = {'app/src/main/java/com/android/purebilibili/feature/live/LiveSearchScreen.kt': 'c391e12ba7e24a29cecf7c13d3593365029b2dbad19b8bb2a7ec48b8e28c0ba0', 'app/src/main/java/com/android/purebilibili/feature/live/LiveAreaScreen.kt': '09f5cb59863395387ca7b40805e30e633f1aa2f74bb593bc12104e35dd9b53f8', 'app/src/main/java/com/android/purebilibili/feature/live/LiveAreaDetailScreen.kt': 'e71df3f0eb772d756399a4f035e3a8477411f0ebf6bb6525142f45ba65b53cc7', 'app/src/main/java/com/android/purebilibili/feature/live/LiveFollowingScreen.kt': '75bc6e38c891944425834a188973a314abf4c4d24a7fbbf50c6d44560ad32b59', 'app/src/main/java/com/android/purebilibili/feature/live/LiveAreaScreenPolicy.kt': 'f76b8591ed458ab3d4a139a7a440611454eb9586f117cad6e2d348491d0e2fbf', 'core-data/src/main/java/com/android/purebilibili/data/repository/SearchRepository.kt': '4cec15c48b162a5987ae84ab962ed5e0958e89c33126f7c1f1fdbec322880ed0', 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt': '729021fb73c3ec4aa2aedb0d4de5706c3d72c43928f6b5f0a8da4e80c693ccca'}
BASE='app/src/main/java/com/android/purebilibili/'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
def generate(repo,output,standalone=False):
 repo=Path(repo);output=Path(output);records=[];adaptations=[]
 originals={p:read(_desktop_canonical_source(repo, p)) for p in SOURCE_PINS}
 for p,s in originals.items():assert sha(s)==SOURCE_PINS[p],p
 parser=load(repo/'desktop/tools/sync-upstream.py','live_navigation_parser')
 media=load(repo/'desktop/tools/extract-upstream-media.py','live_navigation_selector')
 def emit(p,s,path=None,mode='selected'):
  raw=originals[p];dst=path or 'com/android/purebilibili/'+p.removeprefix(BASE)
  write(output/dst,s);records.append(dict(source=p,output=dst,mode=mode,originalSha256LF=sha(raw),preparedSha256LF=sha(s),adapted=raw!=s))
 def change(p,s,before,after,count=1):
  assert s.count(before)==count,(p,before,s.count(before))
  positions=[match.start()+i*(len(after)-len(before)) for i,match in enumerate(re.finditer(re.escape(before),s))]
  adaptations.append(dict(source=p,before=before,after=after,count=count,positionsAfter=positions));return s.replace(before,after)
 def span(s,n):
  match=re.search(r'\b(?:suspend\s+)?fun\s+'+re.escape(n)+r'\s*\(',s)
  assert match,n;begin=s.rfind('\n',0,match.start())+1
  tokens=parser.kotlin_tokens(s);i=next(i for i,t in enumerate(tokens) if t[1]>=match.start())
  while tokens[i][0]!='(':i+=1
  depth=1
  while depth:
   i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
  while tokens[i][0]!='{':i+=1
  opening=tokens[i][1];depth=1
  while depth:
   i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
  return begin,opening,tokens[i][2]
 def wrap(p,s,n,slot,group,initial,finish,extra=''):
  begin,opening,end=span(s,n);old=s[opening+1:end-1];body=old
  body=body.replace('.onSuccess {','.onOwnedSuccess(request) {').replace('.onFailure {','.onOwnedFailure(request) {')
  assert initial in body,(n,initial)
  body=body.replace(initial,'        request.publish {\n'+initial+'\n        }',1)
  assert finish in body,(n,finish);body=body.replace(finish,'',1)
  # Initial no-op checks must precede request replacement admission.
  prefix=''
  if n=='submit':
   cut=body.index('        keyboard?.hide()');prefix=body[:cut];body=body[cut:]
  elif n in ['loadMoreLive','loadMoreUser']:
   cut=body.index('        request.publish');prefix=body[:cut];body=body[cut:]
  before=s[opening+1:end-1]
  after=prefix+'\n        val request = binding.begin('+repr(slot).replace("'",'"')+', '+('"'+group+'"' if group else 'null')+')\n'+extra+'        try {\n'+body+'\n        } finally { request.finish { '+finish.strip()+' } }\n    '
  return change(p,s,before,after)
 for name in ['LiveSearchScreen','LiveAreaScreen','LiveAreaDetailScreen','LiveFollowingScreen']:
  p=BASE+'feature/live/'+name+'.kt';s=originals[p]
  for line in ['import com.android.purebilibili.data.repository.LiveRepository\n','import com.android.purebilibili.data.repository.SearchRepository\n','import com.android.purebilibili.core.store.SettingsManager\n','import androidx.compose.ui.platform.LocalContext\n']:
   if line in s:s=change(p,s,line,'')
  anchor='import kotlinx.coroutines.launch'
  s=change(p,s,anchor,anchor+'\nimport com.bilipai.desktop.ui.LocalDesktopLiveNavigationBinding\nimport com.bilipai.desktop.ui.onOwnedSuccess\nimport com.bilipai.desktop.ui.onOwnedFailure')
  _,opening,_=span(s,name)
  s=s[:opening+1]+'\n    val binding = LocalDesktopLiveNavigationBinding.current'+s[opening+1:]
  adaptations.append(dict(source=p,before='',after='\n    val binding = LocalDesktopLiveNavigationBinding.current',count=1,insertAtFunction=name,positionsAfter=[opening+1]))
  s=change(p,s,'LiveRepository.','binding.requests.',s.count('LiveRepository.')) if 'LiveRepository.' in s else s
  s=change(p,s,'SearchRepository.','binding.requests.',s.count('SearchRepository.')) if 'SearchRepository.' in s else s
  if name=='LiveSearchScreen':
   s=wrap(p,s,'submit','search-submit','search', '''        hasSubmitted = true
        isLoading = true
        error = null
        activeKeyword = normalized
        liveResults.clear()
        userResults.clear()
        liveHasMore = false
        userHasMore = false
        liveNextPage = 1
        userNextPage = 1''','        isLoading = false')
   s=wrap(p,s,'loadMoreLive','search-live',None,'        liveLoadingMore = true','        liveLoadingMore = false', '        val requestedKeyword = activeKeyword\n        val requestedPage = liveNextPage\n')
   s=wrap(p,s,'loadMoreUser','search-user',None,'        userLoadingMore = true','        userLoadingMore = false', '        val requestedKeyword = activeKeyword\n        val requestedPage = userNextPage\n')
   s=change(p,s,'binding.requests.searchLive(activeKeyword, liveNextPage','binding.requests.searchLive(requestedKeyword, requestedPage')
   s=change(p,s,'binding.requests.searchUp(activeKeyword, userNextPage)','binding.requests.searchUp(requestedKeyword, requestedPage)')
   s=change(p,s,'        liveNextPage = 1\n        userNextPage = 1','        liveNextPage = 1\n        userNextPage = 1\n        liveLoadingMore = false\n        userLoadingMore = false')
   s=change(p,s,'                            if (it.isBlank()) {','                            if (it.isBlank()) {\n                                binding.invalidate("search")\n                                isLoading = false\n                                liveLoadingMore = false\n                                userLoadingMore = false')
  elif name=='LiveAreaScreen':
   s=change(p,s,'    val context = LocalContext.current\n','')
   s=change(p,s,'SettingsManager.getLiveFavoriteTags(context)','binding.preferences.favoriteTags')
   s=change(p,s,'SettingsManager.setLiveFavoriteTags(context, next)','binding.preferences.setLiveFavoriteTags(next)')
   s=change(p,s,'SettingsManager.setLiveFavoriteTags(\n                                context,','binding.preferences.setLiveFavoriteTags(')
   s=change(p,s,'    LaunchedEffect(reloadKey) {','    LaunchedEffect(reloadKey) {\n        val request = binding.begin("area-index", "area-index")\n        try {')
   s=change(p,s,'.onSuccess {','.onOwnedSuccess(request) {',s.count('.onSuccess {'))
   s=change(p,s,'.onFailure {','.onOwnedFailure(request) {',s.count('.onFailure {'))
   s=change(p,s,'    }\n\n    AppScaffold(', '        } finally { request.finish { isLoading = false } }\n    }\n\n    AppScaffold(')
  elif name=='LiveAreaDetailScreen':
   begin,opening,end=span(s,'loadPage');old=s[opening+1:end-1]
   body=old.replace('.onSuccess {','.onOwnedSuccess(request) {').replace('.onFailure {','.onOwnedFailure(request) {')
   body=body.replace('        if (reset) {','        request.publish {\n        if (reset) {',1)
   body=body.replace('            if (isLoadingMore || !hasMore) return\n','')
   body=body.replace('        val nextPage =','        }\n        val nextPage =',1)
   body=body.replace('sortType = sortType,','sortType = requestedSortType,')
   s=change(p,s,old,'\n        if (!reset && (isLoadingMore || !hasMore)) return\n        val request = binding.begin("area-page", if (reset) "area" else null)\n        val requestedSortType = sortType\n        try {\n'+body+'\n        } finally { request.finish { isLoading = false; isLoadingMore = false } }\n    ')
   s=change(p,s,'            totalCount = 0\n        } else {','            totalCount = 0\n            isLoadingMore = false\n        } else {')
   begin,opening,end=span(s,'loadSiblings');old=s[opening+1:end-1];body=old.replace('.onSuccess {','.onOwnedSuccess(request) {')
   s=change(p,s,old,'\n        val request = binding.begin("area-siblings")\n        try {\n'+body+'\n        } finally { request.finish {} }\n    ')
  else:
   # Original initial, refresh and append algorithms remain intact. One request family
   # rejects old refresh/load completion, including finally, under the same Root gate.
   s=change(p,s,'    LaunchedEffect(Unit) {','    LaunchedEffect(Unit) {\n        val request = binding.begin("following-initial", "following")\n        try {')
   s=change(p,s,'    }\n\n    AppScaffold(', '        } finally { request.finish { isLoading = false } }\n    }\n\n    AppScaffold(')
   s=change(p,s,'                            coroutineScope.launch {\n                                isRefreshing = true','                            coroutineScope.launch {\n                                val request = binding.begin("following-refresh", "following")\n                                try {\n                                request.publish { isRefreshing = true }')
   s=change(p,s,'                                isRefreshing = false','                                } finally { request.finish { isRefreshing = false; isLoadingMore = false } }')
   s=change(p,s,'                                    coroutineScope.launch {\n                                        isLoadingMore = true','                                    coroutineScope.launch {\n                                        val request = binding.begin("following-more")\n                                        val requestedPage = nextPage\n                                        try {\n                                        request.publish { isLoadingMore = true }')
   s=change(p,s,'page = nextPage)','page = requestedPage)')
   s=change(p,s,'                                        val request = binding.begin("following-more")','                                        if (isRefreshing || isLoadingMore || !hasMore) return@launch\n                                        val request = binding.begin("following-more")')
   s=change(p,s,'request.publish { isRefreshing = true }','request.publish { isRefreshing = true; isLoadingMore = false }')
   s=change(p,s,'request.publish { isRefreshing = true; isLoadingMore = false }\n                                error = null','request.publish { isRefreshing = true; isLoadingMore = false; error = null }')
   s=change(p,s,'                                        isLoadingMore = false','                                        } finally { request.finish { isLoadingMore = false } }')
   s=change(p,s,'.onSuccess {','.onOwnedSuccess(request) {',s.count('.onSuccess {'))
   s=change(p,s,'.onFailure {','.onOwnedFailure(request) {',s.count('.onFailure {'))
  emit(p,s)
 p=BASE+'feature/live/LiveAreaScreenPolicy.kt'
 if standalone:emit(p,originals[p],mode='direct')
 p='core-data/src/main/java/com/android/purebilibili/data/repository/SearchRepository.kt';raw=originals[p];bodies=[]
 for name in ['searchTypeParams','createPageInfo','createSearchError','searchLive','signWithWbi']:
  body=media.function(raw,name,parser);adapt=body
  # Canonical v025 already preserves cancellation in each original selected request.
  # App diagnostics retain the existing desktop Logger; no core-data runtime/client is created.
  adapt=adapt.replace('com.android.purebilibili.core.network.CoreDataLog.', 'com.android.purebilibili.core.util.Logger.')
  bodies.append(textwrap.indent(adapt,'    '));records.append(dict(source=p,declaration=name,originalBodySha256LF=sha(body),preparedBodySha256LF=sha(adapt)))
 header='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.SearchRepository.SearchPageInfo
import kotlinx.coroutines.*
internal class DesktopOriginalLiveSearchProtocol(private val api:SearchApi, private val navApi:BilibiliApi) {
    suspend fun signSearch(params:Map<String,String>):Map<String,String> = signWithWbi(params)
'''
 emit(p,header+'\n\n'.join(bodies)+'\n}\n','com/android/purebilibili/data/repository/DesktopOriginalLiveSearchProtocol.kt')
 write(output/'live-navigation-selection-proof.json',json.dumps(dict(pinnedCommit=COMMIT,selectedSources=records,exactAdaptations=adaptations),ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--output',required=True);p.add_argument('--standalone',action='store_true');a=p.parse_args();generate(a.repo,a.output,a.standalone)

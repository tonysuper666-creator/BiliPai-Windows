"""Select original storage UI, setters/defaults, cache confirmation and pure auto policy.
Actual Windows cache/download mutations are required platform owners, never Android directory scans.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,textwrap,sys
sys.dont_write_bytecode=True
BASE='app/src/main/java/com/android/purebilibili/'
PATHS=[BASE+p for p in ['feature/settings/ui/SettingsSections.kt','core/store/SettingsManager.kt','core/util/CacheUtils.kt','feature/settings/CacheClearUiPolicy.kt','feature/settings/ui/CacheClearAnimation.kt','feature/settings/screen/SettingsScreen.kt','core/store/SettingsPrefsCache.kt']]
VECTOR='app/src/main/res/drawable/ms_check_fill_24.xml'
def load(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf8',newline='\n')
def block(source,marker,parser):
 start=source.index(marker);tokens=parser.kotlin_tokens(source[start:]);opening=next(i for i,t in enumerate(tokens)if t[0]=='{');depth=1;end=opening
 while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
 return source[start:start+tokens[end][2]]
def generate(repo,out):
 parser=load('storage_tokens',repo/'desktop/tools/sync-upstream.py');media=load('storage_methods',repo/'desktop/tools/extract-upstream-media.py')
 raw={p:(_desktop_canonical_source(repo, p)).read_text(encoding='utf8').replace('\r\n','\n') for p in PATHS};sections,manager,cache,policy,animation,screen,mirrors=[raw[p]for p in PATHS]
 body=media.function(sections,'DataStorageSection',parser).replace('fun DataStorageSection(','internal fun DesktopOriginalDataStorageSection(')
 tokens=parser.kotlin_tokens(body);changes=[]
 for i,t in enumerate(tokens[:-1]):
  if t[0]=='SettingClickableItem' and tokens[i+1][0]=='(':
   depth=1;end=i+1
   while depth:end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
   start=t[1];stop=tokens[end][2];s=body[start:stop];changes.append((start,stop,re.sub(r'\bvalue = ','subtitle = ',s)))
 assert len(changes)==5
 for start,stop,s in reversed(changes):body=body[:start]+s+body[stop:]
 body=body.replace('painterResource(id = it)','rememberVectorPainter(DesktopSettingsVectors.vector(it))')
 write(out/'com/android/purebilibili/feature/settings/DesktopOriginalDataStorageSection.kt','''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.android.purebilibili.core.ui.components.AppSliderDialogPreference as SettingSliderItem
import com.android.purebilibili.core.store.DesktopOriginalStorageSettings as SettingsManager
import com.bilipai.desktop.settings.DesktopSettingsVectors
import kotlin.math.roundToInt
@Composable
'''+body+'\n')
 enum=block(manager,'enum class AutoCacheClearInterval',parser)
 names=['getDownloadPath','setDownloadPath','getDownloadExportTreeUri','setDownloadExportTreeUri','getDownloadPathSync','getDownloadExportTreeUriSync','getAutoCacheClearInterval','setAutoCacheClearInterval','getAutoCacheClearThresholdGb','setAutoCacheClearThresholdGb','getLastAutoCacheClearAt','setLastAutoCacheClearAt']
 methods=[]
 for n in names:
  if n=='getLastAutoCacheClearAt':
   match=re.search(r'suspend fun getLastAutoCacheClearAt\(context: Context\): Long =\n[^\n]+',manager);assert match;methods.append(match.group())
  else:methods.append(media.function(manager,n,parser))
 keys=sorted(set(re.findall(r'\bKEY_[A-Z_]+\b','\n'.join(methods))))
 declarations=[]
 for key in keys:
  matches=re.findall(r'private val '+key+r'\s*=\s*(?:int|string|long)PreferencesKey\("[^\"]+"\)',manager);assert len(matches)==1,key;declarations.append(matches[0])
 originalConst=re.search(r'const val DEFAULT_AUTO_CACHE_CLEAR_THRESHOLD_GB = \d+',manager).group()
 methods=[m.replace('editSettingsAndCommitPrefs','editStorageSettingsAndCommitPrefs')for m in methods]
 write(out/'com/android/purebilibili/core/store/DesktopOriginalStorageSettings.kt','''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import com.bilipai.desktop.ui.playerLongPreferencesKey as longPreferencesKey
import kotlinx.coroutines.flow.*
/** Original SettingsManager reads/keys/defaults; only the dual-write helper maps to one atomic Windows backing. */
internal object DesktopOriginalStorageSettings {
'''+textwrap.indent('\n'.join([originalConst,enum]+declarations+methods),'    ')+'\n}\n')
 enumCache=block(cache,'enum class CacheClearTarget',parser);auto=media.function(cache,'shouldAutomaticallyClearCache',parser);breakdown=block(cache,'data class CacheBreakdown',parser);formatter=media.function(cache,'formatSize',parser)
 write(out/'com/android/purebilibili/core/util/DesktopOriginalStorageCachePolicy.kt','''package com.android.purebilibili.core.util
import com.android.purebilibili.core.store.DesktopOriginalStorageSettings as SettingsManager
/** Original pure cache decisions/breakdown. Android Context file-walk and mutation code is never copied. */
'''+enumCache+'\n'+auto+'\nobject CacheUtils {\n'+textwrap.indent(breakdown+'\n'+formatter,'    ')+'\n}\n')
 # CacheClearUiPolicy is mode=direct: existing prepareUpstreamSources is its sole producer.
 # This original Compose dialog/animation is platform-neutral. Keep its complete confirmation interaction.
 animation=animation.replace(',\n            decorFitsSystemWindows = false','').replace('com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_check_fill_24)','com.bilipai.desktop.settings.DesktopStorageSettingsVectors.vector("ms_check_fill_24")')
 write(out/'com/android/purebilibili/feature/settings/CacheClearAnimation.kt',animation)
 vectorHelper=load('storage_vector',repo/'desktop/tools/extract-upstream-settings-search.py');vectorHelper.symbol_names=lambda unused:['ms_check_fill_24']
 vector=vectorHelper.vectors(repo).replace('DesktopSettingsSymbols','DesktopStorageSettingsSymbols').replace('DesktopSettingsVectors','DesktopStorageSettingsVectors')
 write(out/'com/bilipai/desktop/settings/DesktopStorageSettingsVectors.kt',vector)
 inventory=[dict(path=p,mode='direct' if p==PATHS[3]else'policy-extract',features=['desktop-storage-cache-settings-owner-parity'],sha256=hashlib.sha256(raw[p].encode()).hexdigest())for p in PATHS]
 inventory.append(dict(path=VECTOR,mode='direct',features=['desktop-storage-cache-settings-owner-parity'],sha256=hashlib.sha256((_desktop_canonical_source(repo, VECTOR)).read_bytes()).hexdigest()))
 write(out/'storage-source-inventory.json',json.dumps(inventory,ensure_ascii=False,indent=2)+'\n')
 return inventory
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--inventory',type=Path);a=p.parse_args();rows=generate(a.repo.resolve(),a.output.resolve())
 if a.inventory:write(a.inventory,json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
 print('Generated exact original storage families',len(rows))

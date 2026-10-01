from pathlib import Path
import json,re,textwrap,hashlib
HERE=Path(__file__).resolve().parent
exec((HERE/'prepare.py').read_text(encoding='utf-8').split('rows=json.loads',1)[0])
src=read(REPO/'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt')
def nested(name):
 tokens=parser.kotlin_tokens(src);found=[]
 for i,(token,start,end) in enumerate(tokens):
  if token not in ('fun','object'):continue
  if token=='fun':
   j=i+1
   while tokens[j][0]!='(':j+=1
   actual=tokens[j-1][0]
  else:actual=tokens[i+1][0]
  if actual!=name:continue
  j=i
  while tokens[j][0]!='{':j+=1
  depth=1;k=j+1
  while depth:
   depth+=(tokens[k][0]=='{')-(tokens[k][0]=='}');k+=1
  begin=src.rfind('\n',0,start)+1
  found.append(textwrap.dedent(src[begin:tokens[k-1][2]]))
 assert found,name
 return found[-1]
def keydefs(names):
 out=[]
 for name in sorted(names):
  m=re.search(r'(?m)^\s*(?:private |internal )?val '+re.escape(name)+r'\s*=\s*((?:boolean|int|float|string|stringSet)PreferencesKey\("[^"\n]+"\))',src)
  if m:out.append('private val '+name+' = '+m[1]);continue
  if name=='liquidGlassReadabilityModePreferencesKey':out.append('private val '+name+'=intPreferencesKey("liquid_glass_readability_mode")');continue
  if name=='bottomBarItemLabelsPreferencesKey':out.append('private val '+name+'=stringPreferencesKey("bottom_bar_item_labels")');continue
  if name=='miuixPredictiveBackMaxProgressPercentPreferencesKey':out.append('private val '+name+'=intPreferencesKey("miuix_predictive_back_max_progress_percent")');continue
  raise ValueError(name)
 return '\n'.join(out)
constants=['DEFAULT_TOP_TAB_ORDER','DEFAULT_TOP_TAB_VISIBLE','MAX_TOP_TABS','DEFAULT_BOTTOM_BAR_ORDER','DEFAULT_BOTTOM_BAR_VISIBLE_TABS']
lines=[]
for name in constants:
 m=re.search(r'(?m)^\s*(?:(?:private|internal) )?const val '+name+r' = [^\n]+',src);assert m,name
 lines.append(m[0].strip().replace('private const','const').replace('internal const','const'))
constbody='package com.bilipai.desktop.ui\ninternal object DesktopOriginalHomeSettingConstants {\n'+textwrap.indent('\n'.join(lines)+'\n'+nested('BottomBarLabelMode')+'\n'+nested('TopTabLabelMode'),'    ')+'\n}\n'
write(HERE/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalHomeSettingConstants.kt',constbody)
mapper=[]
for n,new in [('mapHomeSettingsFromPreferences','decodeDesktopOriginalHomeSettings'),('mapHomeTopTabSettingsFromPreferences','decodeDesktopOriginalHomeTopTabs'),('mapAppNavigationSettingsFromPreferences','decodeDesktopOriginalHomeNavigation')]:
 b=re.sub(r'\bPreferences\b','DesktopPreferenceSnapshot',nested(n).replace(n,new))
 b=b.replace('BottomBarVisibilityMode','DesktopFavoriteNavigationTypes.BottomBarVisibilityMode')
 b=b.replace('BottomBarLabelMode','DesktopOriginalHomeSettingConstants.BottomBarLabelMode').replace('TopTabLabelMode','DesktopOriginalHomeSettingConstants.TopTabLabelMode')
 for c in constants:b=re.sub(r'\b'+c+r'\b','DesktopOriginalHomeSettingConstants.'+c,b)
 mapper.append(b)
mapper+= [nested('normalizeBottomBarColorItemId'),nested('parseBottomBarItemColors'),nested('resolveOrderedVisibleBottomTabs')]
body='\n\n'.join(mapper)
keys=set(re.findall(r'preferences\[(\w+)\]',body))
header='''package com.bilipai.desktop.ui
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.core.store.navigation.parseBottomBarItemLabels
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.plugins.stringPreferencesKey
private val intPreferencesKey=::favoriteIntKey
private val floatPreferencesKey=::favoriteFloatKey
'''
write(HERE/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalHomeSettingsDecode.kt',header+keydefs(keys)+'\n\n'+body+'\n')
navpath='app/src/main/java/com/android/purebilibili/core/store/navigation/NavigationSettingsStore.kt'
s=read(REPO/navpath)
pick=['normalizeBottomBarLabelItemId','normalizeBottomBarCustomLabel','parseBottomBarItemLabels']
write(HERE/'prepared/generated/com/android/purebilibili/core/store/navigation/DesktopHomeNavigationLabels.kt','package com.android.purebilibili.core.store.navigation\n'+'\n\n'.join(d for n,d in declarations(s) if n in pick)+'\n')
pick=['BottomTabMigrationResult','resolveListenVideoBottomTabMigration']
write(HERE/'prepared/generated/com/android/purebilibili/core/store/DesktopHomeNavigationMigration.kt','package com.android.purebilibili.core.store\n'+'\n\n'.join(d for n,d in declarations(src) if n in pick)+'\n')
write(HERE/'settings-selection-evidence.json',json.dumps(dict(source='app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt',sha256LF=hashlib.sha256(src.encode()).hexdigest(),mappers=[dict(originalName=n,selectedBodySha256LF=hashlib.sha256(nested(n).encode()).hexdigest()) for n in ['mapHomeSettingsFromPreferences','mapHomeTopTabSettingsFromPreferences','mapAppNavigationSettingsFromPreferences']],keys=sorted(keys),constants=constants,namespace='settings',navigationLabelsSource=navpath,navigationLabelsSha256LF=hashlib.sha256(s.encode()).hexdigest(),changes=['Preferences readonly snapshot type', 'Root same-global typed keys', 'nested original enum namespace relocation only']),ensure_ascii=False,indent=2)+'\n')
print('original settings keys',len(keys))

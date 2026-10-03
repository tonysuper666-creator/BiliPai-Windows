from pathlib import Path
import hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=os.path.abspath(str(p));prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(s,encoding='utf8',newline='\n')
def load(name):return json.loads(wide(P/name).read_text(encoding='utf8'))
recipes=load('ui-recipes.json')['recipes']+[load('components-recipe.json'),load('screen-recipe.json')]
for r in recipes:
 output=wide(P/'generated'/r['output']).read_text(encoding='utf8')
 r['prefix']=output[:output.index('\n')+1]
pol=load('ui-policy-recipe.json');settings=load('settings-recipe.json')
settings['output']='com/android/purebilibili/core/store/DesktopOriginalBangumiPlayerUiSettings.kt'
sponsorPath='app/src/main/java/com/android/purebilibili/data/repository/SponsorBlockRepository.kt'
raw=subprocess.check_output(['git','-C',str(C),'show',UP+':'+sponsorPath]).decode('utf8').replace('\r\n','\n')
start=raw.index('    fun findSegmentAtPosition(');end=raw.index('\n    }',start)+len('\n    }\n')
body=raw[start:end];adapted=body.replace('    fun findSegmentAtPosition(', 'internal fun desktopOriginalBangumiFindSponsorSegment(')
sponsor=dict(originalPath=sponsorPath,originalSha256LF=sha(raw),startAnchor='    fun findSegmentAtPosition(',endAnchor='\n    }',exactBodySha256LF=sha(body),adaptedBodySha256LF=sha(adapted),output='com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiSponsorPolicy.kt')
write(P/'generated'/sponsor['output'],'package com.android.purebilibili.feature.bangumi\nimport com.android.purebilibili.data.model.response.SponsorSegment\n'+adapted)
write(P/'sponsor-recipe.json',json.dumps(sponsor,ensure_ascii=False,indent=2)+'\n')
template='''#!/usr/bin/env python3
"""Sole full v0.2.3 PGC Player UI producer, explicit same-native boundaries.
Every whole original file is hash-pinned, every edit is exact by source lines,
and complete reverse reconstruction is checked before writing generated output.
Selected policies/settings keep exact original bodies on canonical Root stores.
"""
import argparse,hashlib,json,os,re
from pathlib import Path
RECIPES=json.loads(RECIPE_LITERAL)
POLICY=json.loads(POLICY_LITERAL)
SETTINGS=json.loads(SETTINGS_LITERAL)
SPONSOR=json.loads(SPONSOR_LITERAL)
def wide(p):
 s=os.path.abspath(str(p));prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def read(repo,r):
 raw=wide(repo/r['originalPath']).read_text(encoding='utf8').replace('\\r\\n','\\n')
 assert sha(raw)==r['originalSha256LF'],r['originalPath'];return raw
def write(output,path,body):
 target=wide(output/path);target.parent.mkdir(parents=True,exist_ok=True);target.write_text(body,encoding='utf8',newline='\\n')
def full(raw,r):
 lines=raw.splitlines(keepends=True);parts=[];cursor=0;reversedParts=[]
 for edit in r['edits']:
  i,j=edit['startLine'],edit['endLineExclusive'];assert i>=cursor
  before=''.join(lines[i:j]);assert before==edit['before'] and sha(before)==edit['beforeSha256LF']
  unchanged=''.join(lines[cursor:i]);parts.extend([unchanged,edit['after']]);reversedParts.extend([unchanged,before]);cursor=j
 parts.append(''.join(lines[cursor:]));reversedParts.append(''.join(lines[cursor:]))
 assert ''.join(reversedParts)==raw
 adapted=''.join(parts);assert sha(adapted)==r['adaptedSha256LF'];return r['prefix']+adapted
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--repo',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);a=parser.parse_args()
 records=[]
 for r in RECIPES:
  raw=read(a.repo,r);body=full(raw,r);write(a.output,r['output'],body);records.append(dict(source=r['originalPath'],output=r['output'],wholeBody=True,edits=len(r['edits']),originalSha256LF=sha(raw),generatedSha256LF=sha(body)))
 raw=read(a.repo,POLICY);first=raw.index('internal fun resolveBangumiPlayerTopControlsPaddingTopDp(');last=raw.index('internal fun resolveBangumiFullscreen(')
 i=raw.index('internal fun resolveBangumiToggleOrientationTarget(');j=raw.index('internal data class BangumiEpisodePreviewWindow(')
 selected=raw[first:last]+raw[i:j];assert sha(selected)==POLICY['exactBodySha256LF']
 adapted=selected.replace('ActivityInfo.SCREEN_ORIENTATION_PORTRAIT','1 /* Original portrait intent; Windows adapter maps presentation */').replace('ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE','6 /* Original sensor-landscape intent; no physical rotation fabricated */')
 assert sha(adapted)==POLICY['adaptedBodySha256LF'];write(a.output,POLICY['output'],'package com.android.purebilibili.feature.bangumi\\n\\n'+adapted)
 raw=read(a.repo,SETTINGS);pieces=[]
 for name in SETTINGS['selectedNames']:
  pattern=(r'(?m)^    private val '+name+r' =[^\\n]+\\n')if name.startswith('KEY_')else(r'(?m)^    fun '+name+r'\\([^\\n]+\\n[^\\n]+\\n')
  found=re.search(pattern,raw);assert found,name;pieces.append(found.group())
 body='\\n'.join(pieces);assert sha(body)==SETTINGS['sha256LF']
 write(a.output,SETTINGS['output'],'package com.android.purebilibili.core.store\\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context\\nimport com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey\\nimport kotlinx.coroutines.flow.Flow\\nimport kotlinx.coroutines.flow.map\\ninternal object DesktopOriginalBangumiPlayerUiSettings {\\n'+body+'\\n}\\n')
 raw=read(a.repo,SPONSOR);i=raw.index(SPONSOR['startAnchor']);j=raw.index(SPONSOR['endAnchor'],i)+len('\\n    }\\n');body=raw[i:j];assert sha(body)==SPONSOR['exactBodySha256LF']
 adapted=body.replace('    fun findSegmentAtPosition(', 'internal fun desktopOriginalBangumiFindSponsorSegment(');assert sha(adapted)==SPONSOR['adaptedBodySha256LF']
 write(a.output,SPONSOR['output'],'package com.android.purebilibili.feature.bangumi\\nimport com.android.purebilibili.data.model.response.SponsorSegment\\n'+adapted)
 print('Produced 5 complete original Player UI bodies and 3 exact policy/settings closures')
if __name__=='__main__':main()
'''
for key,value in [('RECIPE_LITERAL',recipes),('POLICY_LITERAL',pol),('SETTINGS_LITERAL',settings),('SPONSOR_LITERAL',sponsor)]:template=template.replace(key,repr(json.dumps(value,ensure_ascii=False)))
write(P/'prepared/desktop/tools/extract-upstream-bangumi-player-ui.py',template)
print('Prepared sole UI producer for 8 outputs, complete bodies retain exact inverse')

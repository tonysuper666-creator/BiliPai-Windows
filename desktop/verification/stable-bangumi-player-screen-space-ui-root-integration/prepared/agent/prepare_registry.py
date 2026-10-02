from pathlib import Path
import hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';BASE='cd8317d54fff02c2d920e295397aef502333f69b';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';FEATURE='original-bangumi-player-ui'
def wide(p):
 s=os.path.abspath(str(p));prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p):return json.loads(wide(p).read_text(encoding='utf8'))
def get(ref,path):return subprocess.check_output(['git','-C',str(C),'show',ref+':'+path]).decode('utf8').replace('\r\n','\n')
manifestText=get(BASE,'desktop/upstream-sources.json');manifest=json.loads(manifestText);rows=manifest['sources'];original=json.loads(json.dumps(rows));resources=json.loads(json.dumps(manifest['resources']));byPath={r['path']:r for r in rows};assert len(byPath)==len(rows)
recipes=load(P/'ui-recipes.json')['recipes']+[load(P/'components-recipe.json'),load(P/'screen-recipe.json'),load(P/'ui-policy-recipe.json'),load(P/'settings-recipe.json'),load(P/'sponsor-recipe.json'),load(P/'raw-danmaku-boundary.json')]
delta=[]
for r in recipes:
 path=r['originalPath'];body=get(UP,path);assert sha(body)==r['originalSha256LF']
 if path in byPath:
  old=json.loads(json.dumps(byPath[path]));assert old['sha256']==sha(body) and FEATURE not in old['features'];new=json.loads(json.dumps(old));new['features'].append(FEATURE);byPath[path].update(new)
  delta.append(dict(path=path,operation='feature-union',before=old,after=new))
 else:
  new=dict(path=path,sha256=sha(body),features=[FEATURE],mode='extracted');rows.append(new);byPath[path]=new
  delta.append(dict(path=path,operation='add-original-identity',row=new))
added=sum(r['operation']=='add-original-identity'for r in delta);unions=len(delta)-added
assert manifest['resources']==resources
for old in original:
 current=byPath[old['path']]
 assert current['sha256']==old['sha256'] and current['mode']==old['mode']
 assert current['features']==old['features']or current['features']==old['features']+[FEATURE]
result=dict(base=BASE,path='desktop/upstream-sources.json',baseSha256LF=sha(manifestText),sourceDelta=delta,sourcesBefore=len(original),sourcesAfter=len(rows),newOriginalIdentities=added,existingFeatureUnions=unions,resourcesBefore=len(resources),resourcesAfter=len(resources),resourceDelta=[],newDependencies=[],wholeExistingFileOverwriteForbidden=True,fullFunctionalPlayerAccepted=False)
wide(P/'registry-delta.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
print('Registry semantic delta:',added,'new original identities',unions,'unions;',len(original),'->',len(rows),'resources unchanged',len(resources))

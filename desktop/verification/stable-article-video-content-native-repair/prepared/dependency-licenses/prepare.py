"""Four fixed Content dependencies only. Evidence read/copy; no product or build writes."""
from pathlib import Path
import concurrent.futures, hashlib, json, re, subprocess, urllib.request, zipfile
LANE=Path(__file__).resolve().parent
MAIN=LANE.parents[2]
WORK=MAIN.parent
def safe(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def digest(b): return hashlib.sha256(b).hexdigest()
def artifact(p):
    b=safe(p).read_bytes(); return {'path':str(p),'sha256Bytes':digest(b),'size':len(b)}
def put(relative,b):
    p=LANE/relative; safe(p).parent.mkdir(parents=True,exist_ok=True); safe(p).write_bytes(b); return artifact(p)
def js(relative,v):return put(relative,(json.dumps(v,indent=2,ensure_ascii=False)+'\n').encode())
def get(url):
    req=urllib.request.Request(url,headers={'User-Agent':'BiliPai-Windows-license-evidence','Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(req,timeout=35) as r:return r.read()
snapshot=MAIN/'desktop/.local/stable-product-snapshot-52'
assert digest(safe(snapshot/'manifest.json').read_bytes())=='1ea937e921f1d190b20018066509d1cb36051ea9f6eff54d974f7013794e532c'
assert digest(safe(snapshot/'ordered-runtime-cp.json').read_bytes())=='7690288dfb690dbdaedb8249050bb05a4af32826b37e6a17465970f4f14bf304'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text());assert len(cp)==101
deps=[('com.mohamedrejeb.richeditor','richeditor-compose-desktop','1.0.0-rc14','MohamedRejeb/Compose-Rich-Editor','v1.0.0-rc14','afc44f915e2eccab0a63823c2840fe3d52f56473'),
      ('com.mohamedrejeb.ksoup','ksoup-html-jvm','0.6.0','MohamedRejeb/Ksoup','v0.6.0','8481f11dd9c9fc8ef0f1488a1a1fd61205dfb13d'),
      ('com.mohamedrejeb.ksoup','ksoup-entities-jvm','0.6.0','MohamedRejeb/Ksoup','v0.6.0','8481f11dd9c9fc8ef0f1488a1a1fd61205dfb13d'),
      ('org.jetbrains','markdown-jvm','0.7.3','JetBrains/markdown',None,None)]
rows=[]
for group,name,version,repo,tag,commit in deps:
    expected_name=f'{name}-{version}.jar'
    matching=[r for r in cp if Path(r['path']).name==expected_name];assert len(matching)==1
    runtime=matching[0];jar=Path(runtime['path']);assert digest(safe(jar).read_bytes())==runtime['sha256Bytes']
    cache=WORK/'toolchain/gradle-home/caches/modules-2/files-2.1'/group/name/version
    if name.startswith('richeditor'):
        cache=MAIN/'desktop/.local/stable-video-player-page-parity/dependency-evidence'
    evidence=[]
    for suffix in ['.pom','.module']:
        found=list(safe(cache).rglob(name+'-'+version+suffix));assert len(found)==1,(name,suffix,found)
        b=found[0].read_bytes(); copied=put('evidence/'+name+'/'+found[0].name,b)
        evidence.append({'sourcePath':str(found[0]),'url':'https://repo.maven.apache.org/maven2/'+group.replace('.','/')+'/'+name+'/'+version+'/'+found[0].name,**copied})
    candidates=[]
    with zipfile.ZipFile(safe(jar)) as z:
        for n in z.namelist():
            if re.search(r'(^|/)(licen[cs]e|notice|copyright)(\.|/|$)',n,re.I):
                candidates.append({'entry':n,**put('prepared/licenses/'+name+'/jar/'+n,z.read(n))})
    rows.append({'coordinate':group+':'+name+':'+version,'runtimeJar':artifact(jar),'pomAndModule':evidence,
        'jarLicenseNoticeEntries':candidates,'repository':repo,'tag':tag,'commit':commit})
repositories={r['repository']:r for r in rows}
repo_results={}
for repo,row in repositories.items():
    commit=row['commit']
    if commit is None:
        refs=subprocess.run(['git','ls-remote','https://github.com/'+repo+'.git','HEAD'],capture_output=True,text=True,check=True).stdout
        commit=refs.split()[0];put('evidence/'+repo.replace('/','_')+'/head-ls-remote.txt',refs.encode())
    url='https://api.github.com/repos/'+repo+'/git/trees/'+commit+'?recursive=1'
    b=get(url);tree=json.loads(b);assert tree['truncated'] is False
    put('evidence/'+repo.replace('/','_')+'/tree.json',b)
    paths=[v for v in tree['tree'] if v['type']=='blob' and re.search(r'(^|/)(licen[cs]e|notice|copyright)(\.|$)',v['path'],re.I)]
    repo_results[repo]={'commit':commit,'sourceTreeUrl':url,'licenseNoticePaths':[v['path'] for v in paths],
        'versionAssociation':'release tag from ls-remote' if row['tag'] else 'POM names this repository; repository HEAD pinned for license evidence, not claimed artifact-source commit',
        'rawFiles':[]}
    for v in paths:
        rawurl='https://raw.githubusercontent.com/'+repo+'/'+commit+'/'+v['path']
        raw=get(rawurl)
        entry={'repositoryPath':v['path'],'url':rawurl,**put('prepared/licenses/'+repo.replace('/','_')+'/'+v['path'],raw)}
        repo_results[repo]['rawFiles'].append(entry)
js('inspection.json',{'scope':'four newly installed Content dependency JARs only; raw license material, no product writes','actualSnapshot':52,
    'manifest':artifact(snapshot/'manifest.json'),'orderedRuntimeCp':artifact(snapshot/'ordered-runtime-cp.json'),
    'dependencyEvidenceManifest':artifact(MAIN/'desktop/.local/stable-video-player-page-parity/dependency-evidence/dependency-manifest.json'),
    'dependencies':rows,'repositories':repo_results})
print(json.dumps({'dependencies':[{'coordinate':r['coordinate'],'jarLicenseNoticeEntries':[e['entry'] for e in r['jarLicenseNoticeEntries']]} for r in rows],
    'repositories':{k:{'commit':v['commit'],'paths':v['licenseNoticePaths']} for k,v in repo_results.items()}},indent=2))

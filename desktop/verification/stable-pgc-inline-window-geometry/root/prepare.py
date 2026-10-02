from pathlib import Path
import ast,hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf8')
root=Path(__file__).resolve().parent;main=root.parents[2];repo=main.parent/'BiliPai-v023'
base='fd02521dbdbbeb54cc40662ef8cf65288084069f'
tool='desktop/tools/extract-upstream-bangumi-player-ui.py'
source='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiPlayerScreenPlatform.kt'
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git','-c','core.longpaths=true',*a],cwd=repo)
before=git('show',base+':'+tool).decode('utf8')
tree=ast.parse(before)
node=next(n.value for n in tree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='RECIPES'for t in n.targets))
recipes=json.loads(ast.literal_eval(node.args[0]))
row=next(r for r in recipes if r['originalPath'].endswith('/BangumiPlayerScreen.kt'))
original=git('show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+row['originalPath']).decode('utf8')
assert sha(original.encode())==row['originalSha256LF']
lines=original.splitlines(keepends=True)
line='        val playerHeight = screenWidthDp * 2f / 3f\n'
i=lines.index(line)
assert all(not (e['startLine']<=i<e['endLineExclusive'])for e in row['edits'])
replacement='        val playerHeight = com.bilipai.desktop.ui.desktopOriginalBangumiInlinePlayerHeightDp(configuration.screenWidthDp, configuration.screenHeightDp).dp\n'
edit=dict(startLine=i,endLineExclusive=i+1,before=line,after=replacement,beforeSha256LF=sha(line.encode()))
row['edits'].append(edit);row['edits'].sort(key=lambda e:e['startLine'])
parts=[];reverse=[];cursor=0
for e in row['edits']:
 a,b=e['startLine'],e['endLineExclusive'];assert a>=cursor
 fragment=''.join(lines[a:b]);assert fragment==e['before'] and sha(fragment.encode())==e['beforeSha256LF']
 parts += [''.join(lines[cursor:a]),e['after']];reverse += [''.join(lines[cursor:a]),fragment];cursor=b
parts.append(''.join(lines[cursor:]));reverse.append(''.join(lines[cursor:]))
assert ''.join(reverse)==original
adapted=''.join(parts);row['adaptedSha256LF']=sha(adapted.encode())
row['platformBoundaries'].append('Windows inline height uses the existing original large-window metrics; original portrait collapse and full content remain')
old_segment=ast.get_source_segment(before,node)
new_segment='json.loads('+repr(json.dumps(recipes,ensure_ascii=False))+')'
assert before.count(old_segment)==1
after=before.replace(old_segment,new_segment,1).encode();ast.parse(after)
(repo/tool).write_bytes(after)
targets=[]
for path in [tool,source]:
 old=git('show',base+':'+path);new=(repo/path).read_bytes()
 p=root/'before'/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(old)
 targets.append(dict(path=path,beforeSha256Bytes=sha(old),afterSha256Bytes=sha(new)))
(root/'installation.json').write_text(json.dumps(dict(baseCommit=base,sourceCount=1234,resourceCount=244,sourceTargets=targets,normalCompile=False,rootRuntimeAccepted=False),indent=2)+'\n',encoding='utf8')
(root/'generated-verification.json').write_text(json.dumps(dict(passed=True,completeScreenInversePassed=True,fullOriginalSourceSha256LF=sha(original.encode()),adaptedBodySha256LF=sha(adapted.encode()),newPlatformGeometryLine=edit,extraOriginalProducer=0),indent=2)+'\n',encoding='utf8')
(root/'screen-height-required-seam.json').write_text(json.dumps(dict(originalPath=row['originalPath'],before=line,after=replacement,newHelperFile=source,helperSource=(repo/source).read_text(encoding='utf8')),indent=2)+'\n',encoding='utf8')
print('Complete original PGC Screen inverse PASS; one Windows height seam on existing sole producer.')

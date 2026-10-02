from pathlib import Path
import hashlib,json,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
BASE='6ac84c8036ed4c75e2944d92d1020566e283e983';FEATURE='independent-bangumi-pages'
def sha(s):return hashlib.sha256(s.encode('utf8')).hexdigest()
def read(rel):return subprocess.check_output(['git','-C',str(C),'show',BASE+':'+rel]).decode('utf8').replace('\r\n','\n')
def write_json(name,value):(P/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
inventory=json.loads((P/'producer-inventory.json').read_text(encoding='utf8'))
pins={r['originalPath']:r['originalSha256LF'] for r in inventory['emitted']}
s=(P/'produce_pages.py').read_text(encoding='utf8')
old=s[s.index('P=Path(__file__)'):s.index('spec=importlib.util.spec_from_file_location')]
replacement='''args=argparse.ArgumentParser(description="Complete original independent Bangumi Catalog/Detail/Timeline/Review bodies; Root owned platform bindings only.")
args.add_argument('--repo',type=Path,required=True);args.add_argument('--output',type=Path,required=True)
args.add_argument('--manifest',type=Path);options=args.parse_args()
C=options.repo.resolve();OUT=options.output.resolve();OUT.mkdir(parents=True,exist_ok=True);ROWS=[]
BASE='app/src/main/java/com/android/purebilibili/'
INPUT={'upstreamCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'}
PINS='''+repr(pins)+'\n'
assert s.count(old)==1;s=s.replace(old,replacement)
s=s.replace("(P/'original'/rel)","(C/rel)")
s=s.replace("path=OUT/rel.removeprefix(BASE)","path=OUT/'com/android/purebilibili'/rel.removeprefix(BASE)")
s=s.replace("path=OUT/'data/repository", "path=OUT/'com/android/purebilibili/data/repository")
s=s.replace("(P/'producer-inventory.json')", "(options.manifest or OUT/'bangumi-pages-producer-inventory.json')")
s=s.replace("fullOriginalFiles=7", "fullOriginalFiles=sum(r.get('completeOriginalFileBody',False) for r in ROWS)")
assert "P/'" not in s
tool=P/'prepared/desktop/tools/extract-upstream-bangumi-pages.py';tool.parent.mkdir(parents=True,exist_ok=True);tool.write_text(s,encoding='utf8')
actual=P/'standalone-replay';actual.mkdir(exist_ok=True)
r=subprocess.run([sys.executable,str(tool),'--repo',str(C),'--output',str(actual)],capture_output=True)
(P/'standalone-producer.log').write_bytes(r.stdout+r.stderr);assert r.returncode==0,(r.stdout+r.stderr).decode('utf8')
for row in inventory['emitted']:
 original=P/'generated'/row['output'];produced=actual/'com/android/purebilibili'/row['output']
 assert original.read_bytes()==produced.read_bytes(),row['output']
# Exact fragments only, always replayed against fixed current authoritative
# commit. Never install a whole old Shell or a whole manifest/build file.
hunks=[];targets={}
def add(path,name,before,after):
 text=targets.setdefault(path,dict(before=read(path),after=read(path)))
 assert text['after'].count(before)==1,(path,name,text['after'].count(before))
 text['after']=text['after'].replace(before,after)
 hunks.append(dict(path=path,name=name,before=before,after=after,beforeSha256LF=sha(before),afterSha256LF=sha(after)))
shell='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
anchor='''                            entryKey is BiliPaiNavKey.CommentDetail ->
                                DesktopDetailWindow { DesktopOriginalCommentDetailRootHost(entryKey, messageRoutes, active) }'''
add(shell,'actual-original-independent-catalog-detail-review-leaves',anchor,'''                            entryKey is BiliPaiNavKey.Bangumi || entryKey is BiliPaiNavKey.BangumiDetail || entryKey is BiliPaiNavKey.BangumiReview ->
                                DesktopDetailWindow { DesktopOriginalBangumiPagesRootHost(entryKey, messageRoutes, repository, ordinaryVideoResources, active,
                                    replaceSeason = { current, season -> messageRoutes.replaceBangumiDetail(current, season) }) }
'''+anchor)
anchor='is BiliPaiNavKey.Bangumi,is BiliPaiNavKey.BangumiDetail,is BiliPaiNavKey.BangumiPlayer -> DesktopSection.BANGUMI'
add(shell,'original-review-section-navigation',anchor,anchor.replace(',is BiliPaiNavKey.BangumiPlayer',',is BiliPaiNavKey.BangumiReview,is BiliPaiNavKey.BangumiPlayer'))
route='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalRootRouteAssembly.kt'
anchor='    override fun home(): Boolean = push(BiliPaiNavKey.Home)'
add(route,'original-season-replaces-only-real-top-within-existing-admission',anchor,'''    /** Original AppNavigation onSeasonClick replaces the current detail top.
     * Use this same controller/stack/Store-entry admission and native beforeCommit. */
    fun replaceBangumiDetail(current: BiliPaiNavKey.BangumiDetail, seasonId: Long): Boolean {
        if (seasonId <= 0L || currentKey != current) return false
        var replaced = false
        val accepted = admitted {
            if (currentKey == current) {
                val next = decorate(BiliPaiNavKey.BangumiDetail(seasonId = seasonId))
                replaceStack(BiliPaiNavBackStackController(stack.toList()).replaceTop(next).backStack)
                replaced = true
            }
        }
        return accepted && replaced
    }

'''+anchor)
gradle='desktop/build.gradle.kts';anchor='val extractOriginalSubscriptionPage by tasks.registering(Exec::class) {'
block='''val extractOriginalBangumiPages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeBangumiPage, extractUpstreamMedia)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bangumi-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/independent-bangumi-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-pages.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "independent-bangumi-pages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/independent-bangumi-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/independent-bangumi-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPages) }

'''
add(gradle,'sole-original-bangumi-pages-producer-input-closure',anchor,block+anchor)
registry='desktop/upstream-sources.json';manifest=json.loads(read(registry));existing={r['path']:r for r in manifest['sources']};delta=[];new=[]
def json_block(value):return '\n'.join('    '+l for l in json.dumps(value,ensure_ascii=False,indent=2).splitlines())
for path,pin in pins.items():
 if path in existing:
  before=existing[path];assert before['sha256']==pin,path
  if FEATURE in before['features']:continue
  after=json.loads(json.dumps(before));after['features'].append(FEATURE)
  add(registry,'source-feature-union:'+path,json_block(before),json_block(after))
  delta.append(dict(path=path,operation='feature-union',features=[FEATURE],sha256=pin,existingModePreserved=before['mode']))
 else:
  row=dict(path=path,sha256=pin,features=[FEATURE],mode='extracted');new.append(row)
  delta.append(dict(path=path,operation='add-original-identity',row=row))
if new:
 anchor='  "sources": [\n';add(registry,'new-original-identities-only',anchor,anchor+',\n'.join(json_block(row) for row in new)+',\n')
write_json('registry-delta.json',dict(base=BASE,newIdentities=len(new),sourceFeaturesUnion=delta,newResources=[],newDependencies=[]))
write_json('exact-hunks.json',hunks)
write_json('targets.json',[dict(path=path,beforeSha256LF=sha(text['before']),afterSha256LF=sha(text['after'])) for path,text in targets.items()])
copy=[]
for f in sorted((P/'prepared').rglob('*')):
 if not f.is_file():continue
 rel=f.relative_to(P/'prepared').as_posix();assert not (C/rel).exists(),rel
 copy.append(dict(source=f.relative_to(P).as_posix(),target=rel,sha256Bytes=hashlib.sha256(f.read_bytes()).hexdigest()))
write_json('install-contract.json',dict(candidateHead=BASE,copyWhitelist=copy,exactHunks='exact-hunks.json',targets='targets.json',registryDelta='registry-delta.json',newOriginalIdentities=len(new),newResources=[],newDependencies=[],soleProducer='desktop/tools/extract-upstream-bangumi-pages.py',completeOriginalCatalogDetailTimelineReview=True,completeOriginalPlayerAccepted=False,wholeBuild=False,rootRuntimeAccepted=False,productionWrites=0,doNotInstall=['generated/**','original/**','standalone-replay/**','runs/**','proof-runs/**']))
write_json('standalone-replay-receipt.json',dict(passed=True,comparedOutputs=len(inventory['emitted']),inverseOriginalBodies=True,base=BASE,candidateWrites=0,toolSha256Bytes=hashlib.sha256(tool.read_bytes()).hexdigest()))
print('STAGED',len(copy),'copies',len(hunks),'exact hunks',len(new),'new original identities; Player unaccepted')

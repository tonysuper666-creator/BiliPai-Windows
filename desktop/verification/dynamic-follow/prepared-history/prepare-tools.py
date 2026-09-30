from pathlib import Path
import hashlib,importlib.util,json,sys,difflib
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def ext(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
pins=[]
for tool,anchor,extra in [
 ('extract-upstream-dynamic-settings.py','    private val feedPagination=DynamicFeedPaginationRegistry()', '''
    /** One request's transient checkpoint; the original registry remains the only cursor owner. */
    fun checkpointForFollowChange(type:String)=feedPagination.snapshot(DynamicFeedScope.DYNAMIC_SCREEN,type)
    fun restoreAfterFollowChange(type:String,before:DynamicPaginationState) {
        feedPagination.updateState(DynamicFeedScope.DYNAMIC_SCREEN,type,before)
    }'''),
 ('extract-upstream-dynamic-tabs.py',' private val userFeedPagination=DynamicUserPaginationRegistry()', '''
 fun checkpointForFollowChange(uid:Long)=DynamicPaginationState(offset=userFeedPagination.offset(uid),hasMore=userFeedPagination.hasMore(uid))
 fun restoreAfterFollowChange(uid:Long,before:DynamicPaginationState) {
  userFeedPagination.update(uid,before.offset,before.hasMore)
 }''')]:
 source=REPO/'desktop/tools'/tool;s=source.read_text(encoding='utf-8');assert s.count(anchor)==1
 adapted=s.replace(anchor,anchor+extra);output=HERE/'prepared-tools/desktop/tools'/tool;output.parent.mkdir(parents=True,exist_ok=True);output.write_text(adapted,encoding='utf-8',newline='\n')
 frozen=HERE/'baseline/desktop/tools'/tool;frozen.parent.mkdir(parents=True,exist_ok=True);frozen.write_bytes(source.read_bytes())
 patch=HERE/'patches'/(tool+'.patch');patch.write_text(''.join(difflib.unified_diff(s.splitlines(keepends=True),adapted.splitlines(keepends=True),fromfile='desktop/tools/'+tool,tofile='desktop/tools/'+tool)),encoding='utf-8',newline='\n')
 pins.append(dict(path='desktop/tools/'+tool,sha256Bytes=sha(source),preparedSha256Bytes=sha(output),onlyAddedCursorCheckpointMethods=True))
output=HERE/'tool-generated';assert not output.exists();output.mkdir()
spec=importlib.util.spec_from_file_location('prepared_follow_extractor',HERE/'prepared-tools/desktop/tools/extract-upstream-dynamic-follow.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
files=m.generate(REPO,output)
checks=[]
for generated in files:
 counterpart=HERE/'candidate/generated'/generated.relative_to(output)
 # Generated provenance comments are the only difference from the already compiled candidate source.
 body=ext(generated).read_text(encoding='utf-8');body='\n'.join(body.splitlines()[2:]).strip()
 assert body==ext(counterpart).read_text(encoding='utf-8').strip(),str(generated)
 checks.append(dict(path=str(generated.relative_to(HERE)),sha256Bytes=sha(generated),compiledCandidateBodySha256Bytes=sha(counterpart),sameBody=True))
(HERE/'prepared-tool-receipt.json').write_text(json.dumps(dict(passed=True,producerInputs=pins,originalInventory=m.inventory(REPO),generatedChecks=checks,noMainOrSharedGradle=True),indent=2)+'\n',encoding='utf-8')
print('PASS prepared formal extraction producer and exact compiled-candidate body identity')

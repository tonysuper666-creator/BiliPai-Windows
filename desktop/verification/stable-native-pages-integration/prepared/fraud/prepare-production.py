"""Package this lane's selected source producer; never edit the install target."""
from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def one(s,a,b):assert s.count(a)==1,(s.count(a),a[:90]);return s.replace(a,b,1)
def main():
 source=read(HERE/'prepare.py')
 source=one(source,"HERE=Path(__file__).resolve().parent\nMAIN=next(p for p in HERE.parents if (p/'.git').exists());BASE=MAIN.parent/'BiliPai-v023'\n",'')
 source=one(source,'def main():','def generate(repo: Path, output: Path, standalone: bool = False):\n BASE=Path(repo);HERE=Path(output)')
 source=one(source," source=subprocess.check_output(['git','show',COMMIT+':'+PATH],cwd=BASE).decode().replace('\\r\\n','\\n');assert source==read(BASE/PATH)\n write(HERE/'original-source'/PATH,source)"," protocol=load('pinned_fraud_source_loader',BASE/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')\n sources,identities=protocol.load_pinned_sources(BASE,[PATH]);source=sources[PATH]\n assert identities[0]['pinnedCommit']==COMMIT\n assert sha(source)=='4950b91a708e4d22ddbc6b6f39ef6880879e175dad543ff1fd5b833518b2b820'")
 source=one(source," appearance=load('fraud_declarations',BASE/'desktop/tools/extract-appearance-platform.py')\n protocol=load('readonly_protocol_token_helpers',BASE/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')\n",'')
 source=one(source,"  write(HERE/'original-bodies'/(name+'.kt'),original+'\\n')\n",'')
 start=source.index(" write(HERE/'operations-member.fragment.kt','''")
 end=source.index(" opspath='desktop/src/main/kotlin",start)
 member=source[start:end]
 member=one(member," write(HERE/'operations-member.fragment.kt','''"," fragment='''")
 assert member.endswith("''')\n");member=member[:-5]+"'''\n"
 source=source[:start]+member+source[end:]
 start=source.index(" opspath='desktop/src/main/kotlin")
 end=source.index(' result=dict(',start)
 source=source[:start]+source[end:]
 source=one(source,"  opsBaseSha256LF=sha(ops),proofOnlyWholeOpsSha256LF=sha(candidate),soleMemberFragment=True,externalOwnedDependencies=deps,","  soleMemberFragment=True,operationsMemberFragment=fragment,identities=identities,\n  externalOwnedDependencies=['com.android.purebilibili.data.model.CommentFraudStatus','com.android.purebilibili.data.repository.CommentFraudDetectionPolicyKt'],")
 source=one(source," write(HERE/'source-inventory.json',json.dumps(result,ensure_ascii=False,indent=2)+'\\n');print(json.dumps(dict(status='prepared',opsBase=result['opsBaseSha256LF'])))\nif __name__=='__main__':main()", " write(HERE/'source-inventory.json',json.dumps(result,ensure_ascii=False,indent=2)+'\\n')\n return result\nif __name__=='__main__':\n import argparse\n parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True);parser.add_argument('--standalone',action='store_true');args=parser.parse_args()\n generate(Path(args.repo),Path(args.output),args.standalone)")
 write(HERE/'prepared/desktop/tools/extract-upstream-comment-fraud-protocol.py',source)
 write(HERE/'prepared/desktop/src/main/kotlin/com/android/purebilibili/data/repository/DesktopCommentFraudRawTransport.kt',read(HERE/'platform/com/android/purebilibili/data/repository/DesktopCommentFraudRawTransport.kt'))
 spec=importlib.util.spec_from_file_location('prepared_fraud_producer',safe(HERE/'prepared/desktop/tools/extract-upstream-comment-fraud-protocol.py'));producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
 mainrepo=next(p for p in HERE.parents if (p/'.git').exists());repo=mainrepo.parent/'BiliPai-v023'
 result=producer.generate(repo,safe(HERE/'production-byte-proof'),False)
 generated='generated/com/android/purebilibili/data/repository/DesktopOriginalCommentFraudProtocol.kt'
 a=read(HERE/generated);b=read(HERE/'production-byte-proof'/generated)
 assert a==b,'production source producer must emit the proven identical selected helper'
 assert read(HERE/'operations-member.fragment.kt')==result['operationsMemberFragment']
 proof=dict(status='PASS',sameGeneratedHelperBytes=True,sameOperationsFragmentBytes=True,originalIdentity=result['identities'][0],adapterIsExactCompiledInput=True,producerSha256Bytes=hashlib.sha256(safe(HERE/'prepared/desktop/tools/extract-upstream-comment-fraud-protocol.py').read_bytes()).hexdigest())
 write(HERE/'production-byte-equality.json',json.dumps(proof,indent=2)+'\n');print(json.dumps(proof))
if __name__=='__main__':main()

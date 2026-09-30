"""Read-only checks on the isolated candidate, not a substitute for runtime proof."""
from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent
original=HERE.parent/'native-share-current-main-review/stable-source-a/native/DesktopDiagnosticShare.cpp'
candidate=HERE/'candidate04/DesktopDiagnosticShare.cpp'
old=original.read_text(encoding='utf-8');new=candidate.read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def function(text,signature):
 start=text.index(signature);opening=text.index('{',start);depth=1;i=opening+1
 while depth:
  depth+=(text[i]=='{')-(text[i]=='}');i+=1
 return text[start:i]
checks=[]
def test(label,condition):
 assert condition,label
 checks.append({'label':label,'passed':True})
test('original reviewed source byte pin',sha(original)=='dc810bdb5b6c43c7842768270795d998d76f0b0a221e84fed97a245c220c2a9a')
test('candidate reviewed source byte pin',sha(candidate)=='5eba2101f6019c0f04a47df7202396bf0cf526967aee2abbee2f79466ed21b59')
for signature in ['extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareProbe',
                  'extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiSharePrepare',
                  'extern "C" __declspec(dllexport) HRESULT WINAPI BilipaiShareState',
                  'HRESULT revokePackage() noexcept']:
 test('verbatim original '+signature.split(' WINAPI ')[-1],function(old,signature)==function(new,signature))
start='        std::weak_ptr<Session> weak=value;';end='        }); value->hasRequested=true;'
def callback(text):return text[text.index(start):text.index(end)+len(end)]
test('verbatim weak DataRequested and original metadata/storage/completion/cancel handlers',callback(old)==callback(new))
test('original close body plus truthful owner release flag only',function(old,'HRESULT closeOnOwnerThread() noexcept').replace('return failure;','if(SUCCEEDED(failure))ownerReleased=true;\n        return failure;')==function(new,'HRESULT closeOnOwnerThread() noexcept'))
test('exact own process/thread checked before native Bind admission','process!=GetCurrentProcessId()||!thread' in new)
test('actual owner STA binding retains exact process/thread guard','process!=GetCurrentProcessId()||thread!=GetCurrentThreadId()' in new)
test('native owner only, no global hook or injection module','SetWindowsHookExW(WH_CALLWNDPROC,ownerHook,nullptr,thread)' in new and 'GetModuleHandleExW' not in new)
test('exact HWND, private message, zero lparam and owned command lookup','dispatchers.find(message->hwnd)' in new and 'message->message==ownerMessage()&&message->lParam==0' in new and 'dispatcher->commands.find(static_cast<uint64_t>(message->wParam))' in new)
test('normal Show reuses Bind then actual owner Show','auto hr=BilipaiShareBind(token,hwnd);if(FAILED(hr))return hr;' in new and 'return dispatchOwner(find(token),OwnerOperation::Show)' in new)
test('Bind has no share-pane or storage supply statement','ShowShareUIForWindow' not in function(new,'HRESULT bindOnOwnerThread') and new.count('SetStorageItems(items,true)')==1)
test('Send timeout cannot assign receiver terminal states','command->canceled=true;++commandTimeouts;' in new and 'SMTO_ABORTIFHUNG|SMTO_BLOCK|SMTO_ERRORONEXIT,2000' in new)
test('Close authority revoked immediately before native drain','value->closed=true; // immediately stop grants' in function(new,'HRESULT retireSession'))
test('Close retries reuse pending command and Bind/Show admission rejects duplicate','command=entry.second;break;' in new and 'if(operation!=OwnerOperation::Close)return HRESULT_FROM_WIN32(ERROR_BUSY);' in new)
test('completed-phase acknowledgement is checked before generic Windows timeout','if(command->phase.load(std::memory_order_acquire)==2)return command->result;' in new)
test('WM_NCDESTROY closes authority then actual owner drains; running COM defers cleanup','dispatcher->destroying=true;' in new and 'if(!dispatcher->executing)entry.second->closeOnOwnerThread();' in new and 'if(dispatcher->destroying||value->closed)' in new)
test('hook release and callback quiescence explicitly required','UnhookWindowsHookEx(dispatcher->hook)' in new and 'WaitForSingleObject(dispatcher->quiescent,2000)' in new and 'if(!dispatcher->commands.empty())return HRESULT_FROM_WIN32(ERROR_IO_PENDING);' in new)
test('failed Retire remains fail closed','*finalState=-1;*supplied=1;' in new)
test('no native HTTP, new windows, shell or receiver APIs',not any(x in new for x in ['CreateWindow','ShellExecute','WinHttp','InternetOpen','CreateProcess','OpenProcess']))
test('single token registry remains bounded at eight','if(sessions.size()>=8)' in new)
result={'passed':True,'checks':checks,'checksCount':len(checks),'originalSourceSha256Bytes':sha(original),'candidateSourceSha256Bytes':sha(candidate),
        'staticOnly':True,'actualRuntimeProof':'bind-proof05/accepted-evidence.json','arbitraryConcurrentNativeApiSupported':False,
        'productionNativeTransportIsSerializedByOperationsMutex':True,'ShareShowExecuted':False,'MainAccepted':False}
out=HERE/'source-contract-result.json';assert not out.exists();out.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
patch=HERE/'candidate04-against-reviewed-original.patch';assert not patch.exists();patch.write_text(''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='reviewed-original/DesktopDiagnosticShare.cpp',tofile='candidate04/DesktopDiagnosticShare.cpp')),encoding='utf-8',newline='\n')
print(json.dumps({'passed':True,'checks':len(checks),'resultSha256Bytes':sha(out)}))

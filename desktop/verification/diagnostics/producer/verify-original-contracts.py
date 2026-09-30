from pathlib import Path
import hashlib,importlib.util,json,textwrap
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def load(path,name):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
tool=load(HERE/'extract-upstream-diagnostics.py','tool');host=load(REPO/'desktop/tools/extract-upstream-plugins.py','host');media=host.media_extractor(REPO);parser=media.parser_for(REPO)
def read(p):return p.read_text(encoding='utf-8').replace('\r\n','\n')
original=read(REPO/tool.LOGGER);collector=original[original.index('object LogCollector {'):]
generated=read(next((HERE/'generated').rglob('DesktopDiagnosticCollector.kt')))
policies=read(next((HERE/'generated').rglob('DesktopDiagnosticPolicy.kt')))
checks=[]
expected=original[original.index('private const val LOG_DIRECTORY_NAME'):original.index('/**\n *  统一日志工具类')].replace('LogCollector.','DesktopDiagnosticCollector.')
expected=expected.replace('appendLine("Android版本: $androidRelease (API $apiLevel)")','appendLine("系统版本: $androidRelease；运行时: $apiLevel")')
assert policies.rstrip().endswith(expected.rstrip()),'Entire original pure policy region plus documented Windows header/type adapters'
for name in ['shouldEnableVerboseRuntimeLogs','shouldEmitVerboseLogcat','shouldCaptureRuntimeLogEntry','shouldPersistRuntimeLogEntry','hasExportableDiagnostics','appendRollingDiagnosticLog','hasPendingCrashSnapshot','resolveRuntimeLogFile','resolveBasicLogFile','resolveCrashSnapshotFile','resolveCrashSnapshotMarkerFile']:
 checks.append(name)
for name in ['sanitizeMessage','getEntries','getCount','clear']:
 assert media.function(collector,name,parser)==media.function(generated,name,parser),name
 checks.append(name)
adapted=media.function(generated,'add',parser).replace('clock()','System.currentTimeMillis()').replace('persist(it, basicDiagnostic)','appendEntryToRuntimeFile(it, basicDiagnostic)')
assert adapted==media.function(collector,'add',parser);checks.append('original_add_only_clock_and_file_sink_bound')
section=read(next((HERE/'generated').rglob('SettingsDiagnosticFields.kt')))
assert '同意并开启' in section and '256KB' in section and '64KB' in section and '不会自动上传' in section
assert 'crashTrackingEnabled' not in section and 'analyticsEnabled' not in section and 'NetworkProxyStore' not in section
for part in ['if (enabled) showEnhancedDiagnosticConsent = true','else onEnhancedDiagnosticLoggingChange(false)','showEnhancedDiagnosticConsent = false','onEnhancedDiagnosticLoggingChange(true)']:assert part in section
checks.append('original_effective_fields_and_consent_only_no_firebase_or_proxy_stubs')
consumer=read(HERE/'DesktopDiagnostics.kt')
assert 'OkHttpClient' not in consumer and 'SessionStore' not in consumer and 'System.getenv' not in consumer
assert 'StandardOpenOption.CREATE_NEW' in consumer and '512*1024-safeHeader.size' in consumer
assert 'Thread.setDefaultUncaughtExceptionHandler' not in read(HERE/'DesktopDiagnosticRuntimeBindings.kt')
checks.append('consumer_has_no_account_network_or_global_handler_install')
result=dict(passed=True,checks=checks,originalLoggerSha256Lf=hashlib.sha256(original.encode()).hexdigest(),originalSanitizerEntireBodyPreserved=True)
(HERE/'source-contract-proof.json').write_text(json.dumps(result,indent=2),encoding='utf-8',newline='\n')
print(json.dumps(result))

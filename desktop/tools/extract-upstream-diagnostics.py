"""Original logging policies, collector, and effective enhanced-consent UI only."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,textwrap
BASE='app/src/main/java/com/android/purebilibili/'
LOGGER=BASE+'core/util/Logger.kt'
SETTINGS=BASE+'core/store/SettingsManager.kt'
SECTIONS=BASE+'feature/settings/ui/SettingsSections.kt'
CRASH=BASE+'core/util/CrashReporter.kt'
EXIT=BASE+'core/performance/Android17Diagnostics.kt'
NATIVE=BASE+'core/performance/NativeExitTrace.kt'
ASSETS=['ms_pest_control_24']
def load(path,name):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
CORE_API_ERROR_SHA = 'f8699ac6588654fd9fef96ce337b94ffe0d9270f63d46cb0f4e5eea3521735a6'

def generate_core_api_error_policy(repo, out, parser, media, source):
    import re
    assert hashlib.sha256(source.encode()).hexdigest() == CORE_API_ERROR_SHA
    constants = '\n'.join(re.search(r'(?m)^private const val '+name+r' =[^\n]+', source).group(0)
        for name in ['MIN_NON_FATAL_HEADROOM_BYTES', 'MIN_NON_FATAL_HEADROOM_RATIO'])
    pure = '\n\n'.join(media.function(source, name, parser)
        for name in ['shouldRecordNonFatalEvent', 'normalizeApiErrorEndpoint'])
    fields = '\n'.join(re.search(r'(?m)^    private (?:const )?val '+name+r' =[^\n]+', source).group(0)
        for name in ['RATE_LIMIT_WINDOW_MS', 'RATE_LIMIT_MAX_KEYS', 'nonFatalRateLimiter'])
    fields = fields.replace('private const val', 'private val')
    selected = '\n\n'.join(media.function(source, name, parser)
        for name in ['reportApiError', 'shouldDropByRateLimit', 'hasNonFatalReportingHeadroom'])
    adapted = selected
    firebase = '            crashlytics.setCustomKey("api_endpoint", safeEndpoint)\n' + \
        '            crashlytics.setCustomKey("api_http_code", httpCode)\n' + \
        '            crashlytics.log("API Error: [$httpCode] $safeEndpoint - ${errorMessage.take(300)}")\n' + \
        '            crashlytics.recordException(ApiException(safeEndpoint, httpCode, errorMessage))\n' + \
        '            Logger.e(TAG, "API error: [$httpCode] $safeEndpoint - $errorMessage")'
    # Extraction dedents each method. Bind only platform sinks and the one owner.
    firebase = textwrap.dedent(firebase)
    # Dedent removes common indentation from this stand-alone span; in a method
    # the try body has eight spaces. Restore those exact source spaces.
    firebase = textwrap.indent(firebase, '        ')
    changes = [
        ('if (!isEnabled) return', 'if (!enabled()) return'),
        (firebase, '        emit("API Error: [$httpCode] $safeEndpoint - ${errorMessage.take(300)}")'),
        ('Log.e(TAG, "Failed to report API error", e)', 'emit("Failed to report API error: ${e.javaClass.simpleName}")'),
        ('val now = System.currentTimeMillis()', 'val now = clock()'),
    ]
    for before, after in changes:
        assert adapted.count(before) == 1, before
        adapted = adapted.replace(before, after, 1)
    inverse = adapted
    for before, after in reversed(changes):
        assert inverse.count(after) == 1
        inverse = inverse.replace(after, before, 1)
    assert inverse == selected
    body = 'package com.android.purebilibili.core.util\nimport java.util.concurrent.ConcurrentHashMap\nimport kotlin.math.max\n\n' + constants + '\n\n' + pure + \
        '\n\ninternal class DesktopOriginalCoreApiErrorPolicy(\n    private val enabled:()->Boolean,\n    private val emit:(String)->Unit,\n    private val clock:()->Long=System::currentTimeMillis,\n) {\n' + fields + '\n\n' + textwrap.indent(adapted, '    ') + '\n}\n'
    target = out/'com/android/purebilibili/core/util/DesktopOriginalCoreApiErrorPolicy.kt'
    target.parent.mkdir(parents=True, exist_ok=True);target.write_text(body, encoding='utf8', newline='\n')
    (out/'core-api-error-source-proof.json').write_text(json.dumps(dict(
        source=CRASH, originalSha256LF=CORE_API_ERROR_SHA,
        selectedOriginalSha256LF=hashlib.sha256(selected.encode()).hexdigest(),
        selectedAdaptedSha256LF=hashlib.sha256(adapted.encode()).hexdigest(),
        fullSelectedInverseExact=True, platformChanges=changes,
        existingConsumerOwnedInstance=True, cloudUpload=False), indent=2)+'\n', encoding='utf8')

def generate(repo,out):
 host=load(repo/'desktop/tools/extract-upstream-plugins.py','diaghost');media=host.media_extractor(repo);parser=media.parser_for(repo)
 identity=load(repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py','diag_fixed_identity')
 fixed_sources,source_ids=identity.load_pinned_sources(repo,[LOGGER,SETTINGS,SECTIONS,CRASH,EXIT,NATIVE])
 out.mkdir(parents=True,exist_ok=True);files=[];original=fixed_sources[LOGGER]
 declaration=load(repo/'desktop/tools/extract-appearance-platform.py','diag_original_exception')
 exception=declaration.declarations(parser,fixed_sources[EXIT],['AbnormalProcessExitException'])
 files.append(host.write(out,EXIT,fixed_sources[EXIT],'package com.android.purebilibili.core.performance\nimport java.io.PrintWriter\nimport java.io.PrintStream\n'+exception,'DesktopOriginalAbnormalProcessExitException.kt'))
 # Pure JVM original tombstone encoding/parsing/summary stays source-owned.
 files.append(host.write(out,NATIVE,fixed_sources[NATIVE],fixed_sources[NATIVE],'DesktopOriginalNativeExitTrace.kt'))
 (out/'source-identity.json').write_text(json.dumps(source_ids,indent=2)+'\n',encoding='utf-8')
 # All pure original functions before the Android Logger object. The reference to the
 # original singleton becomes the sole platform collector type, not a duplicate buffer.
 pure=original[original.index('private const val LOG_DIRECTORY_NAME'):original.index('/**\n *  统一日志工具类')]
 pure=pure.replace('LogCollector.','DesktopDiagnosticCollector.')
 pure=host.substitute(pure,'appendLine("Android版本: $androidRelease (API $apiLevel)")','appendLine("系统版本: $androidRelease；运行时: $apiLevel")')
 files.append(host.write(out,LOGGER,original,'package com.android.purebilibili.core.util\nimport com.android.purebilibili.core.performance.AbnormalProcessExitException\nimport com.android.purebilibili.core.performance.nativeExitTraceSummary\nimport java.io.File\nimport java.text.SimpleDateFormat\nimport java.util.*\n\n'+pure,'DesktopDiagnosticPolicy.kt'))
 collector=original[original.index('object LogCollector {'):]
 fields=collector[collector.index('    private const val MAX_ENTRIES'):collector.index('    /**\n     * 日志条目')]
 fields=fields[:fields.index('    private val dateFormat')]+collector[collector.index('    private var lastEntryFingerprint'):collector.index('    @Volatile\n    private var appContext')]
 fields=fields.replace('private const val','private val')
 entry=media.function(collector,'add',parser).replace('System.currentTimeMillis()','clock()')
 entry=host.substitute(entry,'appendEntryToRuntimeFile(it, basicDiagnostic)','persist(it, basicDiagnostic)')
 # Nested model and methods are original, including dedupe, truncation, UTF-8 cap,
 # ring eviction and pre-memory sanitization. File/lifetime binding is separate.
 model=parser.extract_declaration(collector,'data class LogEntry') if hasattr(parser,'extract_declaration') else None
 if model is None:
  model=collector[collector.index('    data class LogEntry('):collector.index('    /**\n     * 添加日志条目')].strip()
 methods=[media.function(collector,n,parser) for n in ['getEntries','getCount','clear']]
 clear=media.function(collector,'clearRuntimeDiagnostics',parser)
 clear=clear[:clear.index('    val context = appContext')].rstrip()+'\n}'
 sanitize=media.function(collector,'sanitizeMessage',parser)
 source='package com.android.purebilibili.core.util\nimport java.text.SimpleDateFormat\nimport java.util.*\n\ninternal class DesktopDiagnosticCollector(\n    private val clock: () -> Long = System::currentTimeMillis,\n    private val persist: (LogEntry, Boolean) -> Unit,\n) {\n'+fields+'\n'+model+'\n\n'+entry+'\n\n'+'\n\n'.join(methods)+'\n\n'+clear+'\n\n    companion object {\n'+textwrap.indent(sanitize,'        ')+'\n    }\n}\n'
 files.append(host.write(out,LOGGER,original,source,'DesktopDiagnosticCollector.kt'))
 # Preserve original settings key/default/body while binding its one DataStore to
 # the existing Windows atomic settings namespace. Cold-start reads that same key.
 original=fixed_sources[SETTINGS]
 getter=media.function(original,'getEnhancedDiagnosticLoggingEnabled',parser)
 setter=media.function(original,'setEnhancedDiagnosticLoggingEnabled',parser)
 key='KEY_ENHANCED_DIAGNOSTIC_LOGGING_ENABLED'
 assert 'booleanPreferencesKey("enhanced_diagnostic_logging_enabled")' in original
 getter=host.substitute(getter,'context.settingsDataStore.data','store.snapshot("settings")')
 getter=host.substitute(getter,'preferences['+key+']','preferences[com.bilipai.desktop.plugins.booleanPreferencesKey('+key+')]')
 getter=getter.replace('(context: Context)','()')
 setter=setter[:setter.index('    // Application')].rstrip()+'\n}'
 setter=host.substitute(setter,'(context: Context, value: Boolean)','(value: Boolean)')
 setter=host.substitute(setter,'suspend fun setEnhancedDiagnosticLoggingEnabled','fun setEnhancedDiagnosticLoggingEnabled')
 setter=host.substitute(setter,'context.settingsDataStore.edit { preferences ->\n        preferences['+key+'] = value\n    }','store.update("settings", mapOf('+key+' to JsonPrimitive(value)))')
 source='package com.bilipai.desktop.diagnostics\nimport com.bilipai.desktop.plugins.DesktopPluginStore\nimport kotlinx.coroutines.flow.*\nimport kotlinx.serialization.json.*\n\ninternal class DesktopDiagnosticSettings(private val store:DesktopPluginStore) {\n    private val '+key+' = "enhanced_diagnostic_logging_enabled"\n'+getter+'\n'+setter+'\n    fun getEnhancedDiagnosticLoggingEnabledSync():Boolean = store.preferences("settings")['+key+']?.jsonPrimitive?.booleanOrNull ?: false\n}\n'
 files.append(host.write(out,SETTINGS,original,source,'DesktopDiagnosticSettings.kt'))
 original=fixed_sources[SECTIONS];section=media.function(original,'DiagnosticsSection',parser)
 start=section.index('        SettingSwitchItem(\n            icon = com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_pest_control_24)')
 end=section.index('        SettingsAdaptiveDivider()',start)
 control=section[start:end]
 export=section[section.index('        SettingClickableItem(\n            icon = exportLogsVisual.icon,'):section.index('\n    }\n\n    if (showProxyDialog)')]
 consent=section[section.index('    if (showEnhancedDiagnosticConsent)'):].rstrip()[:-1].rstrip()
 preamble=section[section.index('    val siblingTints'):section.index('    val proxySettings')]
 source='package com.android.purebilibili.feature.settings\nimport androidx.compose.runtime.*\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.text.font.FontWeight\nimport com.android.purebilibili.core.theme.*\nimport com.android.purebilibili.core.ui.*\nimport com.android.purebilibili.core.ui.components.*\nimport com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem\nimport com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem\nimport com.bilipai.desktop.settings.*\n\n@Composable\ninternal fun DesktopDiagnosticFields(\n    enhancedDiagnosticLoggingEnabled:Boolean,\n    onEnhancedDiagnosticLoggingChange:(Boolean)->Unit,\n    onExportLogsClick:()->Unit,\n) {\n'+preamble+'    var showEnhancedDiagnosticConsent by remember { mutableStateOf(false) }\n    SettingsCardGroup {\n'+control+'        SettingsAdaptiveDivider()\n'+export+'\n    }\n'+consent+'\n}\n'
 for asset in ASSETS:source=host.substitute(source,'com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.'+asset+')','DesktopDiagnosticSettingsVectors.vector(DesktopDiagnosticSettingsSymbols.'+asset+')')
 # Android painter-resource call is the existing Windows vector adapter boundary.
 source=host.substitute(source,'exportLogsVisual.iconResId?.let { painterResource(id = it) }','exportLogsVisual.iconResId?.let { androidx.compose.ui.graphics.vector.rememberVectorPainter(DesktopSettingsVectors.vector(it)) }')
 files.append(host.write(out,SECTIONS,original,source,'SettingsDiagnosticFields.kt'))
 converter=load(repo/'desktop/tools/extract-upstream-settings-search.py','diagvectors')
 shared=converter.symbol_names(repo)
 if any(n in shared for n in ASSETS):raise ValueError('Reuse shared vector for diagnostic icon')
 converter.symbol_names=lambda _:ASSETS
 vectors=converter.vectors(repo).replace('DesktopSettingsSymbols','DesktopDiagnosticSettingsSymbols').replace('DesktopSettingsVectors','DesktopDiagnosticSettingsVectors')
 files.append(media.write(out,'com/bilipai/desktop/settings/DesktopDiagnosticSettingsVectors.kt','app/src/main/res/drawable/'+ASSETS[0]+'.xml',read(repo,'app/src/main/res/drawable/'+ASSETS[0]+'.xml'),vectors))
 ref=out.parent/'reference-only';ref.mkdir(exist_ok=True)
 (ref/'OriginalDiagnosticsSection.kt').write_text('@Composable\n'+section,encoding='utf-8',newline='\n')
 # No Firebase stub/provider is generated. Local crash-snapshot policy is always true.
 generate_core_api_error_policy(repo,out,parser,media,fixed_sources[CRASH])
 original=fixed_sources[CRASH];local=media.function(original,'shouldPersistLocalCrashSnapshot',parser)
 files.append(host.write(out,CRASH,original,'package com.android.purebilibili.core.util\n'+local+'\n','DesktopLocalCrashPolicy.kt'))
 return files
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['settings-local-diagnostics-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in [LOGGER,SETTINGS,SECTIONS,CRASH,EXIT,NATIVE]]
def resources(repo):return [dict(path='app/src/main/res/drawable/'+a+'.xml',features=['settings-local-diagnostics-symbol'],sha256=hashlib.sha256(read(repo,'app/src/main/res/drawable/'+a+'.xml').encode()).hexdigest()) for a in ASSETS]
if __name__=='__main__':
 c=argparse.ArgumentParser();c.add_argument('--repo',required=True,type=Path);c.add_argument('--output',required=True,type=Path);a=c.parse_args();print(len(generate(a.repo.resolve(),a.output.resolve())))

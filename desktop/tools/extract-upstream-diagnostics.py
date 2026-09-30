"""Original logging policies, collector, and effective enhanced-consent UI only."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,textwrap
BASE='app/src/main/java/com/android/purebilibili/'
LOGGER=BASE+'core/util/Logger.kt'
SETTINGS=BASE+'core/store/SettingsManager.kt'
SECTIONS=BASE+'feature/settings/ui/SettingsSections.kt'
CRASH=BASE+'core/util/CrashReporter.kt'
ASSETS=['ms_pest_control_24']
def load(path,name):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def read(repo,path):return (repo/path).read_text(encoding='utf-8').replace('\r\n','\n')
def generate(repo,out):
 host=load(repo/'desktop/tools/extract-upstream-plugins.py','diaghost');media=host.media_extractor(repo);parser=media.parser_for(repo)
 out.mkdir(parents=True,exist_ok=True);files=[];original=read(repo,LOGGER)
 # All pure original functions before the Android Logger object. The reference to the
 # original singleton becomes the sole platform collector type, not a duplicate buffer.
 pure=original[original.index('private const val LOG_DIRECTORY_NAME'):original.index('/**\n *  统一日志工具类')]
 pure=pure.replace('LogCollector.','DesktopDiagnosticCollector.')
 pure=host.substitute(pure,'appendLine("Android版本: $androidRelease (API $apiLevel)")','appendLine("系统版本: $androidRelease；运行时: $apiLevel")')
 files.append(host.write(out,LOGGER,original,'package com.android.purebilibili.core.util\nimport java.io.File\nimport java.text.SimpleDateFormat\nimport java.util.*\n\n'+pure,'DesktopDiagnosticPolicy.kt'))
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
 original=read(repo,SETTINGS)
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
 original=read(repo,SECTIONS);section=media.function(original,'DiagnosticsSection',parser)
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
 original=read(repo,CRASH);local=media.function(original,'shouldPersistLocalCrashSnapshot',parser)
 files.append(host.write(out,CRASH,original,'package com.android.purebilibili.core.util\n'+local+'\n','DesktopLocalCrashPolicy.kt'))
 return files
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['settings-local-diagnostics-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in [LOGGER,SETTINGS,SECTIONS,CRASH]]
def resources(repo):return [dict(path='app/src/main/res/drawable/'+a+'.xml',features=['settings-local-diagnostics-symbol'],sha256=hashlib.sha256(read(repo,'app/src/main/res/drawable/'+a+'.xml').encode()).hexdigest()) for a in ASSETS]
if __name__=='__main__':
 c=argparse.ArgumentParser();c.add_argument('--repo',required=True,type=Path);c.add_argument('--output',required=True,type=Path);a=c.parse_args();print(len(generate(a.repo.resolve(),a.output.resolve())))

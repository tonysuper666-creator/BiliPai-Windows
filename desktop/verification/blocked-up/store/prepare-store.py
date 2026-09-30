from pathlib import Path
import difflib,hashlib,importlib.util,json
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def modify(name,operations):
    baseline=HERE/'baseline'/name;baseline.parent.mkdir(parents=True,exist_ok=True)
    if not baseline.exists():baseline.write_bytes((ROOT/name).read_bytes())
    original=baseline.read_text(encoding='utf-8');text=original
    for old,new in operations:
        assert text.count(old)==1,old;text=text.replace(old,new,1)
    target=HERE/'prepared'/name;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text,encoding='utf-8',newline='\n')
    patches=HERE/'patches';patches.mkdir(exist_ok=True)
    (patches/(Path(name).name+'.patch')).write_text(''.join(difflib.unified_diff(original.splitlines(keepends=True),text.splitlines(keepends=True),fromfile='a/'+name,tofile='b/'+name)),encoding='utf-8',newline='\n')

modify('desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryPreferences.kt',[
    ('class DesktopDiscoveryPreferences(private val root: Path = DesktopLibrary.directoryForAccount(null)) {',
     'class DesktopDiscoveryPreferences(private val root: Path = DesktopLibrary.directoryForAccount(null),\n    val blockedUps: DesktopBlockedUpStore = DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root)))) {\n    init { blockedUps.migrateLegacyDiscoveryMids() }'),
    ('''        val blocked = DiscoveryPreferenceState(context, "blocked_ups") { runCatching {
            Json.decodeFromString<List<Long>>(context.getSharedPreferences("blocked_ups", 0).getString("mids", null).orEmpty())
                .filter { it > 0 }.toSet()
        }.getOrDefault(emptySet()) }
''',''),
    ('fun blockedCreators(mid: Long?): StateFlow<Set<Long>> = state(mid).blocked',
     'fun blockedCreators(mid: Long?): StateFlow<Set<Long>> { require(mid == null || mid > 0); return blockedUps.mids }'),
    ('if (blockCreator && video.creatorMid > 0) changeBlocked(mid, video.creatorMid, true)',
     'if (blockCreator && video.creatorMid > 0) blockedUps.upsert(com.android.purebilibili.core.database.entity.BlockedUp(\n            video.creatorMid, video.creatorName, "", blockedAt = video.dislikedAtMillis))'),
    ('''        val updated = if (blocked) state.blocked.value + creatorMid else state.blocked.value - creatorMid
        state.context.getSharedPreferences("blocked_ups", 0).edit().putString("mids", Json.encodeToString(updated.toList())).apply()''',
     '''        if (blocked) blockedUps.upsert(com.android.purebilibili.core.database.entity.BlockedUp(creatorMid, "", ""))
        else blockedUps.remove(creatorMid)'''),
])
modify('desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryRepository.kt',[
    ('fun blockedCreators(mid: Long?): StateFlow<Set<Long>> = preferences.blockedCreators(mid)',
     'val blockedUps: DesktopBlockedUpStore get() = preferences.blockedUps\n    fun blockedCreators(mid: Long?): StateFlow<Set<Long>> = preferences.blockedCreators(mid)'),
])
modify('desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt',[
    ('val blockedCreators by remember(discovery, accountMid) { discovery.blockedCreators(accountMid) }.collectAsState()',
     'val blockedCreators by remember(discovery, accountMid) { discovery.blockedCreators(accountMid) }.collectAsState()\n    val blockedMigrationError by discovery.blockedUps.migrationError.collectAsState()'),
    ('                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    TextButton(enabled = !feedbackBusy, onClick = { showBlocked = true })',
     '''
                blockedMigrationError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { discovery.blockedUps.migrateLegacyDiscoveryMids() } }) { Text("重试黑名单迁移") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !feedbackBusy, onClick = { showBlocked = true })'''),
])
tool=HERE/'prepared/desktop/tools/extract-upstream-blocked-up-platform.py'
spec=importlib.util.spec_from_file_location('blocked_extract',tool);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.generate(ROOT,HERE/'generated')
(HERE/'source-inventory.json').write_text(json.dumps(module.inventory(ROOT),indent=2)+'\n',encoding='utf-8')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
owned=[]
for path in sorted((HERE/'prepared').rglob('*')):
    if path.is_file() and path.suffix in ['.py','.kt'] and '__pycache__' not in path.parts:
        relative=path.relative_to(HERE/'prepared').as_posix();baseline=HERE/'baseline'/relative
        row=dict(path=relative,kind='modify' if baseline.exists() else 'new',sha256=sha(path))
        if baseline.exists():row['baseSha256']=sha(baseline)
        owned.append(row)
(HERE/'owned-files.json').write_text(json.dumps(owned,indent=2)+'\n',encoding='utf-8')
print('Prepared original-model global store + actual Discovery read/write/migration-error consumer seams; main untouched.')

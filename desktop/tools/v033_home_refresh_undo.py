"""Fixed v033 Home undo delta before existing canonical declaration/platform selection.
No runtime preference, cache or Home VM owner is created here.
"""
from pathlib import Path
import hashlib,json,os
COMMIT='6a95beedce6342219986572620f3b8071b155ef3'
PREVIOUS='1db8665cb9706dca44fcae0f540f2a3440721089'
CANONICAL='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
ARCHIVE=Path('desktop/upstream-slices/v033-home-undo')
MANIFEST_SHA256='ae2b3077d3d75cf892ca8cb11711b430082c5828192f07fce9d8b2d2d8d3dbf3'
INPUTS={'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt': '2c959020dec595839527d8c51ebbfb0a8fbf991290ec102cd18ac7b82054d176'}
EDITS={'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': [{'name': 'SettingsManager-fixed033-insertion-1', 'before': '    val cardAnimationEnabled: Boolean = false,    //  卡片进场动画（默认关闭）\n', 'after': '    val homeRefreshUndoVisible: Boolean = false, // 刷新后的撤销按钮默认隐藏\n    val cardAnimationEnabled: Boolean = false,    //  卡片进场动画（默认关闭）\n', 'count': 1}, {'name': 'SettingsManager-fixed033-insertion-3', 'before': '    private val KEY_HOME_UP_AVATARS_VISIBLE = booleanPreferencesKey("home_up_avatars_visible")\n', 'after': '    private val KEY_HOME_REFRESH_UNDO_VISIBLE = booleanPreferencesKey("home_refresh_undo_visible")\n    private val KEY_HOME_UP_AVATARS_VISIBLE = booleanPreferencesKey("home_up_avatars_visible")\n', 'count': 1}, {'name': 'SettingsManager-fixed033-insertion-5', 'before': '            cardAnimationEnabled = preferences[KEY_CARD_ANIMATION_ENABLED] ?: false,\n', 'after': '            homeRefreshUndoVisible = preferences[KEY_HOME_REFRESH_UNDO_VISIBLE] ?: false,\n            cardAnimationEnabled = preferences[KEY_CARD_ANIMATION_ENABLED] ?: false,\n', 'count': 1}, {'name': 'SettingsManager-fixed033-insertion-7', 'before': '\n    suspend fun setHomeRefreshTipVisible(context: Context, value: Boolean) {\n', 'after': '\n    fun getHomeRefreshUndoVisible(context: Context): Flow<Boolean> = context.settingsDataStore.data\n        .map { preferences -> preferences[KEY_HOME_REFRESH_UNDO_VISIBLE] ?: false }\n\n    suspend fun setHomeRefreshUndoVisible(context: Context, value: Boolean) {\n        context.settingsDataStore.edit { preferences ->\n            preferences[KEY_HOME_REFRESH_UNDO_VISIBLE] = value\n        }\n    }\n\n    suspend fun setHomeRefreshTipVisible(context: Context, value: Boolean) {\n', 'count': 1}, {'name': 'SettingsManager-fixed033-insertion-9', 'before': '            BooleanShareablePreferenceDefinition(KEY_HOME_UP_AVATARS_VISIBLE, SettingsShareSection.APPEARANCE),\n', 'after': '            BooleanShareablePreferenceDefinition(KEY_HOME_REFRESH_UNDO_VISIBLE, SettingsShareSection.APPEARANCE),\n            BooleanShareablePreferenceDefinition(KEY_HOME_UP_AVATARS_VISIBLE, SettingsShareSection.APPEARANCE),\n', 'count': 1}], 'app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt': [{'name': 'HomeScreen-fixed033-insertion-1', 'before': '        //  [新增] 刷新撤销悬浮按钮（右下角，5秒后自动消失）\n', 'after': '        //  [新增] 刷新撤销悬浮按钮（右下角，5秒后自动消失）\n        if (homeSettings.homeRefreshUndoVisible) {\n', 'count': 1}, {'name': 'HomeScreen-fixed033-insertion-3', 'before': '\n        com.android.purebilibili.feature.video.share.VideoShareSheetHost(\n', 'after': '\n        } // Refresh undo is opt-in; disabled path does not compose the overlay.\n\n        com.android.purebilibili.feature.video.share.VideoShareSheetHost(\n', 'count': 1}]}
def digest(b):return hashlib.sha256(b).hexdigest()
def raw(path):
    value=os.path.abspath(path)
    return Path('\\\\?\\'+value if os.name=='nt' and not value.startswith('\\\\?\\') else value).read_bytes()
def advance_source(repo,path,body):
    if path not in INPUTS:return body,None
    if digest(body.encode('utf8'))!=INPUTS[path]:raise ValueError('Unknown canonical Home source for v033 delta')
    m=raw(repo/ARCHIVE/'manifest.json')
    if digest(m)!=MANIFEST_SHA256:raise ValueError('v033 Home undo manifest changed')
    manifest=json.loads(m)
    if manifest.get('schemaVersion')!=1 or manifest.get('fixedUpstreamCommit')!=COMMIT or manifest.get('previousUpstreamCommit')!=PREVIOUS or manifest.get('canonicalCommit')!=CANONICAL:raise ValueError('v033 Home undo identity changed')
    fixed={}
    for row in manifest['files']:
        b=raw(repo/ARCHIVE/row['archiveFile'])
        blob=hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
        if len(b)!=row['bytes'] or digest(b)!=row['sha256Bytes'] or blob!=row['gitBlob']:raise ValueError('v033 Home undo full original changed')
        fixed[(row['originalPath'],row['commit'])]=b.decode('utf8')
    def apply(text):
        original=text;trace=[]
        for row in EDITS[path]:
            a,b=row['before'],row['after']
            if text.count(a)!=1:raise ValueError('v033 Home undo literal seam changed')
            at=text.index(a);trace.append((at,a,b));text=text[:at]+b+text[at+len(a):]
        inverse=text
        for at,a,b in reversed(trace):
            if inverse[at:at+len(b)]!=b:raise ValueError('v033 Home undo inverse changed')
            inverse=inverse[:at]+a+inverse[at+len(b):]
        if inverse!=original:raise ValueError('v033 Home undo complete original inverse failed')
        return text
    if apply(fixed[(path,PREVIOUS)])!=fixed[(path,COMMIT)]:raise ValueError('v033 Home undo delta differs from complete fixed originals')
    result=apply(body)
    return result,dict(originalPath=path,canonicalCommit=CANONICAL,previousUpstreamCommit=PREVIOUS,fixedUpstreamCommit=COMMIT,inputSHA256LF=digest(body.encode('utf8')),outputSHA256LF=digest(result.encode('utf8')),actualWholeInverse=True,partialV033BusinessDelta=True,canonicalEpochAdvanced=False,countedOriginalAdaptations=EDITS[path])

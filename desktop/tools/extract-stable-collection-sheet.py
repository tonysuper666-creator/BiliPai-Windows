"""Full stable collection UI and exact settings/request bodies with Windows ports."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib, importlib.util, json, re, sys
sys.dont_write_bytecode = True
APP = 'app/src/main/java/com/android/purebilibili/'

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result

def generate(repo, output):
    parser = load('collection_parser', repo/'desktop/tools/sync-upstream.py')
    selector = load('collection_selector', repo/'desktop/tools/extract-appearance-platform.py')
    media = load('collection_functions', repo/'desktop/tools/extract-upstream-media.py')
    manifest = json.loads((repo/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
    pins = {r['path']:r['sha256'] for r in manifest['sources']}
    records = []
    def read(path):
        text = (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
        assert hashlib.sha256(text.encode()).hexdigest() == pins[path], path
        return text
    def emit(path, body, name):
        original = read(path)
        package = re.search(r'(?m)^package (\S+)', body).group(1)
        dest = output/package.replace('.','/')/name
        dest.parent.mkdir(parents=True,exist_ok=True)
        dest.write_text('// GENERATED from '+path+'; do not edit.\n// LF-normalized SHA-256: '+pins[path]+'\n'+body,
                        encoding='utf-8',newline='\n')
        records.append({'original':path,'output':str(dest.relative_to(output)),
                        'originalSha256Lf':pins[path],'generatedSha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
    def change(text, before, after, count=1):
        assert text.count(before)==count, before
        return text.replace(before,after)

    path=APP+'feature/video/ui/components/CollectionSheet.kt'; body=read(path)
    body=change(body, 'import androidx.compose.ui.platform.LocalContext',
                'import coil3.compose.LocalPlatformContext\nimport com.bilipai.desktop.ui.LocalDesktopCollectionBindings')
    body=change(body, 'import com.android.purebilibili.core.store.SettingsManager',
                'import com.android.purebilibili.core.store.DesktopOriginalCollectionSettings as SettingsManager')
    body=change(body, 'import com.android.purebilibili.core.ui.AppModalBottomSheet',
                'import com.bilipai.desktop.ui.DesktopWindowsCollectionModalSheet as AppModalBottomSheet')
    body=change(body, 'val context = LocalContext.current','val platform = LocalDesktopCollectionBindings.current\n    val context = platform.context')
    body=change(body, 'ImageRequest.Builder(LocalContext.current)', 'ImageRequest.Builder(LocalPlatformContext.current)')
    body=change(body, 'com.android.purebilibili.core.util.ShareUtils.shareCollection(',
                'platform.shareCollection(')
    body=change(body, 'SettingsManager.setCollectionSortMode(context, collectionSubscriptionId, nextMode)',
                'platform.withOwnedCollectionPreferences { SettingsManager.setCollectionSortMode(context, collectionSubscriptionId, nextMode) }')
    emit(path,body,'DesktopOriginalCollectionSheet.kt')

    path=APP+'feature/video/ui/components/CollectionSubscriptionButton.kt';body=read(path)
    body=change(body,'import android.widget.Toast\n','')
    body=change(body,'import androidx.compose.ui.platform.LocalContext',
                'import com.bilipai.desktop.ui.LocalDesktopCollectionBindings')
    body=change(body,'import com.android.purebilibili.core.store.SettingsManager',
                'import com.android.purebilibili.core.store.DesktopOriginalCollectionSettings as SettingsManager')
    body=change(body,'import com.android.purebilibili.data.repository.ActionRepository\n','')
    body=change(body,'val context = LocalContext.current',
                'val platform = LocalDesktopCollectionBindings.current\n    val context = platform.context\n    val ActionRepository = platform.operations')
    body=change(body,'Toast.makeText(context, "无法识别合集 ID", Toast.LENGTH_SHORT).show()',
                'platform.showFeedback("无法识别合集 ID")')
    pattern=r'Toast\.makeText\(\s*context,\s*(.*?),\s*Toast\.LENGTH_SHORT\s*\)\.show\(\)'
    body,n=re.subn(pattern,lambda m:'platform.showFeedback('+m[1].strip()+')',body,flags=re.S)
    assert n==2 and 'Toast' not in body
    body=change(body, 'SettingsManager.setCollectionSubscription(context, collectionId, subscribed)',
                'platform.withOwnedCollectionPreferences { SettingsManager.setCollectionSubscription(context, collectionId, subscribed) }', count=2)
    # UI lifetime keys/disposal use the same account/source owner as its host.
    emit(path,body,'DesktopOriginalCollectionSubscriptionButton.kt')

    path=APP+'core/store/SettingsManager.kt'; original=read(path)
    names=['decodeCollectionSubscriptionIds','encodeCollectionSubscriptionIds','toggleCollectionSubscription',
           'setCollectionSubscription','decodeCollectionSortPreferences','encodeCollectionSortPreferences']
    top=selector.declarations(parser,original,names)
    manager=original[original.index('object SettingsManager {')+len('object SettingsManager {'):original.rfind('}')]
    names=['KEY_SUBSCRIBED_COLLECTION_IDS','KEY_COLLECTION_SORT_PREFERENCES','getSubscribedCollectionIds',
           'isCollectionSubscribed','setCollectionSubscription','getCollectionSortPreferences','getCollectionSortMode','setCollectionSortMode']
    members=selector.declarations(parser,manager,names)
    header='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.stringPreferencesKey
import com.bilipai.desktop.settings.collectionSettingsDataStore as settingsDataStore
import com.android.purebilibili.feature.video.ui.components.CollectionSortMode
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
'''
    emit(path,header+top+'\ninternal object DesktopOriginalCollectionSettings {\n'+members+'\n}\n',
         'DesktopOriginalCollectionSettings.kt')

    path=APP+'data/repository/ActionRepository.kt';original=read(path)
    helpers=selector.declarations(parser,original,['CollectionSubscriptionRequest','buildCollectionSubscriptionRequest'])
    constants=selector.declarations(parser,original,['FAVORITE_SEASON_PATH','UNFAVORITE_SEASON_PATH','COLLECTION_SUBSCRIPTION_PLATFORM'])
    methods='\n'.join(media.function(original,name,parser) for name in ['setCollectionSubscription','checkCollectionSubscriptionStatus'])
    methods=change(methods,'TokenManager.csrfCache ?: ""','csrfProvider()')
    methods=methods.replace('} catch (e: Exception) {','} catch (cancelled: kotlinx.coroutines.CancellationException) {\n                throw cancelled\n            } catch (e: Exception) {')
    methods=re.sub(r'(?m)^\s*android\.util\.Log\.e\([^\n]+\)\n','\n',methods)
    header='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
'''
    emit(path,header+constants+helpers+'\ninternal class DesktopOriginalCollectionActions(private val api:BilibiliApi, private val csrfProvider:()->String) {\n'+methods+'\n}\n',
         'DesktopOriginalCollectionActions.kt')
    path=APP+'core/util/ShareUtils.kt'; original=read(path)
    share=media.function(original,'shareCollection',parser)
    begin=re.search(r'(?m)^[ \t]*val url = ',share).start()
    end=re.search(r'(?m)^[ \t]*val intent = ',share).start()
    emit(path,'package com.android.purebilibili.core.util\ninternal fun buildDesktopCollectionShareText(title:String,mid:Long,seasonId:Long):String {\n'+
         share[begin:end]+'    return shareText\n}\n','DesktopOriginalCollectionShareText.kt')
    output.mkdir(parents=True,exist_ok=True)
    (output/'source-bindings.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8',newline='\n')

if __name__=='__main__':generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())

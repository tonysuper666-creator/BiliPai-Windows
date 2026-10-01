def preview_probe(repo,output,standalone=False,shared_closure=False):
 host=module(repo,'full_card_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
 appearance=module(repo,'full_card_decl','desktop/tools/extract-appearance-platform.py')
 output.mkdir(parents=True,exist_ok=True);files=[]
 def emit(path,body,name):files.append(host.write(output,path,read(repo,path),body,name))
 def replace(source,old,new):return host.substitute(source,old,new)
 def fun(s,n):return media.function(s,n,parser)
 def decl(s,n):
  tokens=parser.kotlin_tokens(s);found=[i for i,t in enumerate(tokens) if t[0] in ['class','interface'] and tokens[i+1][0]==n]
  assert len(found)==1,n
  start=found[0];index=start
  while tokens[index][0]!='{':index+=1
  depth=1
  while depth:
   index+=1;depth+=(tokens[index][0]=='{')-(tokens[index][0]=='}')
  begin=s.rfind('\n',0,tokens[start][1])+1
  return s[begin:tokens[index][2]]
 p=COMP+'ImagePreviewDialog.kt';s=read(repo,p)
 emit(p,'package com.android.purebilibili.feature.dynamic.components\n'+fun(s,'normalizeImageUrl')+'\n'+fun(s,'resolveImagePreviewPlaceholderCacheKey')+'\n'+fun(s,'resolveImageShareMimeType')+'\n','DesktopOriginalImageUrlPolicy.kt')
 # Stable removes the obsolete Quad footer. Select the complete renderer prefix
 # before the URL-policy functions, retaining every original preview declaration.
 body=s[:s.index('/**\n *  规范化图片 URL')]
 # Android navigation-bar animation has no existing desktop equivalent. Its
 # Activity/window lifecycle is removed below, so remove only this new helper.
 body=replace(body,fun(s,'animateWindowNavigationBarColor'),'')
 body='\n'.join(l for l in body.splitlines() if not (l.startswith('import android.') or l.startswith('import androidx.core.') or l.startswith('import androidx.navigationevent') or any(l.startswith('import '+x) for x in ['androidx.compose.ui.platform.LocalView','androidx.compose.ui.window.DialogWindowProvider','androidx.compose.ui.graphics.asComposeRenderEffect','com.android.purebilibili.core.ui.setWindowNavigationBarColor','com.android.purebilibili.core.ui.LocalPredictiveBackGestureEnabled','com.android.purebilibili.core.util.rememberHapticFeedback','androidx.media3.common.Player','coil3.imageLoader'])))+'\n'
 body=body.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext').replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle')
 body=body.replace('.collectAsStateWithLifecycle(initialValue =','.collectAsStateWithLifecycle(initial =')
 body=body.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopDynamicCardSettings as SettingsManager')
 body+='\n@Composable\n'+fun(s,'LivePhotoIcon')+'\n@Composable\n'+fun(s,'LivePhotoOffIcon')+'\n'+fun(s,'resolveLivePhotoVideoUrl')+'\n'
 body=body.replace('    val token: Long,','    val token: Long,\n    val platform: com.bilipai.desktop.ui.DesktopDynamicCardPlatform,',1)
 body=body.replace('    val latestOnDismiss by rememberUpdatedState(onDismiss)','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    val latestOnDismiss by rememberUpdatedState(onDismiss)',1)
 body=body.replace('                token = requestToken,','                token = requestToken,\n                platform = platform,',1)
 body=body.replace('                decorFitsSystemWindows = false','')
 start=body.index('            val dialogView = LocalView.current');end=body.index('            ImagePreviewOverlayContent(',start)
 body=body[:start]+'            CompositionLocalProvider(com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings provides request.platform) {\n'+body[end:]
 host_start=body.index('fun ImagePreviewOverlayHost(');host_end=body.index('\n@Composable\nprivate fun ImagePreviewOverlayContent',host_start)
 fragment=body[host_start:host_end];last=fragment.rfind('\n}');fragment=fragment[:last]+'\n    }'+fragment[last:];body=body[:host_start]+fragment+body[host_end:]
 body=body.replace('    val haptic = rememberHapticFeedback()','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    val haptic = com.bilipai.desktop.ui.rememberDynamicPlatformHaptic()')
 start=body.index('    //  获取 Activity 和 Window');end=body.index('    //  动画状态控制',start);body=body[:start]+body[end:]
 start=body.index('    val backEventState =');end=body.index('    var isDismissing',start);body=body[:start]+'    val backProgress = 0f // Android predictive back has no Windows gesture provider.\n'+body[end:]
 body=body.replace('SettingsManager.getImagePreviewLongPressSaveEnabled(context)','SettingsManager.getImagePreviewLongPressSaveEnabled(platform.context)').replace('SettingsManager.getImagePreview3dPageEnabled(context)','SettingsManager.getImagePreview3dPageEnabled(platform.context)')
 body=body.replace('context.imageLoader','coil3.SingletonImageLoader.get(context)')
 body=body.replace('mutableStateOf<Player?>','mutableStateOf<com.bilipai.desktop.ui.DesktopDynamicLivePhotoPlayer?>')
 start=body.index('    var pendingSaveAction');end=body.index('    // 当前页的图片 URL',start)
 body=body[:start]+'''    fun saveOwned(operation:suspend ()->Boolean,successMessage:String="图片已保存到所选文件") {
        if(isSaving)return
        isSaving=true
        scope.launch {
            try{handleImageSaveResult(operation(),successMessage)}
            catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){platform.showFeedback(error.message?:"保存失败，请重试")}
            finally{isSaving=false}
        }
    }
    fun requestSaveCurrentImage(imageUrl:String) {
        if(imageUrl.isEmpty()||isSaving)return
        if(onImageLongPress!=null){onImageLongPress(imageUrl);return}
        saveOwned({platform.saveImage(imageUrl)})
    }
    fun requestSaveMotionPhoto(imageUrl:String,videoUrl:String) = saveOwned({platform.saveMotionPhoto(imageUrl,videoUrl)},"实况照片已保存到所选文件")
    fun requestSaveLivePhotoVideo(videoUrl:String) = saveOwned({platform.saveLivePhotoVideo(videoUrl)},"实况视频已保存到所选文件")
    fun requestSaveAllImages()=saveOwned({platform.saveImages(images.map(::normalizeImageUrl).filter(String::isNotEmpty))})
    fun requestShareCurrentImage(imageUrl:String) {
        if(imageUrl.isEmpty()||isSharing)return
        isSharing=true
        scope.launch {
            try{handleImageShareResult(platform.shareImage(imageUrl))}
            catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){platform.showFeedback(error.message?:"分享失败，请重试")}
            finally{isSharing=false}
        }
    }
'''+body[end:]
 # Remove only Android predictive-back provider call; original dialog dismissal,
 # vertical drag, pager transforms and return morph remain intact.
 start=body.index('            NavigationBackHandler(');tokens=parser.kotlin_tokens(body);i=next(i for i,t in enumerate(tokens)if t[1]>=start)
 while tokens[i][0]!='(':i+=1
 depth=1
 while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
 body=body[:start]+body[tokens[i][2]:]
 import re
 body=re.sub(r'Toast.makeText\(\s*context,\s*(.*?),\s*Toast.LENGTH_SHORT\s*\)\.show\(\)',r'platform.showFeedback(\1)',body,flags=re.S)
 body=body.replace('                            val clipboard = context.getSystemService(ClipboardManager::class.java)\n                            clipboard?.setPrimaryClip(ClipData.newPlainText("图片链接", currentImageUrl))','                            platform.copyText(currentImageUrl)')
 body=body.replace('LivePhotoPlayback(','com.bilipai.desktop.ui.DesktopDynamicLivePhotoPlayback(')
 emit(p,body,'DesktopOriginalImagePreviewRenderer.kt')
 return files

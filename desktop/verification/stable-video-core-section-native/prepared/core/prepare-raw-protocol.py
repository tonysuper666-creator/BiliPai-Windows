from pathlib import Path
import importlib.util,json,re
H=Path(__file__).resolve().parent
def module(n,p):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
p=module("rawModels",H/"prepare-models.py")
sel=module("rawSelect",p.REPO/"desktop/tools/extract-upstream-video-detail-full-units.py")
sel.parser=module("rawParser",p.REPO/"desktop/tools/sync-upstream.py")
orig=p.original(p.BASE+"data/repository/VideoRepository.kt")
methods=['getVideoInfoOnly','getInitialPlayUrlData','getVideoDetails','getPlaybackNavInfo','getPlayUrlData','getPlayUrlDataForPlaybackTransition','getExactPremiumPlayUrl','fetchPlayUrlRecursive','hasPlayableStreams','fetchDashWithFallback','fetchAsGuestFallback','fetchGuestPlaybackWithFallback','fetchPlayUrlWithWbiInternal','fetchPlayUrlWithAccessToken','getRelatedVideos','classifyPlayUrlError','getWbiKeys']
parts=[sel.func(orig,n,False) for n in methods]
a=orig.index('    private data class PlayUrlFetchResult(');b=p.lex.balanced(p.lex.masked(orig),orig.index('(',a));result=orig[a:b]
s='\n\n'.join([result]+parts);changes=[]
def change(a,b,label,count=None):
 global s
 n=s.count(a);assert n and (count is None or n==count),(label,n);s=s.replace(a,b);changes.append(dict(label=label,before=a,after=b,count=n))
# Root's same authorized APIs replace only original global access. No HTTP graph is created here.
change('NetworkModule.playbackApi()','environment.playbackApi','Captured SAME playback authorization API')
change('NetworkModule.guestApi','environment.guestApi','Captured visitor-only original guest API')
change('com.android.purebilibili.core.util.Logger','Logger','Existing safe desktop diagnostics logger')
change('com.android.purebilibili.core.store.TokenManager.ACCESS_TOKEN_PLATFORM_ANDROID','environment.androidAccessTokenPlatform','Exact original Android token platform supplied by same session port')
change('applicationContext != null && playbackAccount() == null','environment.canRefreshPrimaryToken() && playbackAccount() == null','Captured primary account refresh admission')
change('com.android.purebilibili.core.network.TokenRefreshHelper.refresh(applicationContext!!)','environment.refreshPrimaryToken()','SAME session original refresh actor')
# These are reads of the original key/default, not a new settings model.
initial='val auto1080pEnabled = try {\n            val context = NetworkModule.appContext\n            context?.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)\n                ?.getBoolean("exp_auto_1080p", true) ?: true\n        } catch (e: Exception) {\n            true\n        }'
change(initial,'val auto1080pEnabled = environment.auto1080pEnabled()','Original exp_auto_1080p same-global read',1)
detail='val auto1080pEnabled = try {\n                val context = com.android.purebilibili.core.network.NetworkModule.appContext\n                context?.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)\n                    ?.getBoolean("exp_auto_1080p", true) ?: true // 默认开启\n            } catch (e: Exception) {\n                true // 出错时默认开启\n            }'
change(detail,'val auto1080pEnabled = environment.auto1080pEnabled()','Original exp_auto_1080p same-global read',1)
change('val auto1080pEnabled = NetworkModule.appContext?.let { context ->\n            runCatching { SettingsManager.getAuto1080p(context).first() }.getOrDefault(true)\n        } ?: true','val auto1080pEnabled = environment.auto1080pEnabled()','Original flow same-global read',1)
change('NetworkModule.appContext?.let {\n                SettingsManager.getBiliDirectedTrafficEnabledSync(it)\n            } ?: false','environment.directedTrafficEnabled()','Original directed traffic consent same-global',1)
change('NetworkModule.appContext?.let {\n                NetworkUtils.isMobileData(it)\n            } ?: false','environment.isMobileData()','Real Windows WWAN query',1)
change('PlayUrlCache.','environment.cache.','Sole existing Root playback cache port')
change('e.printStackTrace()','Logger.e("VideoRepo", "Original video detail failed", e)','Safe logging, no raw stdout exception',1)
# Task cancellation/retirement must not be downgraded to a normal original API failure.
change('catch (e: Exception)', 'catch (e: Exception)', 'marker') if False else None
s=s.replace('} catch (e: Exception) {','} catch (e: Exception) {\n            if (e is kotlinx.coroutines.CancellationException) throw e\n            environment.assertOwned()\n')
for name in ['getVideoInfoOnly','getInitialPlayUrlData','getVideoDetails','getPlaybackNavInfo','getPlayUrlData','getRelatedVideos']:
 a,b=sel.function_range(s,name);piece=s[a:b]
 piece=re.sub(r'^(\s*)(?:internal )?suspend fun',r'\1override suspend fun',piece,count=1)
 # Overrides own no default: canonical defaults are retained on the required interface.
 hs=piece.index('(');he=p.lex.balanced(p.lex.masked(piece),hs)
 head=piece[hs:he]
 head=re.sub(r' = (?:0L|0|null)', '',head)
 piece=piece[:hs]+head+piece[he:]
 s=s[:a]+piece+s[b:]
# A late raw response/cache read must not re-enter retired UI state.
s=s.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n        environment.assertOwned()\n')
s=s.replace('Result.success(info)','environment.assertOwned()\n            Result.success(info)')
s=s.replace('Result.success(Pair(info, playData))','environment.assertOwned()\n            Result.success(Pair(info, playData))')
s=s.replace('return@withContext cachedPlayData','environment.assertOwned()\n                return@withContext cachedPlayData')
# env APIs/cache are already admitted; these public response checks are supplementary, not the network ownership authority.
header='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.core.network.WbiUtils\nimport com.android.purebilibili.core.network.AppSignUtils\nimport android.util.Log as Logger\nimport kotlinx.coroutines.*\nimport com.bilipai.desktop.ui.DesktopOriginalVideoLoadRepository\nimport com.bilipai.desktop.ui.DesktopOriginalVideoLoadProtocolEnvironment\n\ninternal class DesktopOriginalVideoLoadProtocol(private val environment:DesktopOriginalVideoLoadProtocolEnvironment) : DesktopOriginalVideoLoadRepository {\n    private val api get() = environment.api\n    private val APP_API_COOLDOWN_MS = 120_000L\n    private var appApiCooldownUntilMs:Long\n        get() = environment.state.appApiCooldownUntilMs\n        set(value) { environment.state.appApiCooldownUntilMs = value }\n    private var wbiKeysCache:Pair<String,String>?\n        get() = environment.state.wbiKeys\n        set(value) { environment.state.wbiKeys = value }\n    private var wbiKeysTimestamp:Long\n        get() = environment.state.wbiKeysTimestamp\n        set(value) { environment.state.wbiKeysTimestamp = value }\n    private var last412Time:Long\n        get() = environment.state.last412Time\n        set(value) { environment.state.last412Time = value }\n    private val WBI_CACHE_DURATION = 1000 * 60 * 30\n    private suspend fun ensureBuvid3FromSpi() = environment.ensureBuvid()\n    private fun playbackAccount() = environment.playbackAccount()\n    private fun hasPlaybackSessionCookie() = environment.hasPlaybackSessionCookie()\n    private fun playbackAccessToken() = environment.playbackAccessToken()\n    private fun playbackAccessTokenPlatform() = environment.playbackAccessTokenPlatform()\n    override fun isUsingDedicatedPlaybackAccount() = playbackAccount() != null\n    override fun isPlaybackLoggedIn() = resolveVideoPlaybackAuthState(hasPlaybackSessionCookie(), !playbackAccessToken().isNullOrEmpty())\n    override fun isPlaybackVip() = environment.isPlaybackVip()\n    override fun isAppApiCoolingDown() = (appApiCooldownUntilMs-System.currentTimeMillis()).coerceAtLeast(0L)>0L\n    private fun isDirectedTrafficModeActive() = shouldEnableDirectedTrafficMode(environment.directedTrafficEnabled(),environment.isMobileData())\n'
p.put(H/'prepared/protocol/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt',header+s+'\n}\n')
p.put(H/'raw-protocol-adaptations.json',json.dumps(changes,ensure_ascii=False,indent=2)+'\n')
p.put(H/'original-stable/VideoRepository.kt',orig)
print('Full original raw playback selected protocol methods',len(methods),'lines',len((header+s).splitlines()))

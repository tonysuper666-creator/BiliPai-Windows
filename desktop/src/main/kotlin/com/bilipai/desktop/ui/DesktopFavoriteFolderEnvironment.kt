package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.FavFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicBoolean

/** Actual host-window viewport, provided above the modal. No Android screen or fixed height. */
data class DesktopFavoriteFolderViewport(val screenHeightDp:Int)
val LocalDesktopFavoriteFolderViewport=staticCompositionLocalOf<DesktopFavoriteFolderViewport> {
    error("Favorite drawer requires the measured Root window viewport")
}

/** Root supplies its existing API/Operations and page-owned scope. No persistent state or HTTP client. */
class DesktopFavoriteFolderEnvironment(
    val scope:CoroutineScope,
    private val stillOwned:()->Boolean,
    private val readAid:()->Long?,
    private val readFavoriteCount:()->Int,
    private val loadFolders:suspend(Long?)->Result<List<FavFolder>>,
    private val saveFolders:suspend(Long,Set<Long>,Set<Long>)->Result<Boolean>,
    private val createFolder:suspend(String,String,Boolean)->Result<Boolean>,
    private val onFavoriteLoaded:(Boolean)->Unit,
    private val onFavoriteSaved:(Boolean,Int)->Unit,
    private val feedback:(String)->Unit,
) {
    private var brandEvents: com.android.purebilibili.core.events.BrandSuccessEvents? = null
    private var captureBrandOrigin: (suspend () -> DesktopBrandSuccessOrigin?)? = null
    fun mountBrandFeedback(events: com.android.purebilibili.core.events.BrandSuccessEvents,
        capture: suspend () -> DesktopBrandSuccessOrigin?) {
        assertOwned(); check(brandEvents == null || brandEvents === events)
        brandEvents = events; captureBrandOrigin = capture
    }
    private val closed=AtomicBoolean(false)
    fun close() { closed.set(true) }
    fun assertOwned() { if(closed.get() || !scope.isActive || !stillOwned()) throw CancellationException("Favorite drawer owner retired") }
    fun currentAid():Long? { assertOwned();return readAid() }
    fun currentFavoriteCount():Int { assertOwned();return readFavoriteCount() }
    suspend fun getFavoriteFolders(aid:Long?):Result<List<FavFolder>> { assertOwned();return loadFolders(aid).also { assertOwned() } }
    suspend fun updateFavoriteFolders(aid:Long,addFolderIds:Set<Long>,removeFolderIds:Set<Long>):Result<Boolean> {
        assertOwned()
        val origin = captureBrandOrigin?.invoke()
        val result = saveFolders(aid,addFolderIds,removeFolderIds)
        assertOwned()
        if (result.isSuccess && addFolderIds.isNotEmpty() && origin != null)
            brandEvents?.favoriteSaved(origin)
        return result
    }
    suspend fun createFavFolder(title:String,intro:String,isPrivate:Boolean):Result<Boolean> {
        assertOwned();return createFolder(title,intro,isPrivate).also { assertOwned() }
    }
    fun confirmFavoriteLoaded(value:Boolean) { assertOwned();onFavoriteLoaded(value);assertOwned() }
    fun confirmFavoriteSave(value:Boolean,count:Int) { assertOwned();onFavoriteSaved(value,count);assertOwned() }
    fun showFeedback(value:String) { assertOwned();feedback(value);assertOwned() }
}

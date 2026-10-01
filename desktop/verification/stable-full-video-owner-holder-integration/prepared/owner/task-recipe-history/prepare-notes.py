"""Original complete notes protocol, only same-API/session/cancellation platform binding."""
from pathlib import Path
import subprocess,hashlib,json
H=Path(__file__).resolve().parent
repo=H.parents[2].parent/'BiliPai-v023'
path='app/src/main/java/com/android/purebilibili/data/repository/VideoNoteRepository.kt'
original=subprocess.check_output(['git','-C',str(repo),'show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path]).decode().replace('\r\n','\n')
body=original[original.index('data class VideoNoteSnapshot'):]
body=body.replace('object VideoNoteRepository {','internal class DesktopOriginalVideoNoteProtocol(private val environment: com.bilipai.desktop.ui.DesktopOriginalVideoNoteEnvironment) {')
body=body.replace('private val api = NetworkModule.api','private val api get() = environment.api')
body=body.replace('TokenManager.csrfCache','environment.csrf()')
body=body.replace('!TokenManager.sessDataCache.isNullOrBlank()','environment.hasSession()')
body=body.replace('runCatching {','owned {')
marker='    private val api get() = environment.api\n'
guard='''
    // Every actual API already belongs to the captured Root transport. This
    // extra continuation boundary rejects cancellation/retired response before
    // original result projection; there is no HTTP/client/account/cache here.
    private suspend fun <T> owned(block: suspend () -> T): Result<T> {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        environment.assertOwned()
        return try {
            val value = block()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            environment.assertOwned()
            Result.success(value)
        } catch (canceled: kotlinx.coroutines.CancellationException) {
            throw canceled
        } catch (failure: Exception) {
            environment.assertOwned()
            Result.failure(failure)
        }
    }
'''
body=body.replace(marker,marker+guard)
imports='''package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.PublicVideoNoteInfoData
import com.android.purebilibili.data.model.response.PublicVideoNoteItem
import com.android.purebilibili.data.model.response.VideoNoteInfoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

'''
out=H/'prepared/notes/com/android/purebilibili/data/repository/DesktopOriginalVideoNoteProtocol.kt'
out.parent.mkdir(parents=True,exist_ok=True);out.write_text(imports+body,encoding='utf-8',newline='\n')
(H/'note-original-binding.json').write_text(json.dumps(dict(preparedOnly=True,originalPath=path,originalSha256LF=hashlib.sha256(original.encode()).hexdigest(),upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',completeOriginalSchemas=['VideoNoteSnapshot','VideoNoteSavePayload','VideoNoteRepositoryError'],methods=['getVideoNoteSnapshot','savePrivateNote','deletePrivateNote','getPublicNoteInfo','loadPrivateNote','hasSession','classifyApiError'],platformDelta=['object singleton replaced required per-captured-request API/session view over same Root Repository','No raw primary credentials except supplied admitted CSRF field','Cancellation is propagated; final response is rechecked before projection'],uncompiled=True),indent=2)+'\n',encoding='utf-8')
print('Full original note schemas/protocol prepared, no client/store/VM constructed')

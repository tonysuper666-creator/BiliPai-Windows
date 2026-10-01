from pathlib import Path
import importlib.util,json
H=Path(__file__).resolve().parent
sp=importlib.util.spec_from_file_location('suppModels',H/'prepare-models.py');p=importlib.util.module_from_spec(sp);sp.loader.exec_module(p)
original=p.original(p.BASE+'feature/video/viewmodel/VideoSupplementViewModel.kt')
a=original.index('fun interface VideoSupplementLoader');b=original.index('private val EmptyVideoSupplementLoader');loader=original[a:b]
a=original.index('class VideoSupplementViewModel(');b=original.index('internal fun VideoPlaybackUiState.Success.toSupplementSeed');body=original[a:b]
before=body
body=body.replace('class VideoSupplementViewModel(\n    private val loader: VideoSupplementLoader = EmptyVideoSupplementLoader,\n    private val startDelayMs: Long = 300L\n) : ViewModel() {','internal class VideoSupplementViewModel(\n    private val environment:com.bilipai.desktop.ui.DesktopOriginalVideoSupplementEnvironment,\n    private val loader:VideoSupplementLoader,\n    private val startDelayMs:Long=300L,\n) : AutoCloseable {\n    private val viewModelScope get() = environment.scope\n    private fun <T> MutableStateFlow(initial:T):MutableStateFlow<T> =\n        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)\n')
body=body.replace('    override fun onCleared() {','    override fun close() {').replace('        super.onCleared()','')
header='package com.android.purebilibili.feature.video.viewmodel\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.feature.video.note.VideoNoteUiState\nimport kotlinx.coroutines.*\nimport kotlinx.coroutines.flow.*\n'
p.put(H/'prepared/supplement/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSupplementViewModel.kt',header+loader+body)
p.put(H/'supplement-adaptations.json',json.dumps(dict(before=before,after=body,originalSha=p.digest(original),boundary='Required actual loader+same entry scope/owned flow; no default EmptyLoader/ViewModel base'),ensure_ascii=False,indent=2)+'\n')

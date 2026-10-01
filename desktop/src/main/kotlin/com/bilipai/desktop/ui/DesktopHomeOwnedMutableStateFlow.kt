package com.bilipai.desktop.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** Platform receipt admission around the one original MutableStateFlow. The original reducers,
 * values and flow equality are unchanged. There is only this delegate, never a second item store. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class DesktopHomeOwnedMutableStateFlow<T> private constructor(
 private val delegate:MutableStateFlow<T>,private val commit:((()->Unit))->Boolean,
):MutableStateFlow<T> by delegate {
 constructor(initial:T,commit:((()->Unit))->Boolean):this(MutableStateFlow(initial),commit)
 override var value:T
  get()=delegate.value
  set(value){if(!commit{delegate.value=value})throw CancellationException("Home entry retired")}
 override fun compareAndSet(expect:T,update:T):Boolean {var applied=false;if(!commit{applied=delegate.compareAndSet(expect,update)})throw CancellationException("Home entry retired");return applied}
 override suspend fun emit(value:T){this.value=value}
 override fun tryEmit(value:T):Boolean{this.value=value;return true}
}

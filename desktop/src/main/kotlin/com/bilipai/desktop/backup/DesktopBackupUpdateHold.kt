package com.bilipai.desktop.backup

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class DesktopBackupUpdateActivity(
    val editors: Set<Any> = emptySet(),
    val operations: Set<Job> = emptySet(),
) {
    val blocksInstallation get() = editors.isNotEmpty() || operations.isNotEmpty()
}

/** Admission runs on Main with update activation; Job completion only removes its own entry. */
internal class DesktopBackupUpdateHold(private val canBegin: () -> Boolean) {
    private val mutableActivity = MutableStateFlow(DesktopBackupUpdateActivity())
    val activity = mutableActivity.asStateFlow()

    fun canBeginEditing(): Boolean = canBegin()

    fun mountEditor(instance: Any): Boolean {
        if (!canBegin()) return false
        mutableActivity.update { it.copy(editors = it.editors + instance) }
        return true
    }

    fun unmountEditor(instance: Any) {
        mutableActivity.update { it.copy(editors = it.editors - instance) }
    }

    fun beginOperation(operation: Job): Boolean {
        if (!canBegin() || !operation.isActive) return false
        mutableActivity.update { it.copy(operations = it.operations + operation) }
        operation.invokeOnCompletion {
            mutableActivity.update { it.copy(operations = it.operations - operation) }
        }
        return operation.isActive
    }

    fun blocksUpdateInstallation(): Boolean = activity.value.blocksInstallation
}

# Same queue and original task body. Final admission covers only in-memory publication.
rel=BASE+'download/DesktopDownloadManager.kt';s=bodies[rel]
begin=s.index('    fun enqueue(');end=s.index('    /** Pause/cancel',begin)
old=s[begin:end]
create_begin=old.index('        require(metadata.downloadAllowed)')
create_end=old.index('        val existing =')
create=old[create_begin:create_end]
task_begin=old.index('        val task = DownloadTask(');task_end=old.index('        ensureOwnedDirectory(task)')
new='''    fun enqueue(source: PlaybackSource, destination: Path = defaultDownloadRoot(), metadata: DownloadMetadata = DownloadMetadata(), stillOwned: () -> Boolean = { true }): String {
        publication.admit(source, stillOwned) { Unit }
        val task = prepareDownloadTask(source, destination, metadata)
        ensureOwnedDirectory(task) // filesystem work is outside the Store monitor
        val id = publication.admit(source, stillOwned) { enqueueAdmitted(task, source) }
        synchronized(lock) { persistLocked(force = true); scheduleLocked() }
        return id
    }

    private fun prepareDownloadTask(source: PlaybackSource, destination: Path, metadata: DownloadMetadata): DownloadTask {
'''+create+old[task_begin:task_end]+'''        return task
    }

    private fun enqueueAdmitted(task: DownloadTask, source: PlaybackSource): String = synchronized(lock) {
        check(!closed) { "下载队列已关闭" }
        val existing = mutableTasks.value.firstOrNull { it.id == task.id }
        if (existing != null) {
            if (existing.status in setOf(DownloadStatus.FAILED, DownloadStatus.PAUSED)) resumeAdmitted(existing.id, source)
            return existing.id
        }
        mutableTasks.value = mutableTasks.value.filterNot { it.id == task.id } + task
        task.id
    }

'''
modify(rel,[(old,new,1)])
s=bodies[rel];b=s.index('    private fun resumeAdmitted');e=s.index('    fun retry(',b);old=s[b:e]
assert old.count('        persistLocked(force = true)\n        scheduleLocked()\n')==1
new=old.replace('        persistLocked(force = true)\n        scheduleLocked()\n','')
modify(rel,[(old,new,1),(
 '        else resumeAdmitted(id, null)\n    }',
 '        else resumeAdmitted(id, null)\n        synchronized(lock) { persistLocked(force = true); scheduleLocked() }\n    }',1)])
for begin,end in [('            if (publication.requiresAccountReceipt && task.authorizationReceipt == null)', '            publication.admit(task.playbackSource(), owned)'),
                  ('            publication.admit(refreshed, owned) {\n                update(id, true)', '            activeSource = refreshed')]:
 s=bodies[rel];b=s.index(begin);e=s.index(end,b);old=s[b:e]
 replacement=old.replace('update(id, true)', 'synchronized(lock) { updateLocked(id)')
 # The original block closes update(); close its original queue monitor too.
 anchor=' }) }\n'
 assert replacement.count(anchor)==1
 replacement=replacement.replace(anchor,' }) } }\n')
 if begin.startswith('            if'):
  anchor='                task = synchronized(lock) { mutableTasks.value.first { it.id == id } }'
  replacement=replacement.replace(anchor,'                synchronized(lock) { persistLocked(force = true) }\n'+anchor)
 else:replacement+='            synchronized(lock) { persistLocked(force = true) }\n'
 modify(rel,[(old,replacement,1)])

Read-only owner design. No Download status, directory, cache or scheduling mutation is included.

Original DataStorageSection (SettingsSections.kt:1368) separates settings share, WebDAV, download location, image location, manual cache clear, automatic interval and threshold. Existing ZIP/WebDAV and image-location consumers are already real and must stay distinct. A chooser/preferences row alone does not complete download/cache ownership.

Downloads:

* DesktopDownloadManager is the actual catalog/jobs owner. Its constructor restores state and schedules accepted work; enqueue/prepareDownloadTask and enqueueOriginal capture destinationRoot per task (DesktopDownloadManager.kt:23,46,56). Existing tasks store their own root and must not be rewritten on a default-setting change.
* A first bounded settings slice can change only the default for future admissions, through original custom_download_path validation plus the real enqueue entry. Before claiming completion, wire all ordinary-video, PGC/course and audio admission consumers to the same default resolver. Preserve explicit user-selected destination and server download permission.
* A move-existing-downloads operation is a separate multi-file transaction: stop admission, join task jobs, preserve stable source-authorization/epoch leases, validate real absolute paths and exclude symlink/junction escape, verify each .bilipai-download ownership marker (manager ensureOwnedDirectory / deleteOwnedDirectory at 440/455), stage/copy/verify files, atomically replace catalog only after successful destination materialization, then retire source files owned by the same marker. Rollback must leave old catalog/root playable. Never recursively clear arbitrary selected folders.
* Current close at 464 cancels the manager scope under its monitor; it is not by itself an explicit suspend await-all migration barrier. Before any move/restore operation that includes catalog/media files, expose stop-admission plus join-drain on this actual owner. Do not implement a parallel worker/catalog or reuse a normal UI dispose as global migration.

Caches:

* DesktopApplicationImageCacheTrim is a memory-cache/background owner, applying the original 45-second and byte policies; it does not represent all disk caches. Use the actual ImageLoader memory/disk owners, not a new file scan based on "cache" names.
* DesktopDynamicCache has a true stopAccepting → accepted worker drain → shutdownForRestore NonCancellable join barrier at 107. Clear only dynamic_cache/not-interested cache namespaces once its actual actor is quiescent; keep TodayWatch, blocked-UP, history, resume and settings/profile data independent.
* DesktopMediaByteCache/SpanStore owns active playback lease spans, loopback registrations and HTTP readers. Clear/resize must be routed through that owner with active-lease exclusion or a deliberate stop-and-join; never delete an in-use cache directory from Settings UI.
* Image assets/save destinations, crash share cache and downloaded media are separate ownership categories. A cache-size total should enumerate only specific managed cache owners and must disclose omissions; download media/settings backups are not disposable cache.

Restore sequence:

Root beforeRestore already retires original page/resource owners, listen state, dynamic cache and diagnostics writers, search prefs and PluginRuntime/global Store generations (DesktopShell.kt:556–570). Download catalog/media directory migration needs its own await barrier before replacement if its files enter scope. Existing backup archive whitelist and after-success exit barrier must remain authoritative; cancellation before commit remains cancellable, committed restore exits via the accepted-success barrier. Automatic interval/threshold cleanup belongs to application startup after owner initialization and prior to accepting relevant cache work, with exact original interval/threshold policy and a single task—not a composition effect.

Required later evidence: synthetic real disk interrupted migration/rollback, two roots/cold catalog reload, junction and arbitrary selected-folder refusal, active cache lease retention, accepted/queued writer drain, failed settings persistence no publication. No claim of these implementations or tests is made by this design.

# Original dynamic startup and following-list behavior

The installed main source now uses the original startup plan and following-list completion/TTL policies. Feed and live-status requests finish first; startup following hydration then waits 1,200 ms and fetches one page. Selecting UP loads the complete following list, including when that selection arrives during a partial request. Host and visible-feed initialization share the existing timeline request mutex.

The final product compiled and passed nine focused startup/ownership tests. The preceding run passed those nine tests plus the existing ten tab and thirteen timeline tests. Its remaining change was making the retirement field volatile; the final startup suite was repeated afterward. Eleven Python source checks preserve the original declaration bodies and source identities. `source-conformance.json` records the successful tool result, not a separately captured raw Python log.

The frozen JVM probe compiled one fixture source, with twenty fixture classes and no product-class overlap. Three actual product jars precede the same 231 external classpath entries. Eight loaded product classes match both their frozen byte hashes and actual `codeSource`. The probe verifies the feed/live barrier, one-page hydration, complete UP load, same-MID credential-epoch retirement, and timeline initialization deduplication. It opens no native window and uses no real account or network socket.

The first probe attempt failed before compilation because the harness read a long existing dependency path without the Windows extended-path prefix. `path-reading-recovery.json` retains that distinction. Only harness path reading changed; the frozen product and dependency bytes did not.

Evidence files in this folder retain their raw bytes through `.gitattributes`. Product source pins in the integration record use LF-normalized bytes. Frozen product jars, classes, synthetic settings and temporary environments are intentionally local artifacts.

Persistent timeline cache and not-interested state, follow-event invalidation, refresh orchestration, automatic pagination, original gestures/hosts, and final installed-product acceptance remain unfinished. Desktop version 0.2.406.5 has not been replaced. This slice does not increase the manual full-scope estimate of approximately 69%.

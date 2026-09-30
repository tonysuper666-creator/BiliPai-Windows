from pathlib import Path
import subprocess,sys
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
source=(REPO/'desktop/.local/snapshot-native-share-owner-main-01.py').read_text(encoding='utf-8')
source=source.replace("previous=REPO/'desktop/.local/dynamic-resource-navigation/main-product-snapshot-01/manifest.json'","previous=REPO/'desktop/.local/native-share-owner-main-integration/main-product-snapshot-01/manifest.json'")
source=source.replace('4780b40c15bd0de96f01fe5f60086bd2e67684764d075d8af8f437a27b0887c5','9f58369713e6738bea2a6cde03c586768fa414188a82f127b0b0771ca8e50151')
source=source.replace('desktop/.local/native-share-owner-main-integration/main-tests-01.log','desktop/.local/dynamic-follow-main-integration/main-tests-01.log')
source=source.replace("out=REPO/'desktop/.local/native-share-owner-main-integration/main-product-snapshot-01'","out=REPO/'desktop/.local/dynamic-follow-main-integration/main-product-snapshot-01'")
source=source.replace("for name in ['diagnostics.DesktopDiagnosticsTest']:","for name in ['ui.DesktopDynamicTimelineSettingsTest','ui.DesktopDynamicTabsTest','ui.DesktopDynamicCardStateRegistryTest']:")
source=source.replace("phase='native-owner-dispatch-and-callback-retirement-actual-main-integration'","phase='original-follow-event-and-home-observer-actual-main-integration'")
source=source.replace('3ca2b5767f69af7e1f0d3db3f084516376d0bdc3d3119f6548c9c33245a4ea24','fe16610024638f562846e409c549f72542cfb79abed50bf678a2998dbf3d0635')
source=source.replace("sources.add('desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopDynamicResourceNavigationTest.kt')","sources.add('desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopDynamicResourceNavigationTest.kt')\nsources.update(f'desktop/src/test/kotlin/com/bilipai/desktop/ui/{name}.kt'for name in ['DesktopDynamicTimelineSettingsTest','DesktopDynamicTabsTest','DesktopDynamicCardStateRegistryTest'])")
source=source.replace("integrationChanges=['exact HWND owner-thread dispatch and bounded timeout retry','callback lifetime and checked ABI revoke preserves unknown references for real retry','closed storage admission after preparatory COM calls','fresh approved native source build and actual Main trusted asset hash']","integrationChanges=['original success-only follow event and reducers in existing home owners','Root Compose epoch-keyed collection and currentAll sole-cache seam','retired response refuses old rows and restores original cursor owner under existing mutex','original no-replay buffer32 tryEmit false remains server success']")
target=REPO/'desktop/.local/snapshot-dynamic-follow-main-01.py'
target.write_text(source,encoding='utf-8',newline='\n');subprocess.run([sys.executable,str(target)],cwd=REPO,check=True)

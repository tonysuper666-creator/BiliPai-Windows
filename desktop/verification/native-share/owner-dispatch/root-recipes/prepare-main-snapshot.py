from pathlib import Path
import subprocess,sys
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
source=(REPO/'desktop/.local/snapshot-dynamic-resource-navigation-main-01.py').read_text(encoding='utf-8')
source=source.replace("previous=REPO/'desktop/.local/dynamic-editor-main-product-snapshot-01/manifest.json'","previous=REPO/'desktop/.local/dynamic-resource-navigation/main-product-snapshot-01/manifest.json'")
source=source.replace('bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062','4780b40c15bd0de96f01fe5f60086bd2e67684764d075d8af8f437a27b0887c5')
source=source.replace('desktop/.local/dynamic-resource-navigation/main-tests-03.log','desktop/.local/native-share-owner-main-integration/main-tests-01.log')
source=source.replace("out=REPO/'desktop/.local/dynamic-resource-navigation/main-product-snapshot-01'","out=REPO/'desktop/.local/native-share-owner-main-integration/main-product-snapshot-01'")
source=source.replace("for name in ['ui.DesktopDynamicResourceNavigationTest','data.DesktopSpaceRepositoryTest']:","for name in ['diagnostics.DesktopDiagnosticsTest']:")
source=source.replace("phase='original-dynamic-resource-navigation-actual-main-integration'","phase='native-owner-dispatch-and-callback-retirement-actual-main-integration'")
source=source.replace('preparedCompleteManifestSha256Bytes=None',"preparedCompleteManifestSha256Bytes='3ca2b5767f69af7e1f0d3db3f084516376d0bdc3d3119f6548c9c33245a4ea24'")
source=source.replace("integrationChanges=['original DynamicScreen collection/course callbacks with explicit native route dispatch', 'public original favorite folder API and raw resource page; Root route passes title and owner']","integrationChanges=['exact HWND owner-thread dispatch and bounded timeout retry','callback lifetime and checked ABI revoke preserves unknown references for real retry','closed storage admission after preparatory COM calls','fresh approved native source build and actual Main trusted asset hash']")
source=source.replace('8ad04e3d32892376c2987c7ebf3e96a7f58cae14e2e8f6698f600712a9abe16f','2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514')
target=REPO/'desktop/.local/snapshot-native-share-owner-main-01.py'
target.write_text(source,encoding='utf-8',newline='\n');subprocess.run([sys.executable,str(target)],cwd=REPO,check=True)

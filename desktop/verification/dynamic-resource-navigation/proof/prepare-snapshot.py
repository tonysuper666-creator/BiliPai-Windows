from pathlib import Path
import subprocess
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
source=(REPO/'desktop/.local/snapshot-dynamic-editor-main-01.py').read_text(encoding='utf-8')
source=source.replace("previous=REPO/'desktop/.local/native-share-main-product-snapshot-01/manifest.json'","previous=REPO/'desktop/.local/dynamic-editor-main-product-snapshot-01/manifest.json'")
source=source.replace('4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f','bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062')
source=source.replace('desktop/.local/dynamic-editor-root-main-compile-02.log','desktop/.local/dynamic-resource-navigation/main-tests-03.log')
source=source.replace('desktop/.local/dynamic-editor-main-product-snapshot-01\';out.mkdir','desktop/.local/dynamic-resource-navigation/main-product-snapshot-01\';out.mkdir')
source=source.replace("for name in []:","for name in ['ui.DesktopDynamicResourceNavigationTest','data.DesktopSpaceRepositoryTest']:")
source=source.replace('TEST-com.bilipai.desktop.diagnostics.{name}.xml','TEST-com.bilipai.desktop.{name}.xml')
source=source.replace("phase='original-dynamic-editor-actual-main-integration'","phase='original-dynamic-resource-navigation-actual-main-integration'")
source=source.replace("preparedCompleteManifestSha256Bytes='15465a9bf04fe3ac59839bfcf3717a3c35ac2ae45e4073d8452a29e2064c65a2'","preparedCompleteManifestSha256Bytes=None")
source=source.replace("integrationChanges=['original composer and editor repository members', 'same Root modal/session/epoch with shared emotes', 'original AUTH verification after 5 seconds', 'current feed/unread plus detail/space/topic refresh']","integrationChanges=['original DynamicScreen collection/course callbacks with explicit native route dispatch', 'public original favorite folder API and raw resource page; Root route passes title and owner']")
source=source.replace("sourceRows=[dict(","sources.add('desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopDynamicResourceNavigationTest.kt')\nsourceRows=[dict(")
target=REPO/'desktop/.local/snapshot-dynamic-resource-navigation-main-01.py'
target.write_text(source,encoding='utf-8',newline='\n')
subprocess.run([__import__('sys').executable,str(target)],cwd=REPO,check=True)

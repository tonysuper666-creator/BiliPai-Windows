from pathlib import Path
import hashlib,subprocess,sys
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
fixture=REPO/'desktop/.local/native-share-lease-fixture-stable-observation-02.kt'
assert hashlib.sha256(fixture.read_bytes()).hexdigest()=='fdcbfb52bf0c2bc97d0385c8b6c961dde8896fcf9bbdc537207bb96c27f38d66'
(HERE/'NativeShareLeaseFixture.kt').write_bytes(fixture.read_bytes())
source=(REPO/'desktop/.local/native-share-actual-lease-proof-02.py').read_text(encoding='utf-8')
source=source.replace('REPO = HERE.parent.parent',"REPO = next(p for p in HERE.parents if (p/'.git').exists())")
source=source.replace("OUT = HERE / 'native-share-actual-lease-proof-02'","OUT = HERE / 'lease-proof01'")
source=source.replace("HERE / 'native-share-main-product-snapshot-01/manifest.json'","HERE / 'main-product-snapshot-01/manifest.json'")
source=source.replace("HERE / 'native-share-lease-fixture-stable-observation-02.kt'","HERE / 'NativeShareLeaseFixture.kt'")
source=source.replace('4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f','9f58369713e6738bea2a6cde03c586768fa414188a82f127b0b0771ca8e50151')
source=source.replace('a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc','334d1947e200a659f06ab4002bf4b94870bb6ffa369a72a994bd1afbe69d88a2')
source=source.replace("HERE/'source9-appearance/compile-miuix.py'","HERE.parent/'source9-appearance/compile-miuix.py'")
target=HERE/'run-lease-main.py';target.write_text(source,encoding='utf-8',newline='\n')
subprocess.run([sys.executable,str(target)],cwd=REPO,check=True)

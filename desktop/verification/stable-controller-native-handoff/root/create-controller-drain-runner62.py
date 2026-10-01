from pathlib import Path
import hashlib
HERE=Path(__file__).resolve().parent
source=HERE/'controller-drain62/ControllerNativeDrainFixture.kt'
pin=hashlib.sha256(source.read_bytes()).hexdigest()
template=(HERE/'run-actual58-native-publication-fixture.py').read_text(encoding='utf8')
replacements={
"'native-publication-adoption58'":"'controller-drain62'",
'stable-product-snapshot-58':'stable-product-snapshot-62',
"'actual58-native-publication-fixture01'":"'actual62-controller-native-drain-fixture01'",
'7dcc0c79eb816e781313a2dcf3d3c7d17fa14865fa3ece7996575c14b17339ae':'4570296ff7dca1b2fe7d0463c4b1c2cd5016369bf9628f997a801748efab751f',
'504d65ec721ff872dc169229b7fc0cd068e3ac33933070ff69cc81762638ac6c':'f17120b6b06f53f587a094986581682af0a8dd39f70bfd1f62a769c6fa0a7a3c',
'2b416d39a5532d1ee5602855aa10af88b3fa19cfa28093d9649cfda03ef0cf0c':pin,
'NativePublicationAdoptionFixture.kt':'ControllerNativeDrainFixture.kt',
'com.bilipai.desktop.player.NativePublicationAdoptionFixture':'com.bilipai.desktop.ControllerNativeDrainFixture',
'actualProduct=58':'actualProduct=62',
"assert proof['passed'] and proof['assertions'] == 40":"assert proof['passed'] and proof['assertions'] == len(proof['checks']) and proof['assertions'] >= 40",
'assert len(verified_origins) == 3':'assert len(verified_origins) == 4',
'actualProductSnapshot=58':'actualProductSnapshot=62',
'fullControllerDrainAccepted=False':'actualControllerDrainApiAccepted=True, fullControllerFacadeSwitchAccepted=False',
'timeout=55':'timeout=65',
}
for before,after in replacements.items():
 assert before in template,before;template=template.replace(before,after)
out=HERE/'run-actual62-controller-native-drain-fixture.py';assert not out.exists()
out.write_text(template,encoding='utf8',newline='\n')
print(pin)

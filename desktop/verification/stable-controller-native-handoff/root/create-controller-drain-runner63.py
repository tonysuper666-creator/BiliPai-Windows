from pathlib import Path
import hashlib
HERE=Path(__file__).resolve().parent
source=HERE/'controller-drain63/ControllerNativeDrainFixture.kt'
pin=hashlib.sha256(source.read_bytes()).hexdigest()
template=(HERE/'run-actual58-native-publication-fixture.py').read_text(encoding='utf8')
replacements={
"'native-publication-adoption58'":"'controller-drain63'",
'stable-product-snapshot-58':'stable-product-snapshot-63',
"'actual58-native-publication-fixture01'":"'actual63-controller-native-drain-fixture01'",
'7dcc0c79eb816e781313a2dcf3d3c7d17fa14865fa3ece7996575c14b17339ae':'fa0240ab78e15a2cbb87ef36ea82a1ee7dec83310817ead0d9422683d6809d7f',
'504d65ec721ff872dc169229b7fc0cd068e3ac33933070ff69cc81762638ac6c':'499f63dbe5417edf463a90675941e2b4859b7bfd5f6ac5be82dd96314f32448b',
'2b416d39a5532d1ee5602855aa10af88b3fa19cfa28093d9649cfda03ef0cf0c':pin,
'NativePublicationAdoptionFixture.kt':'ControllerNativeDrainFixture.kt',
'com.bilipai.desktop.player.NativePublicationAdoptionFixture':'com.bilipai.desktop.ControllerNativeDrainFixture',
'actualProduct=58':'actualProduct=63',
"assert proof['passed'] and proof['assertions'] == 40":"assert proof['passed'] and proof['assertions'] == len(proof['checks']) and proof['assertions'] >= 40",
'assert len(verified_origins) == 3':'assert len(verified_origins) == 4',
'actualProductSnapshot=58':'actualProductSnapshot=63',
'fullControllerDrainAccepted=False':'actualControllerDrainApiAccepted=True, fullControllerFacadeSwitchAccepted=False',
'timeout=55':'timeout=65',
}
for before,after in replacements.items():
 assert before in template,before;template=template.replace(before,after)
out=HERE/'run-actual63-controller-native-drain-fixture.py';assert not out.exists()
out.write_text(template,encoding='utf8',newline='\n')
print(pin)

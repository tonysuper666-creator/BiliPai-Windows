from pathlib import Path
import hashlib
HERE=Path(__file__).resolve().parent
source=HERE/'controller-drain64/ControllerNativeDrainFixture.kt'
pin=hashlib.sha256(source.read_bytes()).hexdigest()
template=(HERE/'run-actual58-native-publication-fixture.py').read_text(encoding='utf8')
replacements={
"'native-publication-adoption58'":"'controller-drain64'",
'stable-product-snapshot-58':'stable-product-snapshot-64',
"'actual58-native-publication-fixture01'":"'actual64-controller-native-drain-fixture01'",
'7dcc0c79eb816e781313a2dcf3d3c7d17fa14865fa3ece7996575c14b17339ae':'5f93c921297a6571ef6074f6b3afb364ff1c20ca09a8f3e17db15133dde2bf42',
'504d65ec721ff872dc169229b7fc0cd068e3ac33933070ff69cc81762638ac6c':'58d593ab155d1a864b3213cc35e7c633fae7b7f6f55b8b8c22f49fcc71613701',
'2b416d39a5532d1ee5602855aa10af88b3fa19cfa28093d9649cfda03ef0cf0c':pin,
'NativePublicationAdoptionFixture.kt':'ControllerNativeDrainFixture.kt',
'com.bilipai.desktop.player.NativePublicationAdoptionFixture':'com.bilipai.desktop.ControllerNativeDrainFixture',
'actualProduct=58':'actualProduct=64',
"assert proof['passed'] and proof['assertions'] == 40":"assert proof['passed'] and proof['assertions'] == len(proof['checks']) and proof['assertions'] >= 40",
'assert len(verified_origins) == 3':'assert len(verified_origins) == 4',
'actualProductSnapshot=58':'actualProductSnapshot=64',
'fullControllerDrainAccepted=False':'actualControllerDrainApiAccepted=True, fullControllerFacadeSwitchAccepted=False',
'timeout=55':'timeout=65',
}
for before,after in replacements.items():
 assert before in template,before;template=template.replace(before,after)
out=HERE/'run-actual64-controller-native-drain-fixture.py';assert not out.exists()
out.write_text(template,encoding='utf8',newline='\n')
print(pin)

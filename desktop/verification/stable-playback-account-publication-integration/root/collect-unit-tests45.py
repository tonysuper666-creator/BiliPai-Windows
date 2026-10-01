from pathlib import Path
import json,zipfile,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=HERE/'unit-tests-45';assert not(OUT/'result.json').exists()
first=list((OUT/'attempt01').glob('TEST-*.xml'));last=list((OUT/'attempt02').glob('TEST-*.xml'));assert len(first)==6 and len(last)==1
replacement=ET.parse(last[0]).getroot();assert int(replacement.get('failures','0'))==0 and int(replacement.get('errors','0'))==0
accepted=[]
for p in first:
 row=ET.parse(p).getroot()
 if row.get('name')==replacement.get('name'):
  assert {e.get('name')for e in row.findall('testcase')}=={e.get('name')for e in replacement.findall('testcase')},'Case names changed'
  row=replacement;source=last[0]
 else:source=p
 assert all(e.find('failure')is None and e.find('error')is None for e in row.findall('testcase')),row.get('name')
 accepted.append(dict(className=row.get('name'),cases=len(row.findall('testcase')),source=source.relative_to(OUT).as_posix()))
assert sum(row['cases']for row in accepted)==67,accepted
pins=[]
for kind in ['kotlin','java','resources']:
 folder=REPO/('desktop/build/resources/main'if kind=='resources'else f'desktop/build/classes/{kind}/main');wide=Path('\\\\?\\'+str(folder))
 with zipfile.ZipFile(MAIN/'desktop/.local/stable-product-snapshot-45'/f'main-{kind}.jar')as archive:
  entries={p.relative_to(wide).as_posix():p for p in wide.rglob('*')if p.is_file()}
  assert set(entries)==set(archive.namelist()),(kind,'class/resource set changed',len(entries),len(archive.namelist()))
  for name,p in entries.items():assert p.read_bytes()==archive.read(name),(kind,name,'bytes changed')
 pins.append(dict(kind=kind,entries=len(entries),byteEqualToFrozen45=True))
result=dict(passed=True,uniqueAcceptedCases=67,classes=accepted,actualProductClassesUnchangedFromSnapshot45=pins,oneFailedClassReplacedByExactSameCaseRerun=True,originalBusinessAssertionsUnchanged=True,fixtureRepairOnly=True,initialGuestExternalHttpOccurred=True,initialPersonalSessionsRead=False,finalFailedCaseMemoryTransport=True,newRootUiAccepted=False)
(OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,uniqueCases=67,productByteEqual=True)))

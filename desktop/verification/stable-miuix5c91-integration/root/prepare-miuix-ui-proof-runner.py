from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;p=HERE/'prove-full-dock-actual-product.py';source=p.read_text(encoding='utf-8')
old="OUT=MAIN/f'desktop/.local/stable-full-dock-main{phase}-ui-proof'";new="OUT=MAIN/f'desktop/.local/stable-miuix5c91-main{phase}-dock-ui-proof'"
assert source.count(old)==1;source=source.replace(old,new,1)
old="metadata['sourceRegistryCount']==765";new="metadata['sourceRegistryCount']==770";assert source.count(old)==1;source=source.replace(old,new,1)
old="fork=next(r['path'] for r in cp if 'miuix5157-jvm-' in r['path'])"
new=old+"\nassert '0.9.4-5c91d5e5-windows-source1' in fork"
assert source.count(old)==1;source=source.replace(old,new,1)
out=HERE/'prove-miuix5c91-dock-product.py';assert not out.exists();out.write_bytes(source.encode())
report=dict(originalRunnerSha256Bytes=hashlib.sha256(p.read_bytes()).hexdigest(),newRunnerSha256Bytes=hashlib.sha256(out.read_bytes()).hexdigest(),changes=['new independent cohort path','require actual snapshot770 source identities','require exact5c91 source-built dependency artifact'],behaviorAssertionsChanged=False,productionClassOverrides=0)
(HERE/'miuix5c91-proof-runner-adaptation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))

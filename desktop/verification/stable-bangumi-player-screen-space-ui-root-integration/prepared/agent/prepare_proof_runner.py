from pathlib import Path
import os,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent
def wide(p):
 s=os.path.abspath(str(p));prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
body=wide(P.parent/'stable-original-bangumi-player-native-owner-root-parity/proof.py').read_text(encoding='utf8')
for a,b in [('stable-product-snapshot-88','stable-product-snapshot-89'),('fb097cce71237ce93b6220940a5e930c9672239511f3937d037ebc05af0939d2','aa28f253a357c107073d20ef279f28716a96dfa4015aca2be180f1865b14340b'),('0252dc7a049e87084a0510bf406808188648f759f977cfd453f0803ed7e1a62c','63b3d1693889263d164badad1a3e45aa7b09168181be74cb1eff83b7a82bffb5'),("else'04'","else'11'"),("snapshot']==88","snapshot']==89"),('snapshot=88','snapshot=89'),('BangumiNativeOwnerProof','BangumiPlayerClosureProof'),('fullScreenPrepared=False','fullScreenPrepared=True'),('Complete PGC physical plans plus actual original Store/native-owner negative admissions on unmounted MPV; no native transport command ACK/frame','Complete original Player policies/PUGV/final reporter failure, epoch, privacy and cancellation cases plus actual original Store/native-owner admissions on unmounted MPV; no network-success/native transport ACK/frame')]:
 assert a in body,a;body=body.replace(a,b)
wide(P/'proof.py').write_text(body,encoding='utf8',newline='\n')
print('Prepared hash-pinned actual89 narrow proof runner; separate from normal/native/Root account acceptance')
for a,b in [('stable-product-snapshot-89','stable-product-snapshot-90'),('aa28f253a357c107073d20ef279f28716a96dfa4015aca2be180f1865b14340b','4cf1bb27f4615aa602a1b37d2fa939acadeb18c2ba9d552dd819cfb1cfe5dd46'),('63b3d1693889263d164badad1a3e45aa7b09168181be74cb1eff83b7a82bffb5','30455d62eb5934c9fed3478e7cfc8486ff79f8ada35cbc80ca1cdb5a66ba8633'),("else'11'","else'12'"),("snapshot']==89","snapshot']==90"),('snapshot=89','snapshot=90')]:
 assert a in body,a;body=body.replace(a,b)
wide(P/'proof.py').write_text(body,encoding='utf8',newline='\n')
print('Advanced future proof runner to actual90; prior proof01/snapshot89 results retained')

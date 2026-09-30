from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
DELTA=HERE/'boundary-delta'
parent=json.loads((HERE/'install-plan.json').read_text(encoding='utf-8'))
contract=json.loads((DELTA/'delta-contract.json').read_text(encoding='utf-8'))
plan=dict(frozenChecks=parent['frozenChecks'],dependencies=parent['dependencies'],originalInputs=parent['originalInputs'],
    platformInputs=parent['platformInputs'],files=contract['files'],afterInstallPlanSha256Bytes=contract['afterInstallPlanSha256Bytes'])
text=json.dumps(plan,ensure_ascii=False,indent=2)+'\n';(DELTA/'install-plan.json').write_bytes(text.encode('utf-8'))
pin=hashlib.sha256(text.encode()).hexdigest()
script='''"""Read-only by default; Root applies only after the reviewed ten-file producer install."""
from pathlib import Path
import argparse,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('diagnosticdelta',HERE.parent/'install-reviewed.py')
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)
installer.HERE=HERE
PIN='''+repr(pin)+'''
def load_plan():
    path=HERE/'install-plan.json'
    if installer.digest(path)!=PIN:raise ValueError('Reviewed delta plan changed')
    return json.loads(path.read_text(encoding='utf-8'))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,default=installer.DEFAULT_REPO);p.add_argument('--apply',action='store_true');args=p.parse_args()
    print(json.dumps(installer.install(args.repo.resolve(),load_plan(),args.apply)))
'''
(DELTA/'install-boundary-delta.py').write_text(script,encoding='utf-8',newline='\n')
print(json.dumps(dict(passed=True,deltaPlanSha256Bytes=pin)))

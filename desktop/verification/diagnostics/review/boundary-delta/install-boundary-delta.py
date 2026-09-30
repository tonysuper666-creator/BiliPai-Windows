"""Read-only by default; Root applies only after the reviewed ten-file producer install."""
from pathlib import Path
import argparse,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('diagnosticdelta',HERE.parent/'install-reviewed.py')
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)
installer.HERE=HERE
PIN='f17e9f0f1321df784d3d0b4faeef3e0c43d4fa9fccb941412881f37b469b8616'
def load_plan():
    path=HERE/'install-plan.json'
    if installer.digest(path)!=PIN:raise ValueError('Reviewed delta plan changed')
    return json.loads(path.read_text(encoding='utf-8'))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,default=installer.DEFAULT_REPO);p.add_argument('--apply',action='store_true');args=p.parse_args()
    print(json.dumps(installer.install(args.repo.resolve(),load_plan(),args.apply)))

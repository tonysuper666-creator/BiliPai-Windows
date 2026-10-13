"""Extend the existing sole Home producer with fixed complete refresh APIs."""
from pathlib import Path
import argparse,subprocess,sys
from v036_comment_refresh_dependencies import extend_same_home_producer

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--test-output',type=Path,required=True)
    args=p.parse_args()
    command=[sys.executable,str(args.repo/'desktop/tools/extract-upstream-home-page.py'),
             '--repo',str(args.repo),'--output',str(args.output)]
    result=subprocess.run(command)
    if result.returncode:return result.returncode
    extend_same_home_producer(args.output,args.test_output)
    return 0

if __name__=='__main__':raise SystemExit(main())

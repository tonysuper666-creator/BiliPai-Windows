#!/bin/sh
# usage: python-test.sh <repo-root> <unittest args...>, e.g.
#   python-test.sh . discover -s desktop/tools/nvidia -p "test_nvidia_*.py"
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "${1:?repo root}" && shift
PYTHONUTF8=1 PYTHONPATH="$HERE/pyshim:desktop/tools" exec python3 -B -m unittest "$@"

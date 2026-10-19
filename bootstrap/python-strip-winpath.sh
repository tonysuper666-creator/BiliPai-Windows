#!/bin/sh
# Used as PYTHON_EXECUTABLE on Linux. Preloads pyshim/sitecustomize.py, which strips the Windows
# long-path prefix (\\?\ and \\?\UNC\) that repo generators prepend unconditionally.
# PYTHONPATH is inherited, so sys.executable child processes get the shim too.
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHONPATH="$HERE/pyshim${PYTHONPATH:+:$PYTHONPATH}" exec /usr/bin/python3 "$@"

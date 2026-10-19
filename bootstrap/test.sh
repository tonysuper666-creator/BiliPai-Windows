#!/bin/bash
# usage: test.sh <repo-root> --tests <pattern> [--tests ...]   (adds -x prepareJsWorkerResources and --rerun)
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=${1:?repo root}; shift
exec "$HERE/gradle.sh" "$REPO" test -x prepareJsWorkerResources --continue --rerun "$@"

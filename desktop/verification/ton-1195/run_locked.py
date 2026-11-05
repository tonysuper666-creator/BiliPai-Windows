#!/usr/bin/env python3
"""Run an existing Gradle command under the Mac-wide Coach/BiliPai heavy-build lease."""
import os
from pathlib import Path
import subprocess
import sys

repo, log_path, *tasks = sys.argv[1:]
lease = Path.home() / "dev/.coach-heavy.lock"
lease.mkdir()  # Fail rather than steal or silently wait on another build.
owner = lease / "owner"
identity = f"PID={os.getpid()} TON-1195 {Path(repo).resolve().name}"
try:
    owner.write_text(identity + "\n")
    env = dict(os.environ, JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
    command = ["./gradlew", "-p", "desktop", "--no-daemon", "--console=plain", *tasks]
    with Path(log_path).open("w") as log:
        log.write("COMMAND: " + " ".join(command) + "\n")
        log.flush()
        result = subprocess.run(command, cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT)
        log.write(f"\nEXIT_CODE: {result.returncode}\n")
    print(f"exit={result.returncode} log={log_path}", flush=True)
    sys.exit(result.returncode)
finally:
    if owner.exists() and owner.read_text().strip() == identity:
        owner.unlink()
        lease.rmdir()

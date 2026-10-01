from pathlib import Path
import subprocess,sys
P=Path(__file__).resolve().parent
for name in ['prepare-composer.py','prepare-pure.py','prepare-adapters.py','prepare-settings.py','prepare-holder.py','prepare-platform-effects.py','prepare-missing.py','prepare-settings-more.py','map-settings.py','prepare-context-delta.py','adapt-followups.py']:
 r=subprocess.run([sys.executable,str(P/name)],capture_output=True)
 if r.returncode:sys.stdout.buffer.write(r.stdout+r.stderr);raise SystemExit(r.returncode)
 print(name,'PASS')

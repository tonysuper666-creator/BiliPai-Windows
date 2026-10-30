// Preserve the operator CLI; Python supplies an OS-owned flock, released on exit
// or process death. No stale mkdir lock is inferred or forcibly removed.
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const child = spawn('python3',[fileURLToPath(new URL('./invitation.py',import.meta.url)),...process.argv.slice(2)],{stdio:'inherit'});
for (const signal of ['SIGINT','SIGTERM']) process.on(signal,()=>child.kill(signal));
child.on('error',error=>{ console.error(error.message); process.exitCode=1; });
child.on('exit',(code,signal)=>{ process.exitCode=code ?? (signal ? 1 : 0); });

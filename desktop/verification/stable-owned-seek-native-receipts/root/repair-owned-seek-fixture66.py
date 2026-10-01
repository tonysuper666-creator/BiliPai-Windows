from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
source=HERE/'owned-seek66/TypedSeekFixture.kt';raw=source.read_bytes()
assert hashlib.sha256(raw).hexdigest()=='4c97e81dfb5949f492d49ab22b7faafc5242faa99a9249cf25c0c78df62d6dc1'
s=raw.decode()
changes=[
    ('publication.attempt(base,{alive.get()}){synchronized(entry){if(alive.get())command()}}',
     'publication.tryAdmit(base,{alive.get()}){synchronized(entry){if(alive.get()){command();true}else false}}'),
    ('publication.attempt(source,{alive.get()}){','publication.tryAdmit(source,{alive.get()}){'),
    ('throw CancellationException("Entry retired");block()}','throw CancellationException("Entry retired");block();true}'),
    ('f.publication.attempt(f.base,{f.alive.get()}){synchronized(f.entry){if(f.alive.get())command()}}',
     'f.publication.tryAdmit(f.base,{f.alive.get()}){synchronized(f.entry){if(f.alive.get()){command();true}else false}}'),
    ('f.player.recoverSource(ticket.sourceVersion,0.3,true)',
     'f.player.recoverSource(ticket.sourceVersion,positionSeconds=0.3,paused=true)'),
]
for before,after in changes:
    assert s.count(before)==1,before
    s=s.replace(before,after)
lane=HERE/'owned-seek66-02';assert not lane.exists();lane.mkdir()
new_source=lane/source.name;new_source.write_bytes(s.encode());pin=hashlib.sha256(new_source.read_bytes()).hexdigest()
r=(HERE/'run-actual66-owned-seek-fixture.py').read_text()
for before,after in [('owned-seek66\'', 'owned-seek66-02\''),('actual66-owned-seek-fixture01','actual66-owned-seek-fixture02'),
    ('4c97e81dfb5949f492d49ab22b7faafc5242faa99a9249cf25c0c78df62d6dc1',pin)]:
    assert before in r,before;r=r.replace(before,after)
runner=HERE/'run-actual66-owned-seek-fixture02.py';assert not runner.exists();runner.write_bytes(r.encode())
print(json.dumps(dict(sourceSha256Bytes=pin,runnerSha256Bytes=hashlib.sha256(runner.read_bytes()).hexdigest(),
    firstAttemptWasFixtureCompileFailure=True,productionSourceChanged=False)))

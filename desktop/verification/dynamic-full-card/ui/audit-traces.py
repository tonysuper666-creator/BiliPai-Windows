"""Audit original request shapes and actual raw checkpoints without execution."""
from pathlib import Path
import argparse,json
p=argparse.ArgumentParser();p.add_argument('--attempt',required=True);a=p.parse_args()
assert a.attempt.isalnum()
proof=Path(__file__).resolve().parent/'runs'/a.attempt/'proof'
def ext(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def load(path):return json.loads(ext(path).read_text(encoding='utf-8'))
out=proof/'trace-conformance.json';assert not ext(out).exists(),'Do not rewrite an audited attempt'
posts=['/x/dynamic/feed/dyn/thumb','/x/dynamic/feed/dyn/thumb','/x/dynamic/feed/create/dyn','/x/dynamic/feed/operate/remove']
gets={'/x/emote/user/panel/web','/x/polymer/web-dynamic/v1/feed/all','/dynamic_svr/v1/dynamic_svr/w_dyn_uplist','/xlive/web-ucenter/user/following','/x/relation/followings','/x/polymer/web-dynamic/v1/feed/space'}
checks=[]
for cell in ['material3-light','material3-dark','miuix-light','miuix-dark']:
    directory=proof/cell;trace=load(directory/'request-trace.json')
    up=[row for row in trace if row['path']=='/x/polymer/web-dynamic/v1/feed/space']
    assert len(up)==2 and [row['query']['host_mid'] for row in up]==['77','42']
    assert all(row['query']['offset']=='' for row in up)
    mutations=[row for row in trace if row['method']=='POST']
    assert [row['path'] for row in mutations]==posts
    assert [row['syntheticBody']['up'] for row in mutations[:2]]==[1,2]
    assert all(row['syntheticBody']['dyn_id_str']=='123' for row in mutations[:2])
    assert mutations[2]['syntheticBody']['dyn_req']['scene']==4
    assert mutations[2]['syntheticBody']['web_repost_src']['dyn_id_str']=='123'
    assert mutations[3]['syntheticBody']=={'dyn_id_str':'125','dyn_type':4,'rid_str':'125'}
    for row in trace:
        path=row['path'];expected='api.live.bilibili.com' if path.startswith('/xlive/') else 'api.vc.bilibili.com' if path.startswith('/dynamic_svr/') else 'api.bilibili.com'
        assert row['host']==expected
        assert row['method']==('POST' if path in posts else 'GET')
        assert path in gets or path in posts
        assert 'csrf' not in row['query']
        if 'csrf_token' in row['query']:assert row['query']['csrf_token']=='task-only-dynamic-csrf'
        assert not any(key.lower() in {'cookie','sessdata','authorization'} for key in row)
    raw=[load(directory/('raw-'+name+'.json')) for name in ['02-like','03-unlike','04-repost','05-unfold','06-not-interested','07-delete']]
    for row in raw:
        assert row['allRaw']==row['cachedRaw']
        assert row['allHasMore'] is False and row['allInitialized'] is True and row['allBusy'] is False
    assert len(load(directory/'pointers.json'))==19
    checks.append({'cell':cell,'passed':True,'upMids':['77','42'],'upCalls':2,'posts':4,
      'methodAndHostMatchOriginalAPI':True,'checkpointCacheEqualsAllCount':6,'actualPointerPairs':19,'syntheticCsrfTokenOnly':True})
ext(out).write_text(json.dumps({'passed':True,'derivedFrom':'actual request-trace and checkpoint JSON artifacts','checks':checks},indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'passed':True,'cells':4,'rawCacheEqualityChecks':24,'pointerPairs':76},ensure_ascii=False))

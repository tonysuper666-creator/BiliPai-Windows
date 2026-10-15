"""Fetch the pinned delivered updater fixture; never extract or execute it."""
from pathlib import Path
import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
import tempfile
import urllib.error
import urllib.parse
import urllib.request

spec=importlib.util.spec_from_file_location('updater_baseline_pin',Path(__file__).with_name('verify-updater-baseline.py'))
baseline=importlib.util.module_from_spec(spec);spec.loader.exec_module(baseline)
ALLOWED_ASSET_HOSTS={'release-assets.githubusercontent.com','objects.githubusercontent.com'}
MAX_METADATA=1024*1024

def require(condition,message):
    if not condition:raise ValueError(message)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,hdrs,newurl):return None

def open_url(url,headers):
    try:return urllib.request.build_opener(NoRedirect()).open(urllib.request.Request(url,headers=headers),timeout=30)
    except urllib.error.HTTPError as error:
        if error.code not in (301,302,303,307,308):raise
        return error

def fetch(output,token,pin=baseline.BASELINE,transport=open_url):
    require(pin['repository']=='tonysuper666-creator/BiliPai-Windows','Unexpected fixture repository')
    root='https://api.github.com/repos/'+pin['repository']
    headers={'Accept':'application/vnd.github+json','User-Agent':'BiliPai-Windows-Updater-Fixture',
        'X-GitHub-Api-Version':'2022-11-28'}
    if token:headers['Authorization']='Bearer '+token
    def metadata(url):
        with contextlib.closing(transport(url,dict(headers))) as response:
            require(response.status==200,'Fixture metadata request failed')
            raw=response.read(MAX_METADATA+1);require(len(raw)<=MAX_METADATA,'Fixture metadata exceeds bound')
            return json.loads(raw)
    repo=metadata(root)
    require(repo.get('full_name')==pin['repository'] and repo.get('private') is False,'Fixture repository must be the owned public repository')
    release=metadata(root+'/releases/'+str(pin['releaseId']))
    require(release.get('id')==pin['releaseId'] and release.get('draft') is False and
        release.get('tag_name')==pin['releaseTag'] and release.get('target_commitish')==pin['sourceSha'] and
        bool(release.get('published_at')),'Fixture release identity differs from the delivered baseline')
    assets=release.get('assets',[])
    require(len(assets)==2 and len({a.get('id')for a in assets})==2,'Fixture assets are missing or ambiguous')
    for expected in (dict(assetId=pin['assetId'],filename=pin['filename'],bytes=pin['bytes'],sha256=pin['sha256']),pin['sourceArchive']):
        matches=[a for a in assets if a.get('id')==expected['assetId']]
        require(len(matches)==1,'Pinned fixture asset missing')
        asset=matches[0]
        require(asset.get('state')=='uploaded' and asset.get('name')==expected['filename'] and
            asset.get('size')==expected['bytes'] and asset.get('digest')=='sha256:'+expected['sha256'] and
            asset.get('url')==root+'/releases/assets/'+str(expected['assetId']),'Fixture asset identity differs from the delivered baseline')
    output=Path(os.path.abspath(output));missing=[];existing=output
    while not existing.exists():missing.append(existing);existing=existing.parent
    baseline.existing_without_links(existing)
    for directory in reversed(missing):directory.mkdir()
    baseline.existing_without_links(output)
    require(Path(pin['filename']).name==pin['filename'],'Invalid baseline filename')
    destination=output/pin['filename']
    if destination.exists():return baseline.verify(destination,pin)
    response=transport(root+'/releases/assets/'+str(pin['assetId']),dict(headers,Accept='application/octet-stream'))
    if response.status in (301,302,303,307,308):
        try:target=urllib.parse.urlsplit(response.headers['Location'])
        finally:response.close()
        require(target.scheme=='https' and target.hostname in ALLOWED_ASSET_HOSTS and target.port in (None,443) and
            target.username is None and target.password is None,'Untrusted fixture asset redirect')
        response=transport(target.geturl(),{'User-Agent':headers['User-Agent']})
    temporary=None
    try:
        with contextlib.closing(response):
            require(response.status==200,'Fixture archive request failed')
            length=response.headers.get('Content-Length')
            require(length is None or int(length)==pin['bytes'],'Fixture archive content length differs')
            digest=hashlib.sha256();size=0
            with tempfile.NamedTemporaryFile(mode='wb',prefix='.bilipai-updater-fixture-',dir=output,delete=False)as stream:
                temporary=Path(stream.name)
                while block:=response.read(1024*1024):
                    size+=len(block);require(size<=pin['bytes'],'Fixture archive exceeds bound')
                    digest.update(block);stream.write(block)
                stream.flush();os.fsync(stream.fileno())
            require(size==pin['bytes'] and digest.hexdigest()==pin['sha256'],'Fixture archive hash or size differs')
            # Publish the verified bytes without replacing a file another owner created.
            os.link(temporary,destination)
        return baseline.verify(destination,pin)
    finally:
        if temporary is not None:temporary.unlink(missing_ok=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    require(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')==baseline.BASELINE['repository'],
        'Fixture fetch is restricted to the owned repository CI')
    print(json.dumps(fetch(args.output,os.environ.get('GITHUB_TOKEN',''))))

if __name__=='__main__':main()

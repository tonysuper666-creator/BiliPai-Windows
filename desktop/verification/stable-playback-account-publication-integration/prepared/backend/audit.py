from pathlib import Path
import hashlib,json,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
rows=[]
for change in json.loads(read(HERE/'local-hunks.json')):
 candidate=read(HERE/'proof-only'/change['path']);assert sha(candidate)==change['candidateLF']
 inverse=candidate
 for hunk in reversed(change['hunks']):
  assert inverse.count(hunk['after'])==hunk['occurrences'],(change['path'],hunk['after'][:60])
  inverse=inverse.replace(hunk['after'],hunk['before'])
 assert sha(inverse)==change['baseLF'] and inverse==read(REPO/change['path']),change['path']
 rows.append(dict(path=change['path'],baseLF=change['baseLF'],candidateLF=change['candidateLF'],exactInverseByteEqual=True,declaredHunks=len(change['hunks'])))
inventory=json.loads(read(HERE/'source-inventory.json'));original=read(REPO/inventory['originalCookieJar']['path'])
manual=read(HERE/'prepared'/inventory['newManual']['path'])
selected=manual[manual.index('internal class DesktopOriginalPlaybackAccountCookieJar'):].removesuffix('\n').replace('internal class DesktopOriginalPlaybackAccountCookieJar','private class PlaybackAccountCookieJar',1)
assert selected in original and sha(selected)==inventory['originalCookieJar']['selectedBodySha256LF']
comp=json.loads(read(HERE/'runs/compile-04/compile-result.json'))
assert comp['status']=='PASS' and not comp['illegalMarkerClasses']
result=dict(status='PASS',sourceOnly=True,existingSourceFamilies=rows,originalCookieJarBodyByteEqualExceptDeclarationNameAndVisibility=True,
 originalCookieJarPath=inventory['originalCookieJar']['path'],originalCookieJarBodySha256LF=sha(selected),
 newAccountStore=False,newHTTPClient=False,newURLCache=False,newAccountCatalog=False,originalDTOOrAPIOverrides=False,
 candidateJarSha256=comp['jarSha256'],candidateClassCount=len(comp['classes']),explicitProductionClassIntersections=comp['productionClassIntersections'],
 overlapExplanation='All intersections are deliberate full proof copies of five existing platform families; installation is exact local hunks only. Original API/model payloads remain actual39.',
 noRootMountedOrNativeAcceptance=True,HTTP=False,GUI=False,Gradle=False)
safe(HERE/'source-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print('PASS inverse 5 families + full original isolated CookieJar; candidate classes='+str(len(comp['classes']))+' explicit intersections='+str(len(comp['productionClassIntersections'])))

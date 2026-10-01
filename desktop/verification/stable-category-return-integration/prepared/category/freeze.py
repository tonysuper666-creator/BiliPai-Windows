from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def main():
 target=HERE/'frozen-handoff.json';assert not target.exists()
 assert json.loads(safe(HERE/'source-checks.json').read_text(encoding='utf-8'))['status']=='PASS'
 rows=[dict(path=p.relative_to(HERE).as_posix(),bytes=safe(p).stat().st_size,sha256Bytes=sha(p)) for p in sorted(HERE.rglob('*')) if safe(p).is_file() and '__pycache__' not in p.parts and p!=target]
 value=dict(frozen=True,scope='Prepared full original Category source and prospective owned VM proof; not actual Root/UI acceptance',
  upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',originalCategorySha256LF='fc7bb877d961633bb0a3fe8aaa4ceb15476b394d0f2d3a3ffb3bc72acbaffc96',
  artifactCount=len(rows),artifacts=rows,installSources=['prepared/desktop/tools/extract-upstream-category-page.py','prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCategoryEnvironment.kt','prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCategoryRouteHost.kt'],
  existingSoleProducerExactHunks='sole-helper-producer-hunks.json',referencesNotInstalled='reference-dependencies.json',contract='ROOT-INTEGRATION.md',registryMerge='registry-recipe.json',
  prospectiveCompilePassed=True,prospectiveVmAssertions=16,actualCategoryRootRuntimeAccepted=False,GUI=False,HWND=False,HTTP=False,Gradle=False)
 safe(target).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 for r in rows:assert sha(HERE/r['path'])==r['sha256Bytes']
 print(json.dumps(dict(manifest=str(target),sha256Bytes=sha(target),artifacts=len(rows),contractSha256Bytes=sha(HERE/'ROOT-INTEGRATION.md'),checksSha256Bytes=sha(HERE/'source-checks.json')),indent=2))
if __name__=='__main__':main()

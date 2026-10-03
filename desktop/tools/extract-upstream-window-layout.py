from pathlib import Path
import argparse,hashlib,json,os
RECIPES=[{'source': 'design-system/src/main/java/com/android/purebilibili/core/ui/WindowRegionLayout.kt', 'target': 'com/android/purebilibili/core/ui/WindowRegionLayout.kt', 'sourceSha256LF': '94fd4a48c231421301aaa38f2a170e442d7279879a4d7d205439f5898d13aabb', 'generatedSha256LF': '94fd4a48c231421301aaa38f2a170e442d7279879a4d7d205439f5898d13aabb', 'edits': []}, {'source': 'app/src/main/java/com/android/purebilibili/core/ui/adaptive/AppHingeSafeContent.kt', 'target': 'com/android/purebilibili/core/ui/adaptive/AppHingeSafeContent.kt', 'sourceSha256LF': '858c98b1f200ff45e4f233fec647ae243248ecbbc8fbbf4b10e42714bec85699', 'generatedSha256LF': '5dfb9ad566bb8c5e3aeac98bb06c1b384206fc48ef8232a31c678bcedb409792', 'edits': [{'before': 'android.util.Log.d(', 'after': 'com.android.purebilibili.core.util.Logger.d(', 'reason': 'Existing JVM logging boundary'}]}, {'source': 'app/src/main/java/com/android/purebilibili/core/util/HingeLayoutPolicy.kt', 'target': 'com/android/purebilibili/core/util/DesktopOriginalWindowHingeRegionPolicies.kt', 'sourceSha256LF': 'd178d8e69ca1fa91d36dd4a11445f229cba277b9a71e255e5193f61ff9012d9f', 'generatedSha256LF': '6cf14b5a0212e315bb35d913c373c871c28544839cfbc585dea1a85898ed2456', 'edits': [{'before': 'data class AppHingeFeature(\n    val orientation: AppHingeOrientation,\n    val bounds: IntRect,\n    val isSeparating: Boolean,\n    val isOccluding: Boolean,\n    val isFlat: Boolean,\n)', 'after': '', 'reason': 'Unchanged AppHingeFeature remains with its existing sole dynamic-detail-container producer'}]}, {'source': 'app/src/main/java/com/android/purebilibili/core/ui/adaptive/AppHingePaneLayout.kt', 'target': 'com/android/purebilibili/core/ui/adaptive/AppHingePaneLayout.kt', 'sourceSha256LF': 'cece26eba064a8801b1da5bc760ed1f292789953a7f38161de860544ee4fd0ee', 'generatedSha256LF': 'cece26eba064a8801b1da5bc760ed1f292789953a7f38161de860544ee4fd0ee', 'edits': []}, {'source': 'app/src/main/java/com/android/purebilibili/core/ui/adaptive/AppHingeSafeSidePanel.kt', 'target': 'com/android/purebilibili/core/ui/adaptive/AppHingeSafeSidePanel.kt', 'sourceSha256LF': '7eb438bfe59d3e3401d878e9c24a480cc1848a298f22dd437193eb8f8b27134a', 'generatedSha256LF': '7eb438bfe59d3e3401d878e9c24a480cc1848a298f22dd437193eb8f8b27134a', 'edits': []}]
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith(chr(92)*2+'?'+chr(92))else chr(92)*2+'?'+chr(92)+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--existing-hinge-model',type=Path,required=True);a=p.parse_args()
 records=[]
 for r in RECIPES:
  source=wide(a.repo/r['source']).read_text(encoding='utf8').replace('\r\n','\n');assert sha(source)==r['sourceSha256LF'],r['source']
  body=source;positions=[]
  for e in r['edits']:
   assert body.count(e['before'])==1,e['reason']
   if e['after']=='':assert e['before'] in wide(a.existing_hinge_model).read_text(encoding='utf8').replace('\r\n','\n')
   at=body.index(e['before']);positions.append((at,e));body=body[:at]+e['after']+body[at+len(e['before']):]
  inverse=body
  for at,e in reversed(positions):assert inverse[at:at+len(e['after'])]==e['after'];inverse=inverse[:at]+e['before']+inverse[at+len(e['after']):]
  assert inverse==source and sha(body)==r['generatedSha256LF']
  t=wide(a.output/r['target']);t.parent.mkdir(parents=True,exist_ok=True);t.write_text(body,encoding='utf8',newline='\n')
  records.append(dict(source=r['source'],output=r['target'],sourceSha256LF=sha(source),generatedSha256LF=sha(body),completeOriginalInverse=True))
 t=wide(a.output/'window-layout-replay.json');t.write_text(json.dumps(dict(files=records),indent=2)+'\n',encoding='utf8',newline='\n')
 print('Three complete original window/hinge source inverses verified; existing hinge model reused')
if __name__=='__main__':main()

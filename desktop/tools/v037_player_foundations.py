"""Complete fixed original subtitle and player UI foundations."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='f207251037ba4f379b7570464fb51a5854cd871bea8e61efc4865d082d36d796'
PINS=[{'path': 'core-data/src/main/java/com/android/purebilibili/core/subtitle/BiliSubtitlePolicy.kt', 'filename': 'BiliSubtitlePolicy.kt', 'bytes': 19724, 'sha256Bytes': '7c531457d7477c83f3196eff3fa9cc113f24e544e89c249acf6c21deb035a035', 'gitBlob': 'ed24a85d5e991296f88bfda28f464731641f80f2'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppHorizontalPager.kt', 'filename': 'AppHorizontalPager.kt', 'bytes': 3347, 'sha256Bytes': 'a3b7a3c21df37fb2bbdf7821dbdd64140544c49fd82d3f60fa4d808d8eb55126', 'gitBlob': '916fd3f82836d17832514258b39e3da6698543e2'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppColorPicker.kt', 'filename': 'AppColorPicker.kt', 'bytes': 3618, 'sha256Bytes': 'f64643940c299391056d4e0e4aa6a9cd99413ccecbe17b0c8505bfcd4ff23d82', 'gitBlob': '026fc86b1e66480287023e117b458c0774ca9628'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/PiliPlusDanmakuSendIcon.kt', 'filename': 'PiliPlusDanmakuSendIcon.kt', 'bytes': 4390, 'sha256Bytes': 'd607bd225d7d637752493bcf1545dcb4b8f70d83185e59630cd2566dc6605163', 'gitBlob': '8139589fd6de47a3c89d60f02d1690fd21380396'}, {'path': 'app/src/test/java/com/android/purebilibili/feature/video/subtitle/BiliSubtitlePolicyTest.kt', 'filename': 'BiliSubtitlePolicyTest.kt', 'bytes': 19702, 'sha256Bytes': '5eac936e9e3c3396e6b4f129593942bd1bf2cfaa66790bfd6dfa9a59f2e912c7', 'gitBlob': '51b8ff9d4ae946c7bf2f951ebca5d38348b56fcd'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppTabTypography.kt', 'filename': 'AppTabTypography.kt', 'bytes': 358, 'sha256Bytes': 'abf72efd6f5d625a7aeab0484ab04f95da3b1e5c9e467996e3d964d263e8cc57', 'gitBlob': '378f7a09e9bbbd9c01ea4fb4a3f7d1cf87904e07'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppThemeConfig.kt', 'filename': 'AppThemeConfig.kt', 'bytes': 3675, 'sha256Bytes': '278c8e56676dc5748f536a10c8f417dbd729eb1a40cc61f8c7bb4157124fe179', 'gitBlob': 'e9883a19bf804d3e5e864610f79dd80366bec81b'}]
def sha(raw):return hashlib.sha256(raw).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def fixed_sources():
 root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-player-foundation-closure'
 raw=(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA
 m=json.loads(raw);assert m['fixedUpstreamCommit']==COMMIT and m['files']==PINS
 results={}
 for row in PINS:
  raw=(root/row['filename']).read_bytes()
  assert len(raw)==row['bytes'] and sha(raw)==row['sha256Bytes']
  assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==row['gitBlob']
  results[row['path']]=raw
 return results
def adapt_foundation(path, raw):
 text=raw.decode('utf-8')
 if path.endswith('/AppColorPicker.kt'):
  old='android.graphics.Color.colorToHSV(color.toArgb(), it)'
  assert text.count(old)==2
  text=text.replace(old,'desktopColorToHSV(color.toArgb(), it)')
  text+='''
// Windows color conversion uses the actual JDK RGB/HSB implementation.
// Android hue is expressed in degrees; saturation/value retain their original range.
private fun desktopColorToHSV(argb: Int, destination: FloatArray) {
    require(destination.size >= 3)
    java.awt.Color.RGBtoHSB((argb ushr 16) and 255, (argb ushr 8) and 255, argb and 255, destination)
    destination[0] *= 360f
}
'''
 elif path.endswith('/PiliPlusDanmakuSendIcon.kt'):
  changes=[('import androidx.compose.ui.text.font.Font\n','import androidx.compose.ui.text.platform.Font\n'),
   ('import com.android.purebilibili.R\n',''),
   ('private val PiliPlusIconFont = FontFamily(Font(R.font.piliplus_custom_icons))', '''private val PiliPlusIconFont = FontFamily(Font(
    identity = "original-v037-piliplus-custom-icons",
    data = object {}.javaClass.getResourceAsStream("/fonts/piliplus_custom_icons.ttf")
        ?.use { it.readBytes() } ?: error("Original PiliPlus icon font is missing"),
))'''),
   ('platformStyle = PlatformTextStyle(includeFontPadding = false),','platformStyle = null,')]
  for old,new in changes:
   assert text.count(old)==1,(path,old)
   text=text.replace(old,new,1)
 return text.encode('utf-8')

def emit_foundations(output,outputs,test_output=None):
 sources=fixed_sources()
 for row in PINS:
  raw=sources[row['path']]
  from v040_liquid_value_controls import retained_original_override
  raw,effective_row,effective_commit,effective_manifest=retained_original_override(Path(__file__).resolve().parents[2],row['path'],raw,row,COMMIT,MANIFEST_SHA)
  if '/test/java/' in row['path']:
   if test_output is not None:
    rel=row['path'].split('/test/java/',1)[1]
    p=wide(Path(test_output)/rel);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
   continue
  rel=row['path'].split('/main/java/',1)[1]
  assert not any(item['path']==rel for item in outputs),rel
  original=raw
  raw=adapt_foundation(row['path'],raw)
  from v029_comment_search import whole_proof
  proof=whole_proof(original.decode('utf-8'),raw.decode('utf-8'))
  proof.update(sourcePath=row['path'],fixedCommit=effective_commit,sourceQualified=False,pixelsValidated=False)
  proof_path=wide(Path(output)/'v037-foundation-platform-proofs'/(row['filename']+'.json'))
  proof_path.parent.mkdir(parents=True,exist_ok=True);proof_path.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
  p=wide(Path(output)/rel);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
  outputs.append(dict(path=rel,origin=row['path'],fixedCommit=effective_commit,
   mode='full-original-Windows-platform-boundary' if raw!=original else 'direct-complete-original',sha256LF=sha(raw),generated=True,
   source=effective_row,sourceManifestSHA256=effective_manifest))


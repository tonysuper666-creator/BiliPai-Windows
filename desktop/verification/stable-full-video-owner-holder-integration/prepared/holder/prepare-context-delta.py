from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent;MAIN=P.parents[2]
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
source=MAIN/'desktop/.local/stable-video-full-owner-parity/whole-compile-10/source-inputs/prepared/legacy/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt'
raw=wide(source).read_bytes().replace(b'\r\n',b'\n').decode()
before='internal class DesktopOriginalPlayerSettingsDataStore'
at=raw.index(before);prefix=raw[:at];end=prefix.rfind('}\n');assert end>=0
insertion='    fun remove(key: DesktopPreferenceKey<*>) { changes[key.name] = null }\n'
candidate=raw[:end]+insertion+raw[end:]
mapBefore='    val changes = linkedMapOf<String, JsonElement>()'
mapAfter='    val changes = linkedMapOf<String, JsonElement?>()'
getBefore='    operator fun <T> get(key: DesktopPreferenceKey<T>): T? = changes[key.name]?.let(key.decode) ?: snapshot[key]'
getAfter='    operator fun <T> get(key: DesktopPreferenceKey<T>): T? =\n        if (changes.containsKey(key.name)) changes[key.name]?.let(key.decode) else snapshot[key]'
assert candidate.count(mapBefore)==1 and candidate.count(getBefore)==1
candidate=candidate.replace(mapBefore,mapAfter).replace(getBefore,getAfter)
output=P/'compile-reference/context/DesktopOriginalPlayerSettingsContext.kt';wide(output).parent.mkdir(parents=True,exist_ok=True);wide(output).write_bytes(candidate.encode())
anchor='}\n\ninternal class DesktopOriginalPlayerSettingsDataStore'
assert raw.count(anchor)==1
receipt=dict(basePath=str(source),baseSHA256LF=hashlib.sha256(raw.encode()).hexdigest(),candidateSHA256LF=hashlib.sha256(candidate.encode()).hexdigest(),hunks=[dict(before=mapBefore,after=mapAfter),dict(before=getBefore,after=getAfter),dict(before=anchor,after=insertion+anchor)],originalAuthorityUnchanged=True,installWholeSource=False,compileOnlyReferenceSource=str(output),rootRecipe='Apply only these 3 members/types to existing DesktopOriginalPlayerPreferenceValues; null is original MutablePreferences deletion. Preserve parent stringSet and all existing context constructors.')
target=P/'local-hunks/context-remove.json';wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes((json.dumps(receipt,indent=2)+'\n').encode());print(receipt['candidateSHA256LF'])

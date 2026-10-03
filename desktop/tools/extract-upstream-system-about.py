from __future__ import annotations
import hashlib, importlib.util, json, os, re, sys
from pathlib import Path
sys.dont_write_bytecode = True
UPSTREAM = "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
FEATURE = "desktop-whole-system-about-actions"
CATALOG_SHA = "2fa53aa78cc27c76a3923cc44750128b3657c340dbcfe44ec13810cbc48becbc"
BASE = "app/src/main/java/com/android/purebilibili/feature/settings/"
SECTIONS = BASE + "ui/SettingsSections.kt"
GATE = "app/src/main/java/com/android/purebilibili/feature/agreement/UserAgreementGate.kt"
MANAGER = "app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt"
DIRECT = [BASE + "update/" + name + ".kt" for name in ["AppUpdateDownloadState", "AppUpdateReleaseNotesPolicy", "AppUpdateDialogVisualPolicy", "AppUpdateUiPolicy", "AppUpdateAssetSelectionPolicy", "AppBuildVerificationPolicy"]]
WHOLE = [BASE + "update/" + name + ".kt" for name in ["AppUpdateChecker", "Material3AppUpdateDialog", "MiuixAppUpdateDialog"]]

def wide(path):
    raw = os.path.abspath(os.fspath(path))
    return Path(raw if os.name != "nt" or raw.startswith("\\\\?\\") else "\\\\?\\" + raw)
def sha(raw): return hashlib.sha256(raw).hexdigest()
def emit(path, value):
    wide(path.parent).mkdir(parents=True, exist_ok=True)
    wide(path).write_bytes(value.encode("utf-8") if isinstance(value,str) else value)
def js(path, value): emit(path, json.dumps(value, ensure_ascii=False, indent=2)+"\n")
def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod); return mod

class Original:
    def __init__(self, repo):
        self.repo = repo
        raw = wide(repo/"desktop/tools/v025-canonical-sources.json").read_bytes()
        assert sha(raw) == CATALOG_SHA
        self.catalog = json.loads(raw); assert self.catalog["upstreamCommit"] == UPSTREAM
    def raw(self, path):
        pin = self.catalog["paths"][path]
        raw = wide(self.repo/path).read_bytes()
        normalized = raw.replace(b"\r\n", b"\n") if pin["hashNormalization"] == "lf" else raw
        assert sha(normalized) == pin["sha256"], path
        return raw
    def text(self,path): return self.raw(path).decode("utf-8").replace("\r\n","\n")

def changes(initial, replacements):
    source = initial; hunks=[]
    for before, after in replacements:
        assert source.count(before)==1, (before,source.count(before))
        at=source.index(before); hunks.append(dict(offset=at,before=before,after=after))
        source=source[:at]+after+source[at+len(before):]
    inverse=source
    for h in reversed(hunks):
        at=h["offset"]; assert inverse[at:at+len(h["after"])]==h["after"]
        inverse=inverse[:at]+h["before"]+inverse[at+len(h["after"]):]
    assert inverse==initial
    return source,hunks

def about_body(original):
    marker="@Composable\nfun AboutSection("
    at=original.index(marker)
    return at,original[at:]

ABOUT_IMPORTS = '''package com.android.purebilibili.feature.settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import com.bilipai.desktop.settings.LocalDesktopOriginalAboutBindings as LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.components.AppPreferenceSectionTitle as SettingsSectionTitle
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem
import com.android.purebilibili.core.ui.common.rememberClipboardCopyHandler
import com.android.purebilibili.core.util.EasterEggs
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.LocalDesktopDetailForeground
import androidx.lifecycle.compose.collectAsStateWithLifecycle
'''

def generate(repo, output, resource_output, proof_output):
    orig=Original(repo)
    sys.path.insert(0,str(repo/"desktop/tools"))
    media=load_module(repo/"desktop/tools/extract-upstream-media.py","about_media")
    parser=media.parser_for(repo)
    proofs=[]; resources=[]
    def output_file(path, initial, adapted, hunks, name=None, header="", selection=None, emitted=True):
        inverse = adapted
        for h in reversed(hunks):
            at=h["offset"]
            assert inverse[at:at+len(h["after"])]==h["after"], (path,at)
            inverse=inverse[:at]+h["before"]+inverse[at+len(h["after"]):]
        assert inverse==initial, path
        package=re.search(r"(?m)^package\s+([\w.]+)",header or adapted).group(1)
        relative=package.replace(".","/")+"/"+(name or Path(path).name)
        raw=(header+adapted).encode()
        if emitted: emit(output/relative,raw)
        row=dict(originalPath=path, output=relative, originalRawSha256=sha(orig.raw(path)), originalLfSha256=sha(orig.text(path).encode()),
                 selectedLfSha256=sha(initial.encode()), adaptedBodyLfSha256=sha(adapted.encode()), outputRawSha256=sha(raw), hunks=hunks,
                 offsetContract="Sequential applied-stage UTF-8 decoded Unicode codepoint offsets; invert in reverse order", fullInverseVerified=True, emitted=emitted,
                 directOriginalCopyOwnedByUpstreamTask=not emitted)
        if selection is not None:
            begin,end=selection
            reconstructed=orig.text(path)[:begin]+inverse+orig.text(path)[end:]
            assert reconstructed==orig.text(path)
            row.update(selectionBegin=begin,selectionEnd=end, wholeOriginalFileReconstructionSha256=sha(reconstructed.encode()))
        proofs.append(row)
    source=orig.text(SECTIONS); at,body=about_body(source)
    edits=[]
    for old,new in [
        ("fun AboutSection(","internal fun AboutSection("),
        ("appUpdateChannel: SettingsManager.AppUpdateChannel,","appUpdateChannel: DesktopOriginalAboutSettings.AppUpdateChannel,"),
        ("onAppUpdateChannelChange: (SettingsManager.AppUpdateChannel) -> Unit,","onAppUpdateChannelChange: (DesktopOriginalAboutSettings.AppUpdateChannel) -> Unit,"),
        ("SettingsManager.getAppIconAppearance(context)","context.iconAppearance"),
        ("SettingsManager.getAppIconAppearanceSync(context)","context.initialAppearance"),
        ("detailDialogContent?.let { dialogContent ->","detailDialogContent?.takeIf { LocalDesktopDetailForeground.current }?.let { dialogContent ->"),
        ("SettingsManager.AppUpdateChannel.entries.map","DesktopOriginalAboutSettings.AppUpdateChannel.entries.map"),
        ("val avatarResId: Int? = null","val avatarResId: String? = null"),
        ("appIconRes: Int,","appIconRes: String,"),
        ("model = appIconRes,","model = desktopOriginalIconPreviewUri(appIconRes, isSystemInDarkTheme()),"),
        ("painter = painterResource(id = avatarResId),","painter = desktopOriginalAboutAvatarPainter(avatarResId),"),
    ]: edits.append((old,new))
    for name in sorted(set(re.findall(r"R\.drawable\.(avatar_\w+)",body))):
        edits.append(("R.drawable."+name,'"'+name+'"'))
        matches=[p for p in orig.catalog["paths"] if p.startswith("app/src/main/res/drawable-nodpi/"+name+".")]
        assert len(matches)==1,(name,matches)
        path=matches[0]; raw=orig.raw(path); target="original-about/"+Path(path).name
        emit(resource_output/target,raw)
        resources.append(dict(path=path,target=target,sha256=sha(raw),bytes=len(raw),hashNormalization="raw",features=[FEATURE]))
    # These vector identifiers are owned by existing SettingsSearch vectors.
    adapted,hunks=changes(body,edits)
    while "painterResource(id = it)" in adapted:
        before="painterResource(id = it)"; after="rememberVectorPainter(DesktopSettingsVectors.vector(it))"; index=adapted.index(before)
        hunks.append(dict(offset=index,before=before,after=after)); adapted=adapted[:index]+after+adapted[index+len(before):]
    inv=adapted
    for h in reversed(hunks):
        i=h["offset"]; assert inv[i:i+len(h["after"])]==h["after"]; inv=inv[:i]+h["before"]+inv[i+len(h["after"]):]
    assert inv==body
    output_file(SECTIONS,body,adapted,hunks,"DesktopOriginalAboutSection.kt",ABOUT_IMPORTS,(at,len(source)))
    for name in ["SupportToolsSection","ReleaseChannelPinnedCard"]:
        body="@Composable\n"+media.function(source,name,parser)
        start=source.index(body); edits=[]
        adapted=body; hunks=[]
        while "painterResource(id = it)" in adapted:
            before="painterResource(id = it)"; after="rememberVectorPainter(DesktopSettingsVectors.vector(it))"; index=adapted.index(before)
            hunks.append(dict(offset=index,before=before,after=after)); adapted=adapted[:index]+after+adapted[index+len(before):]
        output_file(SECTIONS,body,adapted,hunks,"DesktopOriginal"+name+".kt",ABOUT_IMPORTS,(start,start+len(body)))
    # Existing ProfileMain is the sole full original agreement review/body/linkifier owner.
    # Remove only the exact previous About duplicate; an unknown local file is never deleted.
    duplicate = output / "com/android/purebilibili/feature/agreement/DesktopOriginalUserAgreementReview.kt"
    if wide(duplicate).exists():
        assert sha(wide(duplicate).read_bytes()) == "fc422e6d435a259ace89ec27ab8e7f65f29bb62c2adcc273e2222ec8b830b7b8", "Unexpected modified duplicate agreement output"
        wide(duplicate).unlink()
    profile_producer = repo / "desktop/tools/extract-upstream-profile-main.py"
    assert sha(wide(profile_producer).read_bytes().replace(bytes([13,10]),bytes([10]))) == "47736c78062ec6bcc99888f35a1396f90b3bfc31cbbff5bd571e5a8c188d4663", "Existing complete agreement owner changed"
    agreement_source = orig.text(GATE)
    shared_agreement = dict(originalPath=GATE, originalRawSha256=sha(orig.raw(GATE)),
        originalLfSha256=sha(agreement_source.encode()), producer="desktop/tools/extract-upstream-profile-main.py",
        producerLfSha256="47736c78062ec6bcc99888f35a1396f90b3bfc31cbbff5bd571e5a8c188d4663", producerHashNormalization="lf",
        output="build/generated/profile-main/com/android/purebilibili/feature/agreement/DesktopOriginalAgreementReview.kt",
        functions={name: sha(media.function(agreement_source,name,parser).encode())
            for name in ["UserAgreementBody", "LinkifiedText", "UserAgreementReviewDialog"]},
        emittedByAbout=False, completeOriginalBodyOwnedByProfileMain=True)
    # Full original pure metadata policy/models and both complete theme renderers.
    for path in DIRECT:
        body=orig.text(path); output_file(path,body,body,[],emitted=False)
    for path in WHOLE:
        body=orig.text(path); edits=[]
        if path.endswith("AppUpdateChecker.kt"):
            edits += [("    suspend fun check(","    internal suspend fun check("), ("includePrerelease: Boolean = false\n    ): Result<AppUpdateCheckResult>","includePrerelease: Boolean = false,\n        http: com.bilipai.desktop.settings.DesktopOriginalAboutReleaseHttp\n    ): Result<AppUpdateCheckResult>")]
            start=re.search(r"(?m)^    private fun fetchRemoteText\(",body).start()
            tokens=parser.kotlin_tokens(body); first=next(i for i,t in enumerate(tokens) if t[1]>=start and t[0]=="{")
            end=first; depth=1
            while depth: end+=1;depth+=(tokens[end][0]=="{")-(tokens[end][0]=="}")
            method=body[start:tokens[end][2]]
            adapted_method='''    private suspend fun fetchRemoteText(
        http: com.bilipai.desktop.settings.DesktopOriginalAboutReleaseHttp,
        url: String,
        required: Boolean
    ): String? = http.fetch(url, required)'''
            edits += [(method,adapted_method),
                      ("fetchRemoteText(RELEASES_API, required = false)","fetchRemoteText(http, RELEASES_API, required = false)"),
                      ("fetchRemoteText(metadataUrl, required = false)","fetchRemoteText(http, metadataUrl, required = false)")]
            # This identical metadata call occurs twice; exact stage offsets record each.
            current=body; ops=[]
            for before,after in edits:
                occurrences=current.count(before)
                assert occurrences== (2 if before.startswith("fetchRemoteText(metadataUrl") else 1)
                for _ in range(occurrences):
                    i=current.index(before); ops.append(dict(offset=i,before=before,after=after)); current=current[:i]+after+current[i+len(before):]
            inverse=current
            for h in reversed(ops):
                i=h["offset"]; assert inverse[i:i+len(h["after"])]==h["after"]; inverse=inverse[:i]+h["before"]+inverse[i+len(h["after"]):]
            assert inverse==body
            output_file(path,body,current,ops)
        else:
            edits=[("import androidx.compose.ui.platform.LocalConfiguration","import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration")]
            adapted,ops=changes(body,edits); output_file(path,body,adapted,ops)
    path=BASE+"update/AppUpdateDialogHost.kt"; source=orig.text(path); start=source.index("internal data class AppUpdateDialogState(")
    body=source[start:]; header="package com.android.purebilibili.feature.settings\n"
    const_start=source.index("internal const val GITHUB_RELEASE_DOWNLOAD_URL")
    const_end=source.index("/** Owns update state",const_start)
    header+=source[const_start:const_end]
    output_file(path,body,body,[],"DesktopOriginalAboutUpdateDialogModels.kt",header,(start,len(source)))
    source=orig.text(MANAGER); marker="    enum class AppUpdateChannel("
    start=source.index(marker); tokens=parser.kotlin_tokens(source); token=next(i for i,t in enumerate(tokens) if t[1]>=start and t[0]=="{")
    depth=1; end=token
    while depth: end+=1; depth+=(tokens[end][0]=="{")-(tokens[end][0]=="}")
    body=source[start:tokens[end][2]]
    header="package com.bilipai.desktop.settings\ninternal object DesktopOriginalAboutSettings {\n"
    relative="com/bilipai/desktop/settings/DesktopOriginalAboutSettings.kt";raw=(header+body+"\n}\n").encode(); emit(output/relative,raw)
    proofs.append(dict(originalPath=MANAGER,output=relative,selectionBegin=start,selectionEnd=tokens[end][2],selectedLfSha256=sha(body.encode()),originalLfSha256=sha(source.encode()),originalRawSha256=sha(orig.raw(MANAGER)),outputRawSha256=sha(raw),fullInverseVerified=True,hunks=[],wholeOriginalFileReconstructionSha256=sha(source.encode())))
    path=BASE+"update/AppBuildVerificationRuntime.kt";source=orig.text(path)
    body=media.data_class(source,"InstalledAppBuildProvenance",parser); start=source.index(body)
    output_file(path,body,body,[],"DesktopOriginalAboutBuildProvenance.kt","package com.android.purebilibili.feature.settings\n",(start,start+len(body)))
    original_assets={Path(r["path"]).stem:r["target"] for r in resources}
    helper='''package com.bilipai.desktop.settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.rememberAsyncImagePainter

internal fun desktopOriginalAboutAvatarUri(
    name: String,
    resourceDirectory: java.nio.file.Path? = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }?.let { java.nio.file.Path.of(it) },
    classLoader: ClassLoader = DesktopOriginalAboutSettings::class.java.classLoader,
): coil3.Uri {
    val path = when(name) {
MAPPINGS
        else -> error("Unknown original About avatar: $name")
    }
    val location = if (resourceDirectory != null) {
        val root = resourceDirectory.toAbsolutePath().normalize()
        val asset = root.resolve(path.removePrefix("/")).normalize()
        require(asset.startsWith(root) && java.nio.file.Files.isRegularFile(asset)) {
            "Original About avatar is missing: $asset"
        }
        asset.toUri().toURL()
    } else {
        requireNotNull(classLoader.getResource(path.removePrefix("/"))) {
            "Original About avatar is missing: $path"
        }
    }
    return desktopOriginalCoilClasspathUri(location)
}

@Composable internal fun desktopOriginalAboutAvatarPainter(name: String): Painter =
    rememberAsyncImagePainter(desktopOriginalAboutAvatarUri(name))
'''.replace("MAPPINGS","\n".join('        "'+name+'" -> "/'+target+'"' for name,target in original_assets.items()))
    emit(output/"com/bilipai/desktop/settings/DesktopOriginalAboutResources.kt",helper)
    build=orig.text("app/build.gradle.kts")
    version=re.search(r'versionName\s*=\s*"([^"]+)"',build).group(1)
    code=int(re.search(r'versionCode\s*=\s*(\d+)',build).group(1))
    emit(output/"com/bilipai/desktop/settings/DesktopOriginalAboutVersion.kt",'package com.bilipai.desktop.settings\ninternal const val DESKTOP_ORIGINAL_ABOUT_VERSION = '+json.dumps(version)+'\ninternal const val DESKTOP_ORIGINAL_ABOUT_VERSION_CODE = '+str(code)+'\n')
    proof=dict(upstreamCommit=UPSTREAM,feature=FEATURE,sourceInverse=proofs,sharedOriginalOwners=[shared_agreement],resources=resources,fullOriginalAboutBody=True,originalContributorCount=12,notesAreUpstreamMetadataOnly=True,androidApkInstallerExcluded=True,adapterCompiled=False,actualRootRuntimeVerified=False)
    js(proof_output,proof)
    return proof

def inventory(repo):
    orig=Original(repo)
    sources=[SECTIONS,GATE,MANAGER,BASE+"AppUpdateAutoCheckGate.kt",BASE+"update/AppUpdateDialogHost.kt",BASE+"update/AppBuildVerificationRuntime.kt","app/build.gradle.kts"]+DIRECT+WHOLE
    return [dict(path=p,sha256=sha(orig.text(p).encode()),mode="direct" if p in DIRECT or p.endswith("AppUpdateAutoCheckGate.kt") else "platform-adapter-reference",features=[FEATURE]) for p in sources]

if __name__=="__main__":
    import argparse
    cli=argparse.ArgumentParser();cli.add_argument("--repo",type=Path,required=True);cli.add_argument("--output",type=Path);cli.add_argument("--resource-output",type=Path);cli.add_argument("--proof",type=Path);cli.add_argument("--inventory",action="store_true")
    args=cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo),ensure_ascii=False,indent=2))
    if args.output:
        assert args.resource_output and args.proof
        result=generate(args.repo,args.output,args.resource_output,args.proof)
        print(json.dumps(dict(inverseCount=len(result["sourceInverse"]),resources=len(result["resources"]),compiled=False)))

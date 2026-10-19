"""Read-only helpers of the XP API probe (UX program slice XP, spec 9.2 and 10.2). No JVM, no network.

The probe is desktop/src/test/kotlin/com/bilipai/desktop/ux/UxApiProbeTest.kt. This module holds what the Python
suite needs to judge it without a compiler:

  * a class-file reader (constant pool, access flags, interfaces, field and method tables, annotation types);
  * the discovery of the pinned jars (the Compose desktop jars of the version in desktop/build.gradle.kts and the Miuix
    fork jar of the vendored commit) and of a JDK's lib/ct.sym for the java.desktop API;
  * SYMBOLS, the table of every uncertain API symbol the probe references, with the JVM class, member and
    descriptor that must exist in the pinned jars, the opt-in markers its use requires and the Kotlin text the probe
    must contain;
  * small Kotlin text readers (function parameter lists, call argument names) over the repository's own lexer
    (check_slice_symbol_closure.mask_kotlin), used on the vendored Miuix sources and on the real generated text.

Throwaway. This module, test_ux_api_probe_source.py and the probe die together when Root deletes the probe after
the first Linux compile (see the probe header). Nothing else imports this module.
"""
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PROBE = REPO / "desktop/src/test/kotlin/com/bilipai/desktop/ux/UxApiProbeTest.kt"
MIUIX = REPO / "desktop/third-party/miuix5157"

sys.path.insert(0, str(REPO / "desktop/tools"))
from check_slice_symbol_closure import mask_kotlin  # noqa: E402  (the repository's own Kotlin lexer)

ACC_PUBLIC, ACC_STATIC, ACC_INTERFACE, ACC_ABSTRACT, ACC_SYNTHETIC = 0x0001, 0x0008, 0x0200, 0x0400, 0x1000


def read_text(path):
    """UTF-8 text with CRLF normalised to LF."""
    return Path(path).read_bytes().replace(b"\r\n", b"\n").decode("utf-8")


# --------------------------------------------------------------------------------------
# Class files
# --------------------------------------------------------------------------------------
class Member:
    __slots__ = ("name", "desc", "access", "annotations")

    def __init__(self, name, desc, access, annotations):
        self.name, self.desc, self.access, self.annotations = name, desc, access, annotations

    @property
    def public(self):
        return bool(self.access & ACC_PUBLIC)

    @property
    def synthetic(self):
        return bool(self.access & ACC_SYNTHETIC)

    @property
    def abstract(self):
        return bool(self.access & ACC_ABSTRACT)

    @property
    def static(self):
        return bool(self.access & ACC_STATIC)


class _Reader:
    def __init__(self, data, pos=0):
        self.data, self.pos = data, pos

    def u1(self):
        value = self.data[self.pos]
        self.pos += 1
        return value

    def u2(self):
        value = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return value

    def u4(self):
        value = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return value

    def take(self, count):
        value = self.data[self.pos:self.pos + count]
        self.pos += count
        return value

    def skip(self, count):
        self.pos += count


def _annotation_types(pool, blob):
    """Type descriptors of the annotations in one Runtime(In)VisibleAnnotations attribute body."""
    reader = _Reader(blob)
    types = []

    def skip_value():
        tag = chr(reader.u1())
        if tag in "BCDFIJSZsc":
            reader.skip(2)
        elif tag == "e":
            reader.skip(4)
        elif tag == "@":
            skip_annotation()
        elif tag == "[":
            for _ in range(reader.u2()):
                skip_value()
        else:
            raise ValueError("annotation element tag " + tag)

    def skip_annotation():
        type_index = reader.u2()
        for _ in range(reader.u2()):
            reader.skip(2)
            skip_value()
        return pool[type_index]

    for _ in range(reader.u2()):
        types.append(skip_annotation())
    return types


class ClassFile:
    """Constant pool, header, field and method tables and annotation types of one class file."""

    def __init__(self, data):
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("not a class file")
        reader = _Reader(data, 8)
        count = reader.u2()
        pool = [None] * count
        classes = {}
        index = 1
        while index < count:
            tag = reader.u1()
            if tag == 1:
                pool[index] = reader.take(reader.u2()).decode("utf-8", "replace")
            elif tag == 7:
                classes[index] = reader.u2()
            elif tag in (8, 16, 19, 20):
                reader.skip(2)
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                reader.skip(4)
            elif tag in (5, 6):
                reader.skip(8)
                index += 1
            elif tag == 15:
                reader.skip(3)
            else:
                raise ValueError("constant pool tag %d" % tag)
            index += 1
        self.utf8 = frozenset(item for item in pool if isinstance(item, str))
        self.access = reader.u2()
        this_index, super_index = reader.u2(), reader.u2()
        self.name = pool[classes[this_index]]
        self.super_name = pool[classes[super_index]] if super_index else None
        self.interfaces = tuple(pool[classes[reader.u2()]] for _ in range(reader.u2()))

        def attributes():
            found = {}
            for _ in range(reader.u2()):
                name, length = pool[reader.u2()], reader.u4()
                found[name] = reader.take(length)
            return found

        def annotations(attrs):
            result = []
            for key in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                if key in attrs:
                    result.extend(_annotation_types(pool, attrs[key]))
            return tuple(result)

        def members():
            table = []
            for _ in range(reader.u2()):
                access, name, desc = reader.u2(), pool[reader.u2()], pool[reader.u2()]
                table.append(Member(name, desc, access, annotations(attributes())))
            return tuple(table)

        self.fields = members()
        self.methods = members()
        self.annotations = annotations(attributes())

    @property
    def is_interface(self):
        return bool(self.access & ACC_INTERFACE)

    def find(self, name, desc):
        """Methods with this name and exactly this descriptor. A trailing '-' in `name` means a value-class mangled name:
        the prefix, a '-', then a base64-like hash that may itself contain '-'."""
        if name.endswith("-"):
            return [m for m in self.methods if m.desc == desc and m.name.startswith(name) and len(m.name) > len(name)]
        return [m for m in self.methods if m.desc == desc and m.name == name]

    def has_field(self, name):
        return any(f.name == name for f in self.fields)

    def abstract_methods(self):
        return {m.name + m.desc for m in self.methods if m.abstract and not m.synthetic}


# --------------------------------------------------------------------------------------
# Jars
# --------------------------------------------------------------------------------------
def pins():
    """The pinned Compose version (desktop/build.gradle.kts plugin line) and the Miuix source commit prefix."""
    gradle = read_text(REPO / "desktop/build.gradle.kts")
    compose = re.search(r'id\("org\.jetbrains\.compose"\) version "([^"]+)"', gradle).group(1)
    commit = json.loads(read_text(MIUIX / "upstream-provenance.json"))["commit"][:8]
    return compose, commit


JAR_FAMILIES = ("runtime-desktop", "ui-desktop", "ui-util-desktop", "ui-unit-desktop", "ui-graphics-desktop",
                "foundation-desktop", "foundation-layout-desktop")


def jar_patterns():
    compose, commit = pins()
    version = re.escape(compose)
    patterns = {name: re.compile(r"^%s-%s(-[0-9a-f]+)?\.jar$" % (re.escape(name), version)) for name in JAR_FAMILIES}
    patterns["miuix"] = re.compile(r"^miuix5157-jvm-[0-9.]+-%s-windows-source1(-[0-9a-f]+)?\.jar$" % commit)
    return patterns


def candidate_roots():
    """Directories searched, first the BILIPAI_UX_JAR_DIRS override (os.pathsep separated), then this worktree's own
    build output, then the build output of the sibling integration checkout (work/BiliPai-v025, newest), then the
    runtime jars of the checkout the worktree was created from, then the Gradle module caches (the user's, then the
    workspace toolchain's gradle-home, whose resolved artifacts are the pin authority)."""
    roots = [Path(item) for item in os.environ.get("BILIPAI_UX_JAR_DIRS", "").split(os.pathsep) if item]
    roots += [REPO / "desktop/third-party/miuix5157/build/libs"]
    roots += sorted((REPO / "desktop/build/compose/binaries/main/app").glob("*/app"))
    try:
        common = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--git-common-dir"], capture_output=True, text=True,
                                timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        common = ""
    if common:
        checkout = Path(common).resolve().parent
        sibling = checkout.parent / "BiliPai-v025/desktop"
        roots += [sibling / "third-party/miuix5157/build/libs"]
        roots += sorted((sibling / "build/compose/binaries/main/app").glob("*/app"))
        roots += [checkout / ".local/fsr/base-runtime", checkout / "desktop/.local/stable-product-snapshot-32"]
    gradle_homes = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))]
    if common:
        gradle_homes.append(Path(common).resolve().parent.parent / "toolchain/gradle-home")
    for gradle_home in gradle_homes:
        cache = gradle_home / "caches/modules-2/files-2.1"
        if cache.is_dir():
            roots += sorted(path.parent for path in cache.glob("org.jetbrains.compose.*/*-desktop/*/*/*.jar"))
    return roots


def find_jars(roots=None):
    """({family: [jar paths]}, [families not found]). The first root that holds a family wins that family."""
    patterns, found = jar_patterns(), {}
    for root in (candidate_roots() if roots is None else roots):
        if not Path(root).is_dir():
            continue
        for family, pattern in patterns.items():
            matches = [jar for jar in sorted(Path(root).glob("*.jar")) if pattern.match(jar.name)]
            if matches and family not in found:
                found[family] = matches
    return found, [family for family in patterns if family not in found]


class JarSet:
    """Class lookup over a set of jars (zipfile only)."""

    def __init__(self, jars):
        self.jars = [Path(jar) for jar in jars]
        self._entries = None
        self._parsed = {}

    def entries(self):
        if self._entries is None:
            self._entries = {}
            for jar in self.jars:
                with zipfile.ZipFile(jar) as archive:
                    for name in archive.namelist():
                        if name.endswith(".class"):
                            self._entries.setdefault(name[:-6], jar)
        return self._entries

    def get(self, internal_name):
        if internal_name not in self._parsed:
            jar = self.entries().get(internal_name)
            if jar is None:
                self._parsed[internal_name] = None
            else:
                with zipfile.ZipFile(jar) as archive:
                    self._parsed[internal_name] = ClassFile(archive.read(internal_name + ".class"))
        return self._parsed[internal_name]

    def package(self, package_path):
        """Internal names of the classes directly in a package directory."""
        prefix = package_path.rstrip("/") + "/"
        return sorted(name for name in self.entries() if name.startswith(prefix) and "/" not in name[len(prefix):])

    def requires_opt_in(self, annotation_descriptor):
        """True when the annotation class is itself a kotlin.RequiresOptIn marker."""
        cls = self.get(annotation_descriptor[1:-1])
        return cls is not None and "Lkotlin/RequiresOptIn;" in cls.annotations

    def has_top_level(self, package, name):
        """A top-level callable or property `name` in a Kotlin file facade of the package: a method called name,
        name-hash (value-class mangling), get<Name> or get<Name>-hash (an extension property)."""
        getter = "get" + name[:1].upper() + name[1:]
        for internal in self.package(package.replace(".", "/")):
            if "$" in internal:
                continue  # top-level declarations live in file facades, never in nested or synthetic classes
            cls = self.get(internal)
            for method in cls.methods:
                if method.name in (name, getter) or method.name.startswith((name + "-", getter + "-")):
                    return True
        return False


def jdk_roots():
    """Candidate JDK homes: BILIPAI_UX_JDK, JAVA_HOME, the workspace toolchain's jdk (work/toolchain/jdk/*)."""
    homes = [Path(os.environ[key]) for key in ("BILIPAI_UX_JDK", "JAVA_HOME") if os.environ.get(key)]
    try:
        common = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--git-common-dir"], capture_output=True, text=True,
                                timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        common = ""
    if common:
        homes += sorted((Path(common).resolve().parent.parent / "toolchain/jdk").glob("jdk-*"))
    return homes


class JdkStubs:
    """JDK API signatures read from lib/ct.sym (a zip of class-file stubs of the releases before the running JDK). The
    java.desktop signatures used by the probe are unchanged between those releases and JDK 21."""

    def __init__(self, ct_sym):
        self.path = Path(ct_sym)
        self._names = None

    def _entries(self):
        if self._names is None:
            with zipfile.ZipFile(self.path) as archive:
                self._names = [n for n in archive.namelist() if "/java.desktop/" in n and n.endswith(".sig")]
        return self._names

    def get(self, internal_name):
        suffix = "/java.desktop/%s.sig" % internal_name
        matches = sorted((n for n in self._entries() if n.endswith(suffix)), key=lambda n: n.split("/")[0], reverse=True)
        if not matches:
            return None
        with zipfile.ZipFile(self.path) as archive:
            return ClassFile(archive.read(matches[0]))


def find_jdk_stubs():
    for home in jdk_roots():
        if (home / "lib/ct.sym").is_file():
            return JdkStubs(home / "lib/ct.sym")
    return None


# (class, member, descriptor) of the JDK API the probe uses; a descriptor of None names a field.
JDK_SYMBOLS = (
    ("java/awt/Toolkit", "getDefaultToolkit", "()Ljava/awt/Toolkit;"),
    ("java/awt/Toolkit", "createCustomCursor", "(Ljava/awt/Image;Ljava/awt/Point;Ljava/lang/String;)Ljava/awt/Cursor;"),
    ("java/awt/image/BufferedImage", "<init>", "(III)V"),
    ("java/awt/image/BufferedImage", "TYPE_INT_ARGB", None),
    ("java/awt/Point", "<init>", "(II)V"),
    ("java/awt/Window", "isAlwaysOnTop", "()Z"),
    ("java/awt/Window", "setAlwaysOnTop", "(Z)V"),
    ("java/awt/Dialog", "setTitle", "(Ljava/lang/String;)V"),
    ("java/awt/Dialog", "setUndecorated", "(Z)V"),
    ("java/awt/Dialog", "setResizable", "(Z)V"),
    ("java/awt/Dialog$ModalityType", "MODELESS", None),
)


# --------------------------------------------------------------------------------------
# The symbols
# --------------------------------------------------------------------------------------
UI = "androidx/compose/ui/"
FOUNDATION = "androidx/compose/foundation/"
MIUIX_BASIC = "top/yukonga/miuix/kmp/basic/"
MIUIX_BLUR = "top/yukonga/miuix/kmp/blur/"
BUTTONS = UI + "input/pointer/PointerEvent_skikoKt"
COMPOSER = "Landroidx/compose/runtime/Composer;"
SCROLLBAR_ADAPTER = "Landroidx/compose/foundation/v2/ScrollbarAdapter;"
OPT_IN_UI = "Landroidx/compose/ui/ExperimentalComposeUiApi;"


def check(kind, cls, member=None, desc=None):
    return dict(kind=kind, cls=cls, member=member, desc=desc)


# kinds: class (exists), interface (exists and is an interface), method (public, exact descriptor, mangling-aware),
# abstract-set (the abstract methods of the interface are exactly the set `member`), implements (class `cls` lists
# interface `member`), not-deprecated (method exists and carries no kotlin.Deprecated).
SYMBOLS = (
    dict(key="pointer-buttons-back",
         checks=(check("method", BUTTONS, "PointerButtons", "(ZZZZZ)I"),
                 check("method", BUTTONS, "isBackPressed-", "(I)Z"),
                 check("method", BUTTONS, "isForwardPressed-", "(I)Z")),
         opt_in=(),
         needles=("import androidx.compose.ui.input.pointer.PointerButtons", "import androidx.compose.ui.input.pointer.isBackPressed",
                  "import androidx.compose.ui.input.pointer.isForwardPressed", "PointerButtons(isBackPressed = true)",
                  ".isBackPressed", ".isForwardPressed"),
         named={BUTTONS: ("isBackPressed",)}),
    dict(key="pointer-event-handler",
         checks=(check("method", UI + "input/pointer/SuspendingPointerInputFilter_skikoKt", "onPointerEvent-",
                       "(Landroidx/compose/ui/Modifier;ILandroidx/compose/ui/input/pointer/PointerEventPass;"
                       "Lkotlin/jvm/functions/Function2;)Landroidx/compose/ui/Modifier;"),
                 check("method", UI + "input/pointer/PointerEvent", "getButtons-", "()I")),
         opt_in=(OPT_IN_UI,),
         needles=("import androidx.compose.ui.input.pointer.onPointerEvent", "import androidx.compose.ui.input.pointer.PointerEventType",
                  "@OptIn(ExperimentalComposeUiApi::class)", "onPointerEvent(PointerEventType.Press)", "event.buttons.isBackPressed"),
         named={}),
    dict(key="blank-pointer-icon",
         checks=(check("method", UI + "input/pointer/PointerIcon_desktopKt", "PointerIcon", "(Ljava/awt/Cursor;)Landroidx/compose/ui/input/pointer/PointerIcon;"),
                 check("interface", UI + "input/pointer/PointerIcon")),
         opt_in=(),
         needles=("import androidx.compose.ui.input.pointer.PointerIcon", "Toolkit.getDefaultToolkit().createCustomCursor(",
                  "BufferedImage.TYPE_INT_ARGB", "return PointerIcon(blank)"),
         named={}),
    dict(key="pointer-hover-icon",
         checks=(check("method", UI + "input/pointer/PointerIconKt", "pointerHoverIcon",
                       "(Landroidx/compose/ui/Modifier;Landroidx/compose/ui/input/pointer/PointerIcon;Z)Landroidx/compose/ui/Modifier;"),
                 check("method", UI + "input/pointer/PointerIcon$Companion", "getHand", "()Landroidx/compose/ui/input/pointer/PointerIcon;")),
         opt_in=(),
         needles=("import androidx.compose.ui.input.pointer.pointerHoverIcon", "pointerHoverIcon(blank, overrideDescendants = true)",
                  "pointerHoverIcon(PointerIcon.Hand)"),
         named={UI + "input/pointer/PointerIconKt": ("overrideDescendants",)}),
    dict(key="miuix-tooltip-box",
         checks=(check("method", MIUIX_BASIC + "TooltipKt", "TooltipBox",
                       "(Landroidx/compose/ui/window/PopupPositionProvider;Lkotlin/jvm/functions/Function3;"
                       "Ltop/yukonga/miuix/kmp/basic/TooltipState;Landroidx/compose/ui/Modifier;ZZ"
                       "Lkotlin/jvm/functions/Function2;" + COMPOSER + "II)V"),
                 check("method", MIUIX_BASIC + "TooltipKt", "rememberTooltipState",
                       "(ZZLandroidx/compose/foundation/MutatorMutex;" + COMPOSER + "II)Ltop/yukonga/miuix/kmp/basic/TooltipState;"),
                 check("method", MIUIX_BASIC + "TooltipKt", "PlainTooltip-",
                       "(Ltop/yukonga/miuix/kmp/basic/TooltipScope;Landroidx/compose/ui/Modifier;Landroidx/compose/ui/graphics/Shape;FFJJ"
                       "Landroidx/compose/foundation/layout/PaddingValues;Lkotlin/jvm/functions/Function2;" + COMPOSER + "II)V"),
                 check("method", MIUIX_BASIC + "TooltipDefaults", "rememberTooltipPositionProvider-",
                       "(IF" + COMPOSER + "II)Landroidx/compose/ui/window/PopupPositionProvider;"),
                 check("method", MIUIX_BASIC + "TooltipState", "dismiss", "()V"),
                 check("class", MIUIX_BASIC + "TooltipAnchorPosition")),
         opt_in=(),
         needles=("import top.yukonga.miuix.kmp.basic.TooltipBox as MiuixTooltipBox", "import top.yukonga.miuix.kmp.basic.PlainTooltip as MiuixPlainTooltip",
                  "import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition", "import top.yukonga.miuix.kmp.basic.TooltipDefaults as MiuixTooltipDefaults",
                  "import top.yukonga.miuix.kmp.basic.rememberTooltipState as rememberMiuixTooltipState",
                  "MiuixTooltipBox(", "rememberMiuixTooltipState(isPersistent = true)",
                  "MiuixTooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above, 4.dp)"),
         named={}),
    dict(key="input-mode-manager",
         checks=(check("method", UI + "platform/CompositionLocalsKt", "getLocalInputModeManager", "()Landroidx/compose/runtime/ProvidableCompositionLocal;"),
                 check("method", UI + "input/InputModeManager", "getInputMode-", "()I"),
                 check("method", UI + "input/InputMode$Companion", "getKeyboard-", "()I")),
         opt_in=(),
         needles=("import androidx.compose.ui.platform.LocalInputModeManager", "import androidx.compose.ui.input.InputMode",
                  "LocalInputModeManager.current.inputMode == InputMode.Keyboard"),
         named={}),
    dict(key="layer-backdrop-ondraw",
         checks=(check("method", MIUIX_BLUR + "LayerBackdropKt", "rememberLayerBackdrop",
                       "(Landroidx/compose/ui/graphics/layer/GraphicsLayer;Lkotlin/jvm/functions/Function1;" + COMPOSER + "II)Ltop/yukonga/miuix/kmp/blur/LayerBackdrop;"),
                 check("method", MIUIX_BLUR + "LayerBackdropModifierKt", "layerBackdrop",
                       "(Landroidx/compose/ui/Modifier;Ltop/yukonga/miuix/kmp/blur/LayerBackdrop;)Landroidx/compose/ui/Modifier;"),
                 check("implements", MIUIX_BLUR + "LayerBackdrop", MIUIX_BLUR + "Backdrop")),
         opt_in=(),
         needles=("import top.yukonga.miuix.kmp.blur.LayerBackdrop", "import top.yukonga.miuix.kmp.blur.layerBackdrop",
                  "import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop",
                  "rememberLayerBackdrop(onDraw = { with(platform) { drawVideoBackdrop() } })", ".layerBackdrop(backdrop)"),
         named={}),
    dict(key="player-control-glass-dock",
         checks=(), opt_in=(),
         needles=("import com.android.purebilibili.feature.video.ui.overlay.PlayerControlGlassDock",
                  "import com.android.purebilibili.feature.video.ui.overlay.PlayerControlsBackdropHost",
                  "import com.android.purebilibili.feature.video.ui.overlay.LocalPlayerPageBackdrop",
                  "LocalPlayerPageBackdrop provides backdrop", "PlayerControlsBackdropHost(enabled = true)",
                  "PlayerControlGlassDock(enabled = true"),
         named={}),
    dict(key="scrollbar-adapter-v2",
         checks=(check("abstract-set", FOUNDATION + "v2/ScrollbarAdapter",
                       frozenset({"getScrollOffset()D", "getContentSize()D", "getViewportSize()D",
                                  "scrollTo(DLkotlin/coroutines/Continuation;)Ljava/lang/Object;"})),
                 check("not-deprecated", FOUNDATION + "Scrollbar_desktopKt", "VerticalScrollbar",
                       "(" + SCROLLBAR_ADAPTER + "Landroidx/compose/ui/Modifier;ZLandroidx/compose/foundation/ScrollbarStyle;"
                       "Landroidx/compose/foundation/interaction/MutableInteractionSource;" + COMPOSER + "II)V")),
         opt_in=(),
         needles=("import androidx.compose.foundation.v2.ScrollbarAdapter", "import androidx.compose.foundation.VerticalScrollbar",
                  ": ScrollbarAdapter {", "override val scrollOffset: Double", "override val contentSize: Double",
                  "override val viewportSize: Double", "override suspend fun scrollTo(scrollOffset: Double)",
                  "VerticalScrollbar(adapter = adapter, modifier = modifier)"),
         named={FOUNDATION + "Scrollbar_desktopKt": ("adapter",)}),
    dict(key="popup-position-provider",
         checks=(check("abstract-set", UI + "window/PopupPositionProvider",
                       frozenset({"calculatePosition-llwVHH4(Landroidx/compose/ui/unit/IntRect;JLandroidx/compose/ui/unit/LayoutDirection;J)J"})),
                 check("class", UI + "window/PopupProperties"),
                 check("method", UI + "window/Popup_skikoKt", "Popup",
                       "(Landroidx/compose/ui/window/PopupPositionProvider;Lkotlin/jvm/functions/Function0;"
                       "Landroidx/compose/ui/window/PopupProperties;Lkotlin/jvm/functions/Function2;" + COMPOSER + "II)V")),
         opt_in=(),
         needles=("import androidx.compose.ui.window.PopupPositionProvider", "import androidx.compose.ui.window.Popup",
                  ") : PopupPositionProvider {", "override fun calculatePosition(", "Popup(popupPositionProvider = "),
         named={UI + "window/Popup_skikoKt": ("popupPositionProvider", "onDismissRequest", "properties")}),
    dict(key="compose-dialog",
         checks=(check("method", UI + "awt/ComposeDialog", "<init>", "(Ljava/awt/Window;Ljava/awt/Dialog$ModalityType;Ljava/awt/GraphicsConfiguration;)V"),
                 check("method", UI + "awt/ComposeDialog", "setContent", "(Lkotlin/jvm/functions/Function3;)V"),
                 check("method", UI + "awt/ComposeDialog", "getCompositionLocalContext", "()Landroidx/compose/runtime/CompositionLocalContext;"),
                 check("method", UI + "awt/ComposeDialog", "setCompositionLocalContext", "(Landroidx/compose/runtime/CompositionLocalContext;)V"),
                 check("method", UI + "awt/ComposeDialog", "setTransparent", "(Z)V"),
                 check("method", UI + "awt/ComposeDialog", "setUndecorated", "(Z)V")),
         opt_in=(),
         needles=("import androidx.compose.ui.awt.ComposeDialog", "ComposeDialog(owner, Dialog.ModalityType.MODELESS)",
                  "isUndecorated = true", "isAlwaysOnTop = owner.isAlwaysOnTop", "compositionLocalContext = context", "setContent {"),
         named={}),
    dict(key="card-context-menu-alias",
         checks=(), opt_in=(),
         needles=("import com.bilipai.desktop.ux.DesktopCardContextMenu as AppDropdownMenu",
                  "import com.bilipai.desktop.ux.DesktopCardContextMenuItem as AppDropdownMenuItem",
                  "AppDropdownMenu(", "AppDropdownMenuItem("),
         named={}),
)
OPT_IN_SIMPLE_NAMES = {OPT_IN_UI: "ExperimentalComposeUiApi"}


def symbol_findings(jars, symbol):
    """([problems], {opt-in marker descriptors the symbol's members and classes carry}) for one SYMBOLS row."""
    problems, markers = [], set()
    for item in symbol["checks"]:
        label = "%s: %s %s" % (symbol["key"], item["kind"], item["cls"])
        cls = jars.get(item["cls"])
        if cls is None:
            problems.append(label + " is not in the jars")
            continue
        kind, member, desc = item["kind"], item["member"], item["desc"]
        subjects = list(cls.annotations)
        if kind == "class":
            pass
        elif kind == "interface":
            if not cls.is_interface:
                problems.append(label + " is not an interface")
        elif kind in ("method", "not-deprecated"):
            matches = [m for m in cls.find(member, desc) if m.public]
            if not matches:
                problems.append("%s.%s%s has no public method with that name and descriptor" % (label, member, desc))
                continue
            for method in matches:
                subjects.extend(method.annotations)
            if kind == "not-deprecated" and all("Lkotlin/Deprecated;" in m.annotations for m in matches):
                problems.append("%s.%s%s is deprecated" % (label, member, desc))
        elif kind == "abstract-set":
            if not cls.is_interface:
                problems.append(label + " is not an interface")
            elif cls.abstract_methods() != set(member):
                problems.append("%s abstract methods are %s, expected %s" % (label, sorted(cls.abstract_methods()), sorted(member)))
        elif kind == "implements":
            if member not in cls.interfaces:
                problems.append("%s does not implement %s (%s)" % (label, member, list(cls.interfaces)))
        else:
            raise ValueError(kind)
        markers.update(a for a in subjects if jars.requires_opt_in(a))
    for cls_name, names in symbol["named"].items():
        cls = jars.get(cls_name)
        for name in names:
            if cls is None or name not in cls.utf8:
                problems.append("%s: named argument %s is not in the constant pool of %s" % (symbol["key"], name, cls_name))
    return problems, markers


# --------------------------------------------------------------------------------------
# Kotlin text readers
# --------------------------------------------------------------------------------------
def _matching(masked, open_index, opening, closing):
    depth = 0
    for index in range(open_index, len(masked)):
        char = masked[index]
        if char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("unbalanced " + opening)


def split_top_level(text):
    """Comma separated top-level items; ->, <>, () [] {} nest."""
    safe = text.replace("->", "__").replace(">=", "__").replace("<=", "__")
    items, depth, start = [], 0, 0
    for index, char in enumerate(safe):
        if char in "([{<":
            depth += 1
        elif char in ")]}>":
            depth -= 1
        elif char == "," and depth == 0:
            items.append(text[start:index])
            start = index + 1
    if text[start:].strip():
        items.append(text[start:])
    return [item.strip() for item in items]


def function_signatures(text, name):
    """[[(parameter name, has default)]] for every `fun [Receiver.]name(` in the text, in order."""
    masked = mask_kotlin(text)
    found = []
    for match in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?%s\s*\(" % re.escape(name), masked):
        close = _matching(masked, match.end() - 1, "(", ")")
        params = []
        for item in split_top_level(masked[match.end():close]):
            head = re.sub(r"@\w+(\([^)]*\))?\s*", "", item)
            head = re.sub(r"^(?:vararg|noinline|crossinline)\s+", "", head)
            colon = head.index(":")
            default = re.search(r"(?<![=!<>])=(?!=)", head[colon:]) is not None
            params.append((head[:colon].strip(), default))
        found.append(params)
    return found


def calls(text, callee):
    """[(named argument names, positional count, trailing lambda)] for every `callee(` call (not a declaration)."""
    masked = mask_kotlin(text)
    found = []
    for match in re.finditer(r"(?<![\w])%s\s*\(" % re.escape(callee), masked):
        if re.search(r"\b(?:fun|class|interface|object)\s+(?:[\w.<>?]+\.)?$", masked[max(0, match.start() - 60):match.start()]):
            continue
        close = _matching(masked, match.end() - 1, "(", ")")
        named, positional = [], 0
        for item in split_top_level(masked[match.end():close]):
            label = re.match(r"(\w+)\s*=(?!=)", item)
            if label:
                named.append(label.group(1))
            else:
                positional += 1
        found.append((tuple(named), positional, masked[close + 1:].lstrip().startswith("{")))
    return found


def probe_tags(text):
    """Keys of the `// probe: <key>` comment lines of the probe."""
    return re.findall(r"(?m)^\s*// probe: ([\w-]+)\s*$", text)


def probe_imports(text):
    """[(imported name, alias or None)] for the import lines of a Kotlin file."""
    return [(m.group(1), m.group(2)) for m in re.finditer(r"(?m)^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", text)]

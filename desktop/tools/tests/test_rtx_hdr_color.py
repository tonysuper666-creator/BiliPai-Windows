"""CPU numerics for the actual embedded HDR shader, without a compiler or GPU.

The oracle rebuilds RGB<->XYZ matrices from ITU-R BT.709/BT.2020 xy primaries
and D65, then checks decoded ST2084 luminance. It does not copy the shader's
rounded gamut coefficients. CPU success does not qualify an HDR display or NGX.
Sources:
https://www.itu.int/rec/R-REC-BT.709-6-201506-I/en
https://www.itu.int/rec/R-REC-BT.2020-2-201510-I/en
https://learn.microsoft.com/en-us/windows/win32/direct3darticles/high-dynamic-range
"""
import ast
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BRIDGE = "desktop/native/mpv-rtx-bridge/bilipai_rtx_mpv_bridge.c"
PRESENT = "desktop/third-party/libmpv/build/rtx-present-v1"
NUMBER = r"(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)"
RATIO = NUMBER + r"(?:/" + NUMBER + r")?"

def source_bytes(relative):
    data = (ROOT / relative).read_bytes()
    if len(data) > 2 * 1024 * 1024:
        raise ValueError("source exceeds numerical fixture bound")
    return data

def source_text(relative):
    return source_bytes(relative).decode("utf-8")

def digest(relative):
    return hashlib.sha256(source_bytes(relative)).hexdigest()

def number(text):
    pieces = text.split("/")
    return float(Fraction(pieces[0]) / Fraction(pieces[1])) if len(pieces) == 2 else float(text)

def embedded_shader(c_source):
    match = re.search(r"static const char shader\[\]\s*=\s*((?:\"(?:\\.|[^\"\\])*\"\s*)+);", c_source)
    if match is None:
        raise ValueError("unrecognized embedded shader declaration")
    literals = re.findall(r"\"(?:\\.|[^\"\\])*\"", match[1])
    return "".join(ast.literal_eval(literal) for literal in literals)

class ActualHdrShader:
    """Evaluate only the bounded arithmetic actually present in hdrPS/pq.

    Both the historical pre-matrix clamp and the corrected signed load are
    understood, so a numerical mutant can demonstrate the regression's signal.
    Any unknown function/body fails rather than silently using a reference model.
    """
    def __init__(self, shader):
        def body(declaration):
            matches = re.findall(re.escape(declaration) + r"\{([^{}]*)\}", re.sub(r"\s+", "", shader))
            if len(matches) != 1:
                raise ValueError("unrecognized shader function")
            return matches[0]
        hdr = body("float4hdrPS(float4p:SV_Position):SV_Target")
        match = re.fullmatch(r"float3x=(.*);float3y=float3\((.*)\);returnfloat4\(pq\(y\),1\);", hdr)
        if match is None:
            raise ValueError("unrecognized HDR body")
        load = re.escape("src.Load(int3(uint2(p.xy),0)).rgb")
        signed = re.fullmatch(load + r"\*(" + NUMBER + r")", match[1])
        clamped = re.fullmatch(r"max\(" + load + r",0\)\*(" + NUMBER + r")", match[1])
        if signed is None and clamped is None:
            raise ValueError("unrecognized scRGB load")
        self.preclamp = clamped is not None
        self.white = number((signed if signed is not None else clamped)[1])
        row = r"dot\(x,float3\((" + NUMBER + r"),(" + NUMBER + r"),(" + NUMBER + r")\)\)"
        rows = re.findall(row, match[2])
        if len(rows) != 3 or re.fullmatch(",".join([row] * 3), match[2]) is None:
            raise ValueError("unrecognized gamut matrix")
        self.matrix = tuple(tuple(number(value) for value in values) for values in rows)
        pq = body("float3pq(float3n)")
        pattern = (r"float3v=pow\(saturate\(n/(" + NUMBER + r")\),(" + RATIO + r")\);"
                   r"returnpow\(\((" + RATIO + r")\+\((" + RATIO + r")\)\*v\)/"
                   r"\(1\+\((" + RATIO + r")\)\*v\),(" + RATIO + r")\);")
        parsed = re.fullmatch(pattern, pq)
        if parsed is None:
            raise ValueError("unrecognized bounded PQ encoder")
        self.peak, self.m1, self.c1, self.c2, self.c3, self.m2 = map(number, parsed.groups())

    def evaluate(self, rgb):
        values = [max(value, 0.0) if self.preclamp else value for value in rgb]
        nits = [sum(weight * value * self.white for weight, value in zip(row, values))
                for row in self.matrix]
        encoded = []
        for value in nits:
            luminance = min(1.0, max(0.0, value / self.peak)) ** self.m1
            encoded.append(((self.c1 + self.c2 * luminance) / (1.0 + self.c3 * luminance)) ** self.m2)
        return tuple(encoded)

def solve(matrix, vector):
    augmented = [list(row) + [value] for row, value in zip(matrix, vector)]
    for column in range(3):
        pivot = max(range(column, 3), key=lambda row: abs(augmented[row][column]))
        augmented[column], augmented[pivot] = augmented[pivot], augmented[column]
        factor = augmented[column][column]
        if abs(factor) < 1e-12:
            raise ValueError("singular reference colorimetry")
        augmented[column] = [value / factor for value in augmented[column]]
        for row in range(3):
            if row != column:
                factor = augmented[row][column]
                augmented[row] = [a - factor * b for a, b in zip(augmented[row], augmented[column])]
    return tuple(row[3] for row in augmented)

def multiply(matrix, vector):
    return tuple(sum(a * b for a, b in zip(row, vector)) for row in matrix)

def rgb_to_xyz(primaries):
    columns = [(x / y, 1.0, (1.0 - x - y) / y) for x, y in primaries]
    unscaled = tuple(tuple(columns[column][row] for column in range(3)) for row in range(3))
    xw, yw = 0.3127, 0.3290
    scales = solve(unscaled, (xw / yw, 1.0, (1.0 - xw - yw) / yw))
    return tuple(tuple(value * scales[column] for column, value in enumerate(row)) for row in unscaled)

# Independent CIE chromaticities, not the HLSL's rounded conversion constants.
REC709 = rgb_to_xyz(((0.640, 0.330), (0.300, 0.600), (0.150, 0.060)))
REC2020 = rgb_to_xyz(((0.708, 0.292), (0.170, 0.797), (0.131, 0.046)))

def expected_2020_nits(scrgb):
    return tuple(value * 80.0 for value in solve(REC2020, multiply(REC709, scrgb)))

def decode_st2084(encoded):
    # Independent inverse EOTF (rather than another copy of the shader's forward encoder).
    powered = encoded ** (32.0 / 2523.0)
    numerator = max(powered - 3424.0 / 4096.0, 0.0)
    denominator = 2413.0 / 128.0 - (2392.0 / 128.0) * powered
    return 10000.0 * (numerator / denominator) ** (16384.0 / 2610.0)

def named_python_constant(relative, name):
    for node in ast.parse(source_text(relative)).body:
        if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == name for target in node.targets):
            if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                return node.value.value
    raise ValueError("missing source identity constant")

class RtxHdrColorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shader_text = embedded_shader(source_text(BRIDGE))
        cls.actual = ActualHdrShader(cls.shader_text)

    def assert_color_nits(self, rgb, shader=None):
        result = (shader if shader is not None else self.actual).evaluate(rgb)
        expected = [min(10000.0, max(0.0, value)) for value in expected_2020_nits(rgb)]
        for encoded, nits in zip(result, expected):
            self.assertTrue(math.isfinite(encoded))
            self.assertGreaterEqual(encoded, 0.0)
            self.assertLessEqual(encoded, 1.0)
            # Six-decimal production matrix coefficients introduce bounded rounding.
            self.assertAlmostEqual(decode_st2084(encoded), nits, delta=0.001)

    def test_signed_scrgb_colors_match_independently_derived_xyz_reference(self):
        for rgb in ((-0.2, 0.8, 0.1), (1.0, -0.05, 0.3), (0.2, 0.1, -0.015), (-0.01, 0.25, 0.25)):
            with self.subTest(rgb=rgb):
                self.assert_color_nits(rgb)

    def test_bt2020_primaries_survive_the_signed_scrgb_transport(self):
        for wide_rgb in ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0), (0.25, 0.1, 0.7)):
            scrgb = solve(REC709, multiply(REC2020, wide_rgb))
            with self.subTest(wide_rgb=wide_rgb):
                self.assert_color_nits(scrgb)

    def test_rgb_channel_order_and_matrix_direction_match_colorimetry(self):
        for rgb in ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0), (0.4, 0.2, 0.1)):
            with self.subTest(rgb=rgb):
                self.assert_color_nits(rgb)

    def test_scrgb_units_and_standard_pq_neutral_reference_values(self):
        for scrgb, nits, pq in ((1.25, 100.0, 0.508078421517399),
            (12.5, 1000.0, 0.751827096247041), (125.0, 10000.0, 1.0)):
            # Golden encoded values remain independent of the production matrix.
            self.assertAlmostEqual(decode_st2084(pq), nits, delta=1e-7)
            for channel, row in zip(self.actual.evaluate((scrgb,) * 3), self.actual.matrix):
                # Three six-decimal coefficients each carry <=0.5e-6 rounding.
                # Existing G/B row sums are 0.999999, so the allowed luminance
                # error scales with nits (0.0001/0.001/0.01 at these references).
                row_error = abs(sum(row) - 1.0)
                self.assertLessEqual(row_error, 3 * 0.5e-6)
                self.assertAlmostEqual(decode_st2084(channel), nits,
                    delta=nits * row_error + 1e-6)

    def test_negative_and_overrange_destination_values_clip_at_pq_entry(self):
        self.assert_color_nits((-1.0, -0.5, -0.25))
        for channel in self.actual.evaluate((200.0,) * 3):
            self.assertEqual(channel, 1.0)
        for channel in self.actual.evaluate((-1.0,) * 3):
            self.assertAlmostEqual(decode_st2084(channel), 0.0, delta=1e-12)

    def test_historical_input_clamp_mutant_has_a_large_color_error(self):
        signed = "src.Load(int3(uint2(p.xy),0)).rgb*80"
        self.assertEqual(self.shader_text.count(signed), 1)
        mutant = ActualHdrShader(self.shader_text.replace(signed,
            "max(src.Load(int3(uint2(p.xy),0)).rgb,0)*80", 1))
        rgb = (-0.2, 0.8, 0.1)
        expected = expected_2020_nits(rgb)
        decoded = tuple(decode_st2084(value) for value in mutant.evaluate(rgb))
        self.assertGreater(max(abs(a - b) for a, b in zip(decoded, expected)), 1.0)
        self.assert_color_nits(rgb)

    def test_actual_delivery_source_identities_follow_the_corrected_shader(self):
        manifest_path = PRESENT + "/bilipai-rtx-presentation-source-manifest.json"
        applier_path = PRESENT + "/apply-rtx-presentation-source.py"
        fixed_path = PRESENT + "/fixed-inputs.json"
        manifest_hash, applier_hash, fixed_hash = map(digest, (manifest_path, applier_path, fixed_path))
        manifest = json.loads(source_text(manifest_path))
        bridge = [row for row in manifest["sourceFiles"] if row["sourcePath"] == BRIDGE]
        self.assertEqual(len(bridge), 1)
        self.assertEqual(bridge[0]["sha256"], digest(BRIDGE))
        self.assertEqual(bridge[0]["bytes"], len(source_bytes(BRIDGE)))
        for path in ("desktop/native/mpv-rtx-bridge/vf_bilipai_rtx.c",
            "desktop/native/mpv-rtx-bridge/bilipai_rtx_frame_provenance.h"):
            rows = [row for row in manifest["sourceFiles"] if row["sourcePath"] == path]
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["sha256"], digest(path))
            self.assertEqual(rows[0]["bytes"], len(source_bytes(path)))
        self.assertEqual(named_python_constant(applier_path, "EXPECTED_MANIFEST_SHA256"), manifest_hash)
        fixed = json.loads(source_text(fixed_path))
        self.assertEqual(fixed["filterSourceManifestSha256"], manifest_hash)
        self.assertEqual(fixed["buildPatchHelperSha256"], applier_hash)
        profile_path = "desktop/tools/native/veyra/veyra-presentation-profile-template.json"
        profile = json.loads(source_text(profile_path))
        self.assertEqual(profile["bridgeSourceSha256"].lower(), digest(BRIDGE))
        vf_path = "desktop/native/mpv-rtx-bridge/vf_bilipai_rtx.c"
        self.assertEqual(profile["vfSourceSha256"].lower(), digest(vf_path))
        self.assertEqual(profile["filterSourceManifestSha256"].lower(), manifest_hash)
        self.assertEqual(profile["sourcePatchHelperSha256"].lower(), applier_hash)
        verifier_path = "desktop/tools/native/veyra/verify-veyra-runtime.ps1"
        self.assertEqual(source_bytes(verifier_path),
            source_bytes("desktop/resources/common/native/veyra-core/verify-veyra-runtime.ps1"))
        verifier_hash = digest(verifier_path)
        # This is the presentation branch's actual fixed supplier pin, not the
        # legacy non-presentation vfSourceSha256 stored in the initial $fixed map.
        fixed_vf = re.findall(r"\$fixed\.vfSourceSha256='([0-9A-F]{64})'", source_text(verifier_path))
        self.assertEqual(fixed_vf, [digest(vf_path).upper()])
        # Verify the actual processResources producer, not an unrelated Gradle literal.
        gradle = source_text("desktop/build.gradle.kts")
        tasks = re.findall(r'^val prepareVeyraRuntimeVerifier by tasks\.registering\(Copy::class\) \{\n(.*?)^\}',
            gradle, flags=re.MULTILINE | re.DOTALL)
        self.assertEqual(len(tasks), 1)
        task = tasks[0]
        expected = re.findall(r'^    val expected = "([0-9a-f]{64})"$', task, flags=re.MULTILINE)
        self.assertEqual(expected, [verifier_hash])
        self.assertIn('val verifier = file("tools/native/veyra/verify-veyra-runtime.ps1")', task)
        self.assertIn('from(verifier)', task)
        self.assertIn('into("resources/common/native/veyra-core")', task)
        self.assertIn('inputs.property("verifierSha256", expected)', task)
        self.assertIn('doFirst { require(verifierHash(verifier) == expected)', task)
        self.assertIn('doLast { require(verifierHash(file("resources/common/native/veyra-core/verify-veyra-runtime.ps1")) == expected)', task)
        self.assertIn('tasks.named("processResources") { dependsOn(prepareVeyraRuntimeVerifier) }', gradle)
        kotlin = source_text("desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopVeyraPrivateComponent.kt")
        for name, value in (("PRESENTATION_SOURCE_SHA256", manifest_hash),
            ("PRESENTATION_HELPER_SHA256", applier_hash), ("VERIFIER_SOURCE_SHA256", verifier_hash)):
            self.assertRegex(kotlin, rf'private const val {name} = "{value}"')
        catalog = "desktop/tools/update/prepare-veyra-compatible-catalog.py"
        self.assertEqual(named_python_constant(catalog, "TEMPLATE_SHA256"), digest(profile_path))
        self.assertEqual(named_python_constant(catalog, "VERIFIER_SHA256"), verifier_hash)
        producer = source_text("desktop/tools/native/build-mpv-rtx-core-runtime.py")
        self.assertIn("selected_manifest_sha = ('" + manifest_hash + "'", producer)
        self.assertIn("if presentation and sha(fixed_raw) != '" + fixed_hash + "'", producer)
        self.assertIn("source_receipt.get('sourcePatchHelperSha256') != '" + applier_hash + "'", producer)
        for relative in ("desktop/tools/native/upload-mpv-rtx-core-draft.py",
            "desktop/tools/native/mpv-rtx-core-runtime-descriptor.ps1",
            "desktop/tools/native/veyra/verify-veyra-runtime.ps1",
            "desktop/tools/native/veyra/assemble-veyra-private-component.ps1"):
            text = source_text(relative).lower()
            self.assertIn(manifest_hash, text, relative)
            self.assertIn(applier_hash, text, relative)
        assembler = source_text("desktop/tools/native/veyra/assemble-veyra-private-component.ps1")
        self.assertIn("$contracts[$buildFolder+'/fixed-inputs.json']='" + fixed_hash + "'", assembler)
        self.assertIn("$contracts[$templateContract]='" + digest(profile_path) + "'", assembler)
        self.assertIn("'tools/native/veyra/verify-veyra-runtime.ps1'='" + verifier_hash + "'", assembler)

if __name__ == "__main__":
    unittest.main()

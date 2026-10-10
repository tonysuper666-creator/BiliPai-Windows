"""CPU arithmetic from the actual embedded output-fusion shader.

Independent sRGB goldens, pixel-center coordinates and XYZ/PQ colorimetry form
an oracle; unknown shader bodies fail closed. This does not execute HLSL/NGX or
qualify performance, hardware or an HDR display. No CPU pixel path is added.
"""
import math
from pathlib import Path
import re
import unittest
from test_rtx_hdr_color import (
    ActualHdrShader, embedded_shader, source_text, decode_st2084, expected_2020_nits,
)

BRIDGE = "desktop/native/mpv-rtx-bridge/bilipai_rtx_mpv_bridge.c"
NUMBER = r"(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)"


def compact(text):
    return re.sub(r"\s+", "", text)


class ActualIntensityShader:
    def __init__(self, shader):
        code = compact(shader)
        def body(declaration):
            match = re.findall(re.escape(compact(declaration)) + r"\{([^{}]*)\}", code)
            if len(match) != 1:
                raise ValueError("unrecognized output-fusion function")
            return match[0]
        self.old_hdr = ActualHdrShader(shader)
        decoder = body("float3 intensityDecodeSrgb(float3 c)")
        match = re.fullmatch(
            r"float3lo=c/(" + NUMBER + r");float3hi=pow\(\(c\+(" + NUMBER +
            r")\)/(" + NUMBER + r"),(" + NUMBER + r")\);returnfloat3\("
            r"c.r<=(" + NUMBER + r")\?lo.r:hi.r,c.g<=\5\?lo.g:hi.g,c.b<=\5\?lo.b:hi.b\);",
            decoder)
        if match is None:
            raise ValueError("unrecognized fixed sRGB EOTF")
        self.low_divisor, self.offset, self.high_divisor, self.gamma, self.threshold = map(float, match.groups())
        sampler = body("float3 intensityOriginalLinear(float2 p)")
        prefix = ("uintiw,ih,ow,oh;original.GetDimensions(iw,ih);src.GetDimensions(ow,oh);"
                  "float2q=p*float2(iw,ih)/float2(ow,oh)-")
        match = re.match(re.escape(prefix) + r"(" + NUMBER + r");", sampler)
        if match is None:
            raise ValueError("unrecognized actual texture extent/center mapping")
        self.center = float(match[1])
        remainder = sampler[match.end():]
        expected = "int2a=int2(floor(q));float2f=frac(q);int2end=int2(iw-1,ih-1);"
        for name, coordinate in (("c00", "a"), ("c10", "a+int2(1,0)"),
                                 ("c01", "a+int2(0,1)"), ("c11", "a+int2(1,1)")):
            expected += ("float3" + name + "=intensityDecodeSrgb(original.Load(int3(clamp(" +
                         coordinate + ",0,end),0)).rgb);")
        expected += "returnlerp(lerp(c00,c10,f.x),lerp(c01,c11,f.x),f.y);"
        if remainder != expected:
            raise ValueError("unrecognized decode-before-bilinear sampler")
        mixing = body("float3 intensityMix(float3 a,float3 b)")
        match = re.fullmatch(r"returnlerp\(a,b,float\(intensityPercent\)/(" + NUMBER + r")\);", mixing)
        if match is None:
            raise ValueError("unrecognized output weighting")
        self.weight_divisor = float(match[1])
        sr = body("float4 srBlendPS(float4 p:SV_Position):SV_Target")
        if sr != ("float3enhanced=intensityDecodeSrgb(src.Load(int3(uint2(p.xy),0)).rgb);"
                  "returnfloat4(srgb(intensityMix(intensityOriginalLinear(p.xy),enhanced)),1);"):
            raise ValueError("unrecognized linear SDR output path")
        hdr = body("float4 hdrBlendPS(float4 p:SV_Position):SV_Target")
        match = re.fullmatch(
            r"float3x=intensityMix\(intensityOriginalLinear\(p.xy\)\*(" + NUMBER + r"),"
            r"(src.Load\(int3\(uint2\(p.xy\),0\)\).rgb|max\(src.Load\(int3\(uint2\(p.xy\),0\)\).rgb,0\))"
            r"\*(" + NUMBER + r")\);float3y=float3\((.*)\);returnfloat4\(pq\(y\),1\);", hdr)
        if match is None:
            raise ValueError("unrecognized absolute-nit signed HDR output path")
        self.base_nits, self.hdr_preclamp, self.enhanced_nits = float(match[1]), match[2].startswith("max("), float(match[3])
        row = r"dot\(x,float3\((" + NUMBER + r"),(" + NUMBER + r"),(" + NUMBER + r")\)\)"
        rows = re.findall(row, match[4])
        if len(rows) != 3 or re.fullmatch(",".join([row] * 3), match[4]) is None:
            raise ValueError("unrecognized signed gamut mapping")
        self.hdr_matrix = tuple(tuple(map(float, values)) for values in rows)
        encoder = body("float3 srgb(float3 c)")
        match = re.fullmatch(
            r"c=max\(c,0\);returnfloat3\(c.r<=(" + NUMBER + r")\?(" + NUMBER +
            r")\*c.r:(" + NUMBER + r")\*pow\(c.r,1/(" + NUMBER + r")\)-(" + NUMBER +
            r"),c.g<=\1\?\2\*c.g:\3\*pow\(c.g,1/\4\)-\5,c.b<=\1\?\2\*c.b:\3\*pow\(c.b,1/\4\)-\5\);", encoder)
        if match is None:
            raise ValueError("unrecognized actual sRGB encoder")
        self.encode_threshold, self.encode_low, self.encode_high, self.encode_gamma, self.encode_offset = map(float, match.groups())

    def decode(self, value):
        return value / self.low_divisor if value <= self.threshold else ((value + self.offset) / self.high_divisor) ** self.gamma

    def encode(self, value):
        value = max(value, 0)
        return value * self.encode_low if value <= self.encode_threshold else self.encode_high * value ** (1 / self.encode_gamma) - self.encode_offset

    def original(self, image, x, y, ow, oh):
        ih, iw = len(image), len(image[0])
        sx, sy = (x + .5) * iw / ow - self.center, (y + .5) * ih / oh - self.center
        ax, ay = math.floor(sx), math.floor(sy)
        fx, fy = sx - ax, sy - ay
        def pixel(px, py):
            return tuple(self.decode(v) for v in image[min(ih-1, max(0, py))][min(iw-1, max(0, px))])
        p00, p10, p01, p11 = pixel(ax, ay), pixel(ax+1, ay), pixel(ax, ay+1), pixel(ax+1, ay+1)
        return tuple((p00[c]*(1-fx)+p10[c]*fx)*(1-fy)+(p01[c]*(1-fx)+p11[c]*fx)*fy for c in range(3))

    def mix(self, a, b, percent):
        weight = percent / self.weight_divisor
        return tuple(x + (y-x)*weight for x, y in zip(a, b))

    def sr(self, image, enhanced, x, y, ow, oh, percent):
        if percent == 100:
            return tuple(enhanced)
        return tuple(self.encode(v) for v in self.mix(self.original(image, x, y, ow, oh), tuple(self.decode(v) for v in enhanced), percent))

    def hdr(self, image, enhanced, x, y, ow, oh, percent):
        if percent == 100:
            return self.old_hdr.evaluate(enhanced)
        base = tuple(v * self.base_nits for v in self.original(image, x, y, ow, oh))
        enhanced = tuple((max(v, 0) if self.hdr_preclamp else v) * self.enhanced_nits for v in enhanced)
        rgb = self.mix(base, enhanced, percent)
        nits = tuple(sum(a*b for a, b in zip(row, rgb)) for row in self.hdr_matrix)
        encoder = self.old_hdr
        return tuple(((encoder.c1 + encoder.c2 * (min(1, max(0, value / encoder.peak)) ** encoder.m1)) /
                      (1 + encoder.c3 * (min(1, max(0, value / encoder.peak)) ** encoder.m1))) ** encoder.m2 for value in nits)


class RtxOutputIntensityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = source_text(BRIDGE)
        cls.shader = embedded_shader(cls.source)
        cls.actual = ActualIntensityShader(cls.shader)

    def test_fixed_srgb_goldens_do_not_redecode_using_original_gamma24(self):
        for encoded, linear in ((0, 0), (.04045, .0031308049535603713), (.5, .21404114048223255), (1, 1)):
            self.assertAlmostEqual(self.actual.decode(encoded), linear, delta=1e-14)
        # The original inputPS may decode BT1886; fusion receives its sRGB result.
        self.assertNotIn("gamma24", re.search(r"float3 intensityDecodeSrgb\([^{}]+\{([^{}]*)\}", self.shader)[1])
        self.assertAlmostEqual(self.actual.encode(.5), .7353569830524495, delta=1e-14)

    def test_original_sampling_uses_real_extents_linear_neighbors_and_clamped_edges(self):
        black, white = (0, 0, 0), (1, 1, 1)
        checker = [[black, white], [white, black]]
        self.assertEqual(self.actual.original(checker, 0, 0, 3, 3), black)
        self.assertEqual(self.actual.original(checker, 2, 0, 3, 3), white)
        for value in self.actual.original(checker, 1, 1, 3, 3):
            self.assertAlmostEqual(value, .5, delta=1e-15)
            self.assertGreater(abs(value - self.actual.decode(.5)), .28)
        # A half-pixel mutant stays parseable and must fail the independent center oracle.
        mutant = ActualIntensityShader(self.shader.replace("float2(ow,oh)-.5", "float2(ow,oh)-0", 1))
        self.assertNotEqual(mutant.original(checker, 1, 1, 3, 3), (.5, .5, .5))

    def test_sdr_weight_goldens_and_full_preserve_the_original_output_values(self):
        image = [[(0, 0, 0)]]
        for percent, expected in ((50, .7353569830524495), (75, .8808250210902997), (100, 1)):
            for value in self.actual.sr(image, (1, 1, 1), 0, 0, 1, 1, percent):
                self.assertAlmostEqual(value, expected, delta=1e-14)
        enhanced = (.007, .421, .983)
        self.assertEqual(self.actual.sr(image, enhanced, 0, 0, 1, 1, 100), enhanced)

    def test_hdr_uses_explicit80nit_sdr_white_and_actual_signed_absolute_nit_weights(self):
        image = [[(1, 1, 1)]]
        for percent, expected in ((50, 540), (75, 770), (100, 1000)):
            for encoded in self.actual.hdr(image, (12.5, 12.5, 12.5), 0, 0, 1, 1, percent):
                self.assertAlmostEqual(decode_st2084(encoded), expected, delta=.002)

    def test_signed_hdr_blend_matches_independent_xyz_and_preclamp_mutant_fails(self):
        image = [[(0, 0, 0)]]
        enhanced = (-.2, .8, .1)
        for percent in (50, 75, 100):
            expected = expected_2020_nits(tuple(v * percent / 100 for v in enhanced))
            for encoded, nits in zip(self.actual.hdr(image, enhanced, 0, 0, 1, 1, percent), expected):
                self.assertAlmostEqual(decode_st2084(encoded), max(0, nits), delta=.001)
        token = "intensityOriginalLinear(p.xy)*80,src.Load(int3(uint2(p.xy),0)).rgb*80"
        self.assertEqual(self.shader.count(token), 1)
        mutant = ActualIntensityShader(self.shader.replace(token,
            "intensityOriginalLinear(p.xy)*80,max(src.Load(int3(uint2(p.xy),0)).rgb,0)*80", 1))
        actual = self.actual.hdr(image, enhanced, 0, 0, 1, 1, 50)
        wrong = mutant.hdr(image, enhanced, 0, 0, 1, 1, 50)
        self.assertGreater(max(abs(decode_st2084(a)-decode_st2084(b)) for a, b in zip(actual, wrong)), 1)

    def test_full_dispatch_selects_old_entries_and_new_draw_stays_inside_final_fence(self):
        draw = self.source.split("static void draw_output(struct bv_mpv_bridge *p) {", 1)[1].split("static HRESULT source_views", 1)[0]
        branch = draw.split("if(p->config.intensity_percent==100){", 1)[1].split("return;", 1)[0]
        self.assertIn("p->hdr_ps:p->sr_ps", branch)
        self.assertNotIn("input_srv", branch)
        self.assertNotIn("blend_ps", branch)
        process = self.source.split("int bv_mpv_bridge_process(", 1)[1].split("#undef RETURN", 1)[0]
        self.assertLess(process.index("ID3D11DeviceContext4_Wait"), process.index("draw_output(p)"))
        self.assertLess(process.index("draw_output(p)"), process.index("p->consumer_done=++p->consumer_value"))
        self.assertLess(draw.index("draw(p,p->present_rtv,(p->config.effects&BV_VIDEO_HDR)?p->hdr_blend_ps"),
                        draw.index("PSSetShaderResources(p->context,2,1,&empty_view)"))


if __name__ == "__main__":
    unittest.main()

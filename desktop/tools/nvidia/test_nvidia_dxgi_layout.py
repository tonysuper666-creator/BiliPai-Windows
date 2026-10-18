"""Source checks for the NVIDIA DXGI detail probe and the physical adapter identity (slice S2).

The Java descriptor offsets and the DisplayConfig adapter-name packet are compared with ctypes
structures that the platform ABI lays out independently of the Java and Kotlin text. The checks
read source only: nothing here loads dxgi, user32, a driver or a GPU."""
import ctypes
import re
import struct
import sys
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

JAVA = source.REPO / "desktop/src/main/java/com/bilipai/desktop/player/DesktopWindowsDxgiAdapters.java"
JAVA_TEST = source.ST / "kotlin/com/bilipai/desktop/player/DesktopWindowsDxgiAdaptersTest.kt"
PROBE = source.KN / "DesktopWindowsGpuProbe.kt"
MONITOR = source.KN / "DesktopNvidiaCapabilityMonitor.kt"


class Luid(ctypes.Structure):
    _fields_ = [("LowPart", ctypes.c_uint32), ("HighPart", ctypes.c_int32)]


class DxgiAdapterDesc1(ctypes.Structure):
    """DXGI_ADAPTER_DESC1 from dxgi.h. The WCHAR array is spelled as 16-bit units so the layout
    does not depend on the size of the host wchar_t."""
    _fields_ = [
        ("Description", ctypes.c_uint16 * 128),
        ("VendorId", ctypes.c_uint32),
        ("DeviceId", ctypes.c_uint32),
        ("SubSysId", ctypes.c_uint32),
        ("Revision", ctypes.c_uint32),
        ("DedicatedVideoMemory", ctypes.c_size_t),
        ("DedicatedSystemMemory", ctypes.c_size_t),
        ("SharedSystemMemory", ctypes.c_size_t),
        ("AdapterLuid", Luid),
        ("Flags", ctypes.c_uint32),
    ]


class DisplayConfigDeviceInfoHeader(ctypes.Structure):
    _fields_ = [("type", ctypes.c_uint32), ("size", ctypes.c_uint32), ("adapterId", Luid), ("id", ctypes.c_uint32)]


class DisplayConfigAdapterName(ctypes.Structure):
    _fields_ = [("header", DisplayConfigDeviceInfoHeader), ("adapterDevicePath", ctypes.c_uint16 * 128)]


def java_constants():
    text = source.read(JAVA)
    return {name: int(value) for name, value in re.findall(r"public static final int (DESC1_[A-Z_]+) = (\d+);", text)}


def kotlin_constants(text):
    values = {}
    for name, value in re.findall(r"const val ([A-Z_]+): (?:Int|Long) = (\d+)L?\n", text):
        values[name] = int(value)
    return values


def member(text, marker):
    """Source text of the member that starts at the marker, through its closing brace."""
    assert text.count(marker) == 1, marker
    start = text.index(marker)
    depth = 0
    index = text.index("{", start)
    while True:
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
        index += 1


def guid_from_literals(text, marker):
    """Rebuilds the 16 GUID bytes that the Java code writes after the marker comment."""
    start = text.index(marker)
    snippet = text[start:start + 700]
    data1 = int(re.search(r"iid\.setInt\(0, (0x[0-9a-fA-F]+)\)", snippet).group(1), 16)
    data2 = int(re.search(r"iid\.setShort\(4, \(short\) (0x[0-9a-fA-F]+)\)", snippet).group(1), 16)
    data3 = int(re.search(r"iid\.setShort\(6, \(short\) (0x[0-9a-fA-F]+)\)", snippet).group(1), 16)
    block = re.search(r"iid\.write\(8, new byte\[\] \{(.*?)\}, 0, 8\)", snippet, re.S).group(1)
    tail = bytes(int(item, 16) for item in re.findall(r"0x[0-9a-fA-F]{1,2}", block))
    assert len(tail) == 8
    return struct.pack("<IHH", data1, data2, data3) + tail


class DescriptorLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if ctypes.sizeof(ctypes.c_void_p) != 8:
            raise unittest.SkipTest("the x64 layout can only be checked on a 64-bit interpreter")

    def test_ctypes_descriptor_has_the_documented_x64_size_and_offsets(self):
        self.assertEqual(312, ctypes.sizeof(DxgiAdapterDesc1))
        offsets = {
            "VendorId": 256, "DeviceId": 260, "SubSysId": 264, "Revision": 268,
            "DedicatedVideoMemory": 272, "DedicatedSystemMemory": 280, "SharedSystemMemory": 288,
            "AdapterLuid": 296, "Flags": 304,
        }
        for name, expected in offsets.items():
            self.assertEqual(expected, getattr(DxgiAdapterDesc1, name).offset, name)
        self.assertEqual(0, Luid.LowPart.offset)
        self.assertEqual(4, Luid.HighPart.offset)

    def test_java_constants_equal_the_ctypes_offsets(self):
        constants = java_constants()
        expected = {
            "DESC1_SIZE": ctypes.sizeof(DxgiAdapterDesc1),
            "DESC1_VENDOR_OFFSET": DxgiAdapterDesc1.VendorId.offset,
            "DESC1_DEVICE_OFFSET": DxgiAdapterDesc1.DeviceId.offset,
            "DESC1_SUBSYS_OFFSET": DxgiAdapterDesc1.SubSysId.offset,
            "DESC1_REVISION_OFFSET": DxgiAdapterDesc1.Revision.offset,
            "DESC1_DEDICATED_VIDEO_MEMORY_OFFSET": DxgiAdapterDesc1.DedicatedVideoMemory.offset,
            "DESC1_DEDICATED_SYSTEM_MEMORY_OFFSET": DxgiAdapterDesc1.DedicatedSystemMemory.offset,
            "DESC1_SHARED_SYSTEM_MEMORY_OFFSET": DxgiAdapterDesc1.SharedSystemMemory.offset,
            "DESC1_LUID_LOW_OFFSET": DxgiAdapterDesc1.AdapterLuid.offset + Luid.LowPart.offset,
            "DESC1_LUID_HIGH_OFFSET": DxgiAdapterDesc1.AdapterLuid.offset + Luid.HighPart.offset,
            "DESC1_FLAGS_OFFSET": DxgiAdapterDesc1.Flags.offset,
        }
        self.assertEqual(expected, constants)

    def test_the_existing_probe_literals_agree_with_the_same_layout(self):
        # probe() predates this slice and reads the descriptor with literal offsets; they must not drift.
        text = source.read(JAVA)
        body = member(text, "private static Snapshot probeWindows()")
        self.assertIn("new Memory(312)", body)
        self.assertIn("description.getInt(256), description.getInt(260),", body)
        self.assertIn("description.getInt(304)", body)

    def test_parse_desc1_reads_only_documented_offsets_with_the_right_widths(self):
        body = member(source.read(JAVA), "public static AdapterDetail parseDesc1(Pointer description)")
        for expected in (
            "description.getInt(DESC1_VENDOR_OFFSET)", "description.getInt(DESC1_DEVICE_OFFSET)",
            "description.getInt(DESC1_SUBSYS_OFFSET)", "description.getInt(DESC1_REVISION_OFFSET)",
            "description.getLong(DESC1_DEDICATED_VIDEO_MEMORY_OFFSET)",
            "description.getInt(DESC1_LUID_LOW_OFFSET)", "description.getInt(DESC1_LUID_HIGH_OFFSET)",
            "description.getInt(DESC1_FLAGS_OFFSET)", "boundedDescription(description)", ", -1)",
        ):
            self.assertIn(expected, body)
        self.assertNotIn("SYSTEM_MEMORY", body, "system and shared memory are not video memory")

    def test_kotlin_synthetic_block_test_writes_the_same_offsets(self):
        text = source.read(JAVA_TEST)
        for expected in ("Memory(312)", "block.setInt(256, vendor)", "block.setInt(260, device)",
                         "block.setInt(264, subSys)", "block.setInt(268, revision)", "block.setLong(272, videoMemory)",
                         "block.setLong(280,", "block.setLong(288,", "block.setInt(296, luidLow)",
                         "block.setInt(300, luidHigh)", "block.setInt(304, flags)"):
            self.assertIn(expected, text)


class AdapterNamePacketTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if ctypes.sizeof(ctypes.c_void_p) != 8:
            raise unittest.SkipTest("the x64 layout can only be checked on a 64-bit interpreter")
        cls.text = source.read(PROBE)
        cls.constants = kotlin_constants(cls.text)

    def test_ctypes_packet_is_276_bytes_with_the_path_at_20(self):
        self.assertEqual(20, ctypes.sizeof(DisplayConfigDeviceInfoHeader))
        self.assertEqual(276, ctypes.sizeof(DisplayConfigAdapterName))
        self.assertEqual(20, DisplayConfigAdapterName.adapterDevicePath.offset)
        header = DisplayConfigDeviceInfoHeader
        self.assertEqual((0, 4, 8, 16), (header.type.offset, header.size.offset, header.adapterId.offset, header.id.offset))

    def test_kotlin_constants_equal_the_ctypes_layout(self):
        header = DisplayConfigDeviceInfoHeader
        self.assertEqual(4, self.constants["DEVICE_INFO_GET_ADAPTER_NAME"])
        self.assertEqual(ctypes.sizeof(DisplayConfigAdapterName), self.constants["ADAPTER_NAME_PACKET_SIZE"])
        self.assertEqual(header.adapterId.offset + Luid.LowPart.offset, self.constants["ADAPTER_NAME_LUID_LOW_OFFSET"])
        self.assertEqual(header.adapterId.offset + Luid.HighPart.offset, self.constants["ADAPTER_NAME_LUID_HIGH_OFFSET"])
        self.assertEqual(DisplayConfigAdapterName.adapterDevicePath.offset, self.constants["ADAPTER_NAME_PATH_OFFSET"])
        self.assertEqual(DisplayConfigAdapterName.adapterDevicePath.size, self.constants["ADAPTER_NAME_PATH_BYTES"])

    def test_the_request_is_written_with_the_ctypes_header_offsets(self):
        body = member(self.text, "internal fun encodeAdapterNameRequest(packet: Memory, luidHigh: Int, luidLow: Int)")
        for expected in ("packet.clear()", "packet.setInt(0, DEVICE_INFO_GET_ADAPTER_NAME)",
                         "packet.setInt(4, ADAPTER_NAME_PACKET_SIZE)",
                         "packet.setInt(ADAPTER_NAME_LUID_LOW_OFFSET, luidLow)",
                         "packet.setInt(ADAPTER_NAME_LUID_HIGH_OFFSET, luidHigh)", "packet.setInt(16, 0)"):
            self.assertIn(expected, body)

    def test_the_path_is_the_wide_text_up_to_the_first_nul(self):
        body = member(self.text, "internal fun decodeAdapterNamePath(packet: Pointer): String?")
        self.assertIn("Charsets.UTF_16LE", body)
        self.assertIn("substringBefore('\\u0000')", body)

    def test_only_get_adapter_name_is_requested_from_user32_through_the_jna_core(self):
        code, _ = source.lex_kotlin(self.text)
        # Slice S5 adds the read-only foreground window reads to this file with their own user32 binding, so the
        # one-binding rule is scoped to the identity object. The whole file still declares the adapter query once.
        identity_code, _ = source.lex_kotlin(member(self.text, "internal object DesktopWindowsAdapterIdentity"))
        self.assertEqual(1, identity_code.count("Native.load(\"user32\""))
        self.assertEqual(1, identity_code.count("fun DisplayConfigGetDeviceInfo(packet: Pointer): Int"))
        self.assertEqual(1, code.count("fun DisplayConfigGetDeviceInfo(packet: Pointer): Int"))
        self.assertNotIn("com.sun.jna.platform", code)
        for setter in ("SetDisplayConfig", "ChangeDisplaySettings", "DisplayConfigSetDeviceInfo"):
            self.assertNotIn(setter, code)


class DxgiJavaSurfaceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(JAVA)

    def test_vtable_slots_match_the_dxgi_interfaces(self):
        text = self.text
        # IDXGIFactory1: EnumAdapters1 is slot 12 and IsCurrent is slot 13.
        self.assertGreaterEqual(text.count("method(factory, 12)"), 2)
        self.assertGreaterEqual(text.count("method(factory, 13)"), 2)
        # IDXGIAdapter1: EnumOutputs is slot 7, CheckInterfaceSupport slot 9, GetDesc1 slot 10.
        self.assertEqual(1, text.count("method(adapter, 7)"))
        self.assertEqual(1, text.count("method(adapter, 9)"))
        self.assertGreaterEqual(text.count("method(adapter, 10)"), 3)
        # IUnknown::Release is slot 2.
        self.assertIn("method(instance, 2)", text)

    def test_detail_probe_surface_is_present_and_additive(self):
        text = self.text
        self.assertIn("public record Adapter(int vendorId, int deviceId, int flags, String description)", text)
        self.assertIn("public boolean useBitblt()", text)
        self.assertIn("public static Snapshot probe()", text)
        self.assertIn("public record AdapterDetail(int vendorId, int deviceId, int subSysId, int revision,", text)
        for component in ("long dedicatedVideoMemory", "int luidLow", "int luidHigh", "int flags",
                          "String description", "int outputCount"):
            self.assertIn(component, text)
        self.assertIn("public static AdapterDetail parseDesc1(Pointer description)", text)
        self.assertIn("public static DetailedSnapshot probeDetailed()", text)
        self.assertIn("public static long umdVersionFor(int luidLow, int luidHigh)", text)
        self.assertIn("public static String luidHex(int luidHigh, int luidLow)", text)
        self.assertIn("public String luidHex()", text)
        self.assertEqual(2, text.count("public boolean isSoftware()"), "Adapter and AdapterDetail each own one")

    def test_only_the_selected_adapter_is_asked_for_its_driver_version(self):
        text = self.text
        for loop in ("private static Snapshot probeWindows()", "private static DetailedSnapshot probeDetailedWindows()"):
            body = member(text, loop)
            self.assertNotIn("umdVersionFor", body, loop)
            self.assertNotIn("checkInterfaceSupport", body, loop)
            self.assertNotIn("method(adapter, 9)", body, loop)
        self.assertEqual(1, text.count("checkInterfaceSupport(adapter)"), "called once, for the matching LUID only")
        owner = member(text, "private static long umdVersionForWindows(int luidLow, int luidHigh)")
        self.assertIn("checkInterfaceSupport(adapter)", owner)
        self.assertLess(owner.index("DESC1_LUID_LOW_OFFSET"), owner.index("checkInterfaceSupport(adapter)"))
        check = member(text, "private static long checkInterfaceSupport(Pointer adapter)")
        self.assertIn("method(adapter, 9).invokeInt(new Object[] {adapter, iid, version})", check)
        self.assertIn("version.getLong(0)", check)
        self.assertIn("supported == 0", check)

    def test_outputs_are_counted_with_a_cap_and_only_for_nvidia_hardware(self):
        text = self.text
        loop = member(text, "private static DetailedSnapshot probeDetailedWindows()")
        self.assertLess(loop.index("isNvidiaHardware(detail)"), loop.index("countOutputs(adapter)"))
        count = member(text, "private static int countOutputs(Pointer adapter)")
        for expected in ("MAX_OUTPUTS", "DXGI_ERROR_NOT_FOUND", "release(found.getValue())", "return -1"):
            self.assertIn(expected, count)
        self.assertIn("private static final int MAX_OUTPUTS = 16;", text)
        self.assertIn("private static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;", text)

    def test_the_guid_literals_are_the_documented_interfaces(self):
        text = self.text
        # The factory GUID is written by two methods (the original probe and the shared helper).
        factory = uuid.UUID("770aae78-f26f-4dba-a829-253c83d1b387").bytes_le
        self.assertEqual(factory, guid_from_literals(text, "private static Pointer createFactory(Dxgi dxgi)"))
        self.assertEqual(factory, guid_from_literals(text, "private static Snapshot probeWindows()"))
        device = uuid.UUID("54ec77fa-1377-44e6-8c32-88fd5f44c84c").bytes_le
        self.assertEqual(device, guid_from_literals(text, "// IID_IDXGIDevice: 54ec77fa-1377-44e6-8c32-88fd5f44c84c."))

    def test_the_new_entry_points_keep_the_same_windows_and_abi_gate(self):
        text = self.text
        for name in ("public static DetailedSnapshot probeDetailed()", "public static long umdVersionFor(int luidLow, int luidHigh)"):
            body = member(text, name)
            self.assertIn("Platform.isWindows()", body, name)
            self.assertIn("Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2", body, name)
            self.assertIn("catch (RuntimeException | LinkageError failure)", body, name)

    def test_no_device_is_created_and_nothing_is_written(self):
        code = self.text
        for forbidden in ("D3D11CreateDevice", "D3D12CreateDevice", "CreateSwapChain", "CreateSoftwareAdapter",
                          "SetPrivateData", "RegSetValue", "ChangeDisplaySettings"):
            self.assertNotIn(forbidden, code)

    def test_the_java_file_compiles_standalone_against_jna_only(self):
        # tools/diagnostics/run-render-probe.py compiles this one file with only the JNA jar on the class path.
        imports = re.findall(r"^import ([\w.*]+);", self.text, re.M)
        for name in imports:
            self.assertTrue(name.startswith(("com.sun.jna.", "java.util.")), name)


class KotlinProbeCompositionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(MONITOR)
        cls.code, cls.literals = source.lex_kotlin(cls.text)

    def test_the_driver_version_is_read_once_after_the_primary_is_selected(self):
        probe = member(self.text, "override fun probe(): NvidiaProbeInput")
        self.assertEqual(1, probe.count("umdVersionFor("))
        self.assertLess(probe.index("selectNvidiaPrimary(input)"), probe.index("umdVersionFor("))
        self.assertNotIn("umdVersionFor", member(self.text, "private fun evidenceFor("))

    def test_only_nvidia_hardware_candidates_get_a_physical_check(self):
        evidence = member(self.text, "private fun evidenceFor(")
        self.assertIn("detail.vendorId() == NVIDIA_VENDOR_ID && !detail.isSoftware()", evidence)
        self.assertIn("NvidiaPhysicalVerdict.UNKNOWN", evidence)

    def test_the_real_probe_uses_the_detailed_inventory_and_the_adapter_identity(self):
        self.assertIn("DesktopWindowsDxgiAdapters.probeDetailed()", self.code)
        self.assertIn("DesktopWindowsAdapterIdentity.physicalVerdict(luidHigh, luidLow, dxgiDeviceId)", self.code)
        self.assertIn("snapshot.complete() && snapshot.error() == null", self.code)


if __name__ == "__main__":
    unittest.main()

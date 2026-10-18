package com.bilipai.desktop.player;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only, per-native-session DXGI inventory. Requires only the existing JNA core.
 * ABI: Microsoft dxgi.h, IDXGIFactory1 / IDXGIAdapter1 / DXGI_ADAPTER_DESC1.
 * Software classification matches mpv 69e63f425a d3d11_helpers.c:576-582:
 * a primary Basic Render Driver can have Flags=0 but still be 1414:008c.
 * This inventory is not an observation of the GPU actually chosen by mpv.
 */
public final class DesktopWindowsDxgiAdapters {
    private static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;
    private static final int SOFTWARE_FLAG = 2;
    private static final int MAX_ADAPTERS = 64;

    private DesktopWindowsDxgiAdapters() {}

    public record Adapter(int vendorId, int deviceId, int flags, String description) {
        public boolean isSoftware() {
            // REMOTE is reserved and unknown flag bits are not evidence of a software adapter.
            return (flags == 0 || flags == SOFTWARE_FLAG) &&
                ((flags & SOFTWARE_FLAG) != 0 || (vendorId == 0x1414 && deviceId == 0x008c));
        }
    }

    public record Snapshot(boolean complete, List<Adapter> adapters, String error) {
        public Snapshot {
            adapters = List.copyOf(adapters);
        }

        /** A mixed, empty, incomplete or failed inventory keeps mpv's default flip mode. */
        public boolean useBitblt() {
            return complete && error == null && !adapters.isEmpty() &&
                adapters.stream().allMatch(Adapter::isSoftware);
        }
    }

    // x64 DXGI_ADAPTER_DESC1 is 312 bytes after native 8-byte alignment (Microsoft dxgi.h):
    // WCHAR Description[128] at 0, UINT VendorId at 256, DeviceId at 260, SubSysId at 264,
    // Revision at 268, SIZE_T DedicatedVideoMemory at 272, DedicatedSystemMemory at 280,
    // SharedSystemMemory at 288, LUID {DWORD LowPart at 296, LONG HighPart at 300}, UINT Flags at 304.
    public static final int DESC1_SIZE = 312;
    public static final int DESC1_VENDOR_OFFSET = 256;
    public static final int DESC1_DEVICE_OFFSET = 260;
    public static final int DESC1_SUBSYS_OFFSET = 264;
    public static final int DESC1_REVISION_OFFSET = 268;
    public static final int DESC1_DEDICATED_VIDEO_MEMORY_OFFSET = 272;
    public static final int DESC1_DEDICATED_SYSTEM_MEMORY_OFFSET = 280;
    public static final int DESC1_SHARED_SYSTEM_MEMORY_OFFSET = 288;
    public static final int DESC1_LUID_LOW_OFFSET = 296;
    public static final int DESC1_LUID_HIGH_OFFSET = 300;
    public static final int DESC1_FLAGS_OFFSET = 304;
    private static final int NVIDIA_VENDOR_ID = 0x10DE;
    private static final int MAX_OUTPUTS = 16;

    /**
     * Full DXGI_ADAPTER_DESC1 identity of one adapter. outputCount is -1 when the attached
     * outputs were not counted or could not be counted. This is an inventory entry, never an
     * observation of the adapter mpv actually chose.
     */
    public record AdapterDetail(int vendorId, int deviceId, int subSysId, int revision,
            long dedicatedVideoMemory, int luidLow, int luidHigh, int flags, String description,
            int outputCount) {
        /** The same rule as {@link Adapter#isSoftware()}. */
        public boolean isSoftware() {
            return new Adapter(vendorId, deviceId, flags, description).isSoftware();
        }

        /** Sixteen lowercase hex digits, high part first. */
        public String luidHex() {
            return DesktopWindowsDxgiAdapters.luidHex(luidHigh, luidLow);
        }

        public AdapterDetail withOutputCount(int count) {
            return new AdapterDetail(vendorId, deviceId, subSysId, revision, dedicatedVideoMemory,
                luidLow, luidHigh, flags, description, count);
        }
    }

    public record DetailedSnapshot(boolean complete, List<AdapterDetail> adapters, String error) {
        public DetailedSnapshot {
            adapters = List.copyOf(adapters);
        }
    }

    /** Called before mpv_initialize, outside the player lock; no GPU device is created. */
    public static Snapshot probe() {
        if (!Platform.isWindows()) return unknown("non-windows");
        // The portable Windows product and its pinned native DLL are x64. Do not guess another ABI.
        if (Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2) return unknown("unsupported-abi");
        try {
            return probeWindows();
        } catch (RuntimeException | LinkageError failure) {
            // Driver/library failures must not prevent playback or change the hardware default.
            return unknown("dxgi-native-unavailable");
        }
    }

    private static Snapshot unknown(String error) {
        return new Snapshot(false, List.of(), error);
    }

    private interface Dxgi extends StdCallLibrary {
        int CreateDXGIFactory1(Pointer interfaceId, PointerByReference result);
    }

    private static Snapshot probeWindows() {
        Dxgi dxgi = Native.load("dxgi", Dxgi.class);
        Pointer factory = null;
        List<Adapter> adapters = new ArrayList<>();
        try (Memory iid = new Memory(16)) {
            iid.clear();
            // IID_IDXGIFactory1: 770aae78-f26f-4dba-a829-253c83d1b387.
            iid.setInt(0, 0x770aae78);
            iid.setShort(4, (short) 0xf26f);
            iid.setShort(6, (short) 0x4dba);
            iid.write(8, new byte[] {(byte) 0xa8, 0x29, 0x25, 0x3c, (byte) 0x83,
                (byte) 0xd1, (byte) 0xb3, (byte) 0x87}, 0, 8);
            PointerByReference result = new PointerByReference();
            int created = dxgi.CreateDXGIFactory1(iid, result);
            if (created != 0 || result.getValue() == null) return unknown("dxgi-create-failed");
            factory = result.getValue();
            for (int index = 0; index <= MAX_ADAPTERS; index++) {
                PointerByReference found = new PointerByReference();
                // IDXGIFactory1::EnumAdapters1 is vtable slot 12, following IDXGIFactory.
                int enumerated = method(factory, 12).invokeInt(new Object[] {factory, index, found});
                if (enumerated == DXGI_ERROR_NOT_FOUND) {
                    // IsCurrent is slot 13. A topology change is not a complete current inventory.
                    boolean current = method(factory, 13).invokeInt(new Object[] {factory}) != 0;
                    return new Snapshot(current, adapters, current ? null : "dxgi-topology-changed");
                }
                if (enumerated != 0 || found.getValue() == null)
                    return new Snapshot(false, adapters, "dxgi-enumerate-failed");
                Pointer adapter = found.getValue();
                try {
                    if (index == MAX_ADAPTERS)
                        return new Snapshot(false, adapters, "dxgi-adapter-limit");
                    // x64 DXGI_ADAPTER_DESC1: WCHAR[128], four UINTs, three SIZE_Ts,
                    // LUID, UINT Flags; 312 bytes after native 8-byte alignment.
                    try (Memory description = new Memory(312)) {
                        description.clear();
                        // IDXGIAdapter1::GetDesc1 is vtable slot 10, following IDXGIAdapter.
                        int described = method(adapter, 10).invokeInt(new Object[] {adapter, description});
                        if (described != 0)
                            return new Snapshot(false, adapters, "dxgi-describe-failed");
                        adapters.add(new Adapter(description.getInt(256), description.getInt(260),
                            description.getInt(304), boundedDescription(description)));
                    }
                } finally {
                    release(adapter);
                }
            }
            return new Snapshot(false, adapters, "dxgi-adapter-limit");
        } finally {
            release(factory);
        }
    }

    private static String boundedDescription(Pointer description) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 128; index++) {
            char value = (char) (description.getShort(index * 2L) & 0xffff);
            if (value == 0) break;
            text.append(Character.isISOControl(value) ? '?' : value);
        }
        return text.toString();
    }

    private static Function method(Pointer instance, int slot) {
        Pointer table = instance.getPointer(0);
        if (table == null) throw new IllegalStateException("Missing DXGI vtable");
        Pointer address = table.getPointer(slot * (long) Native.POINTER_SIZE);
        if (address == null) throw new IllegalStateException("Missing DXGI method");
        return Function.getFunction(address, Function.ALT_CONVENTION);
    }

    private static void release(Pointer instance) {
        // IUnknown::Release is slot 2. Every successful factory/adapter reference stays local.
        if (instance != null) method(instance, 2).invokeInt(new Object[] {instance});
    }

    /** Sixteen lowercase hex digits, high part first, as the frame provenance prints a LUID. */
    public static String luidHex(int luidHigh, int luidLow) {
        return hex8(luidHigh) + hex8(luidLow);
    }

    private static String hex8(int value) {
        String text = Integer.toHexString(value);
        return "00000000".substring(text.length()) + text;
    }

    /** Decodes one x64 DXGI_ADAPTER_DESC1 block. Pure over memory, so a synthetic block can test it. */
    public static AdapterDetail parseDesc1(Pointer description) {
        return new AdapterDetail(description.getInt(DESC1_VENDOR_OFFSET),
            description.getInt(DESC1_DEVICE_OFFSET), description.getInt(DESC1_SUBSYS_OFFSET),
            description.getInt(DESC1_REVISION_OFFSET),
            Math.max(0L, description.getLong(DESC1_DEDICATED_VIDEO_MEMORY_OFFSET)),
            description.getInt(DESC1_LUID_LOW_OFFSET), description.getInt(DESC1_LUID_HIGH_OFFSET),
            description.getInt(DESC1_FLAGS_OFFSET), boundedDescription(description), -1);
    }

    /**
     * The same inventory as probe() with the full descriptor, the LUID and, for NVIDIA hardware
     * adapters only, the attached output count. Read-only: no device, no user-mode driver load.
     * Called before mpv_initialize, outside the player lock.
     */
    public static DetailedSnapshot probeDetailed() {
        if (!Platform.isWindows()) return unknownDetailed("non-windows");
        if (Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2) return unknownDetailed("unsupported-abi");
        try {
            return probeDetailedWindows();
        } catch (RuntimeException | LinkageError failure) {
            return unknownDetailed("dxgi-native-unavailable");
        }
    }

    /**
     * User-mode driver version of exactly one adapter, packed as four 16-bit parts with the
     * product part highest (32.0.16.1742 style), or 0 when it is unknown. Call it only for the
     * adapter that was already validated: CheckInterfaceSupport loads that adapter's user-mode driver.
     */
    public static long umdVersionFor(int luidLow, int luidHigh) {
        if (!Platform.isWindows()) return 0L;
        if (Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2) return 0L;
        try {
            return umdVersionForWindows(luidLow, luidHigh);
        } catch (RuntimeException | LinkageError failure) {
            return 0L;
        }
    }

    private static DetailedSnapshot unknownDetailed(String error) {
        return new DetailedSnapshot(false, List.of(), error);
    }

    private static boolean isNvidiaHardware(AdapterDetail detail) {
        return detail.vendorId() == NVIDIA_VENDOR_ID && !detail.isSoftware();
    }

    /** Creates an IDXGIFactory1 (IID 770aae78-f26f-4dba-a829-253c83d1b387); null when creation failed. */
    private static Pointer createFactory(Dxgi dxgi) {
        try (Memory iid = new Memory(16)) {
            iid.clear();
            iid.setInt(0, 0x770aae78);
            iid.setShort(4, (short) 0xf26f);
            iid.setShort(6, (short) 0x4dba);
            iid.write(8, new byte[] {(byte) 0xa8, 0x29, 0x25, 0x3c, (byte) 0x83,
                (byte) 0xd1, (byte) 0xb3, (byte) 0x87}, 0, 8);
            PointerByReference result = new PointerByReference();
            int created = dxgi.CreateDXGIFactory1(iid, result);
            return created != 0 ? null : result.getValue();
        }
    }

    /** IDXGIFactory1::EnumAdapters1 is vtable slot 12, following IDXGIFactory. */
    private static int enumAdapters(Pointer factory, int index, PointerByReference found) {
        return method(factory, 12).invokeInt(new Object[] {factory, index, found});
    }

    private static DetailedSnapshot probeDetailedWindows() {
        Dxgi dxgi = Native.load("dxgi", Dxgi.class);
        Pointer factory = createFactory(dxgi);
        if (factory == null) return unknownDetailed("dxgi-create-failed");
        List<AdapterDetail> adapters = new ArrayList<>();
        try {
            for (int index = 0; index <= MAX_ADAPTERS; index++) {
                PointerByReference found = new PointerByReference();
                int enumerated = enumAdapters(factory, index, found);
                if (enumerated == DXGI_ERROR_NOT_FOUND) {
                    // IsCurrent is slot 13. A topology change is not a complete current inventory.
                    boolean current = method(factory, 13).invokeInt(new Object[] {factory}) != 0;
                    return new DetailedSnapshot(current, adapters, current ? null : "dxgi-topology-changed");
                }
                if (enumerated != 0 || found.getValue() == null)
                    return new DetailedSnapshot(false, adapters, "dxgi-enumerate-failed");
                Pointer adapter = found.getValue();
                try {
                    if (index == MAX_ADAPTERS)
                        return new DetailedSnapshot(false, adapters, "dxgi-adapter-limit");
                    try (Memory description = new Memory(DESC1_SIZE)) {
                        description.clear();
                        // IDXGIAdapter1::GetDesc1 is vtable slot 10, following IDXGIAdapter.
                        int described = method(adapter, 10).invokeInt(new Object[] {adapter, description});
                        if (described != 0)
                            return new DetailedSnapshot(false, adapters, "dxgi-describe-failed");
                        AdapterDetail detail = parseDesc1(description);
                        // Only NVIDIA hardware candidates are asked for outputs: other vendors'
                        // adapters never need them and a sleeping hybrid GPU stays untouched.
                        adapters.add(isNvidiaHardware(detail)
                            ? detail.withOutputCount(countOutputs(adapter)) : detail);
                    }
                } finally {
                    release(adapter);
                }
            }
            return new DetailedSnapshot(false, adapters, "dxgi-adapter-limit");
        } finally {
            release(factory);
        }
    }

    /**
     * Attached outputs of one adapter, or -1 when unknown. IDXGIAdapter::EnumOutputs is vtable
     * slot 7, following IDXGIObject; counting stops at DXGI_ERROR_NOT_FOUND and at 16.
     */
    private static int countOutputs(Pointer adapter) {
        try {
            int count = 0;
            while (count < MAX_OUTPUTS) {
                PointerByReference found = new PointerByReference();
                int enumerated = method(adapter, 7).invokeInt(new Object[] {adapter, count, found});
                if (enumerated == DXGI_ERROR_NOT_FOUND) return count;
                if (enumerated != 0 || found.getValue() == null) return -1;
                release(found.getValue());
                count++;
            }
            return count;
        } catch (RuntimeException failure) {
            return -1;
        }
    }

    private static long umdVersionForWindows(int luidLow, int luidHigh) {
        Dxgi dxgi = Native.load("dxgi", Dxgi.class);
        Pointer factory = createFactory(dxgi);
        if (factory == null) return 0L;
        try {
            for (int index = 0; index < MAX_ADAPTERS; index++) {
                PointerByReference found = new PointerByReference();
                if (enumAdapters(factory, index, found) != 0 || found.getValue() == null) return 0L;
                Pointer adapter = found.getValue();
                try {
                    boolean matches = false;
                    try (Memory description = new Memory(DESC1_SIZE)) {
                        description.clear();
                        int described = method(adapter, 10).invokeInt(new Object[] {adapter, description});
                        matches = described == 0
                            && description.getInt(DESC1_LUID_LOW_OFFSET) == luidLow
                            && description.getInt(DESC1_LUID_HIGH_OFFSET) == luidHigh;
                    }
                    if (matches) return checkInterfaceSupport(adapter);
                } finally {
                    release(adapter);
                }
            }
            return 0L;
        } finally {
            release(factory);
        }
    }

    /**
     * IDXGIAdapter::CheckInterfaceSupport is vtable slot 9. It fills a LARGE_INTEGER of four 16-bit
     * parts. A non-zero HRESULT means unknown, never an error.
     */
    private static long checkInterfaceSupport(Pointer adapter) {
        try (Memory iid = new Memory(16); Memory version = new Memory(8)) {
            iid.clear();
            version.clear();
            // IID_IDXGIDevice: 54ec77fa-1377-44e6-8c32-88fd5f44c84c.
            iid.setInt(0, 0x54ec77fa);
            iid.setShort(4, (short) 0x1377);
            iid.setShort(6, (short) 0x44e6);
            iid.write(8, new byte[] {(byte) 0x8c, 0x32, (byte) 0x88, (byte) 0xfd, 0x5f,
                0x44, (byte) 0xc8, 0x4c}, 0, 8);
            int supported = method(adapter, 9).invokeInt(new Object[] {adapter, iid, version});
            return supported == 0 ? Math.max(0L, version.getLong(0)) : 0L;
        }
    }
}

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
}

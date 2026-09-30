/* Modified for BiliPai Windows: authenticated device transport, bounded IO,
 * cancellation, payload redaction and desktop lifecycle (2026).
 * Original Apache-2.0 copyright and license below remain applicable. */
/*
 * Copyright 2014 Vitaly Litvak (vitavaque@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package su.litvak.chromecast.api.v2;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class that discovers ChromeCast devices and holds references to all of them.
 */
public final class ChromeCasts {
    private static final ChromeCasts INSTANCE = new ChromeCasts();
    private final MyServiceListener listener = new MyServiceListener();

    private volatile JmDNS mDNS;

    private final List<ChromeCastsListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<ChromeCastsListener>();
    private final List<ChromeCast> chromeCasts = Collections.synchronizedList(new ArrayList<ChromeCast>());

    private ChromeCasts() {
    }

    /** Returns a copy of the currently seen chrome casts.
     * @return a copy of the currently seen chromecast devices.
     */
    public static List<ChromeCast> get() {
        return new ArrayList<ChromeCast>(INSTANCE.chromeCasts);
    }

    /** Hidden service listener to receive callbacks.
     * Is hidden to avoid messing with it.
     */
    private class MyServiceListener implements ServiceListener {
        @Override
        public void serviceAdded(ServiceEvent se) {
            JmDNS current = mDNS;
            if (current != null && se.getDNS() == current && ChromeCast.SERVICE_TYPE.equals(se.getType())) {
                // PTR/SRV/address can arrive before TXT. The public JmDNS API waits at least one
                // 200ms slice; bound that registration wait and keep subsequent resolution persistent.
                current.requestServiceInfo(se.getType(), se.getName(), true, 200);
            }
        }

        @Override
        public void serviceRemoved(ServiceEvent se) {
            if (se.getDNS() == mDNS && ChromeCast.SERVICE_TYPE.equals(se.getType())) {
                // We have a ChromeCast device unregistering
                List<ChromeCast> copy = get();
                ChromeCast deviceRemoved = null;
                // Probably better keep a map to better lookup devices
                for (ChromeCast device : copy) {
                    if (device.getName().equals(se.getInfo().getName())) {
                        deviceRemoved = device;
                        chromeCasts.remove(device);
                        break;
                    }
                }
                if (deviceRemoved != null) {
                    for (ChromeCastsListener nextListener : listeners) {
                        nextListener.chromeCastRemoved(deviceRemoved);
                    }
                }
            }
        }

        @Override
        public void serviceResolved(ServiceEvent se) {
            JmDNS current = mDNS;
            if (current == null || se.getDNS() != current || !ChromeCast.SERVICE_TYPE.equals(se.getType()) || se.getInfo() == null) return;
            javax.jmdns.ServiceInfo info = se.getInfo().clone();
            // JmDNS hasData() also accepts EMPTY_TXT={0}; that is not the advertised metadata.
            // No specific fn/md/id property is required: valid TXT without a friendly name remains a route.
            if (!info.hasData() || info.getTextBytes().length <= 1 || info.getPort() <= 0 || info.getPort() > 65535 || info.getInet4Addresses().length == 0) return;
            ChromeCast resolved = new ChromeCast(info);
            boolean discovered = true;
            synchronized (chromeCasts) {
                if (mDNS != current) return;
                for (int index = 0; index < chromeCasts.size(); index++) {
                    if (chromeCasts.get(index).getName().equalsIgnoreCase(resolved.getName())) {
                        chromeCasts.set(index, resolved);
                        discovered = false;
                        break;
                    }
                }
                if (discovered) chromeCasts.add(resolved);
            }
            if (discovered) {
                for (ChromeCastsListener nextListener : listeners) nextListener.newChromeCastDiscovered(resolved);
            }
        }
    }

    private void doStartDiscovery(InetAddress addr) throws IOException {
        if (mDNS == null) {
            chromeCasts.clear();

            if (addr != null) {
                mDNS = JmDNS.create(addr);
            } else {
                mDNS = JmDNS.create();
            }
            mDNS.addServiceListener(ChromeCast.SERVICE_TYPE, listener);
        }
    }

    private void doStopDiscovery() throws IOException {
        JmDNS current = mDNS;
        mDNS = null;
        if (current != null) {
            current.removeServiceListener(ChromeCast.SERVICE_TYPE, listener);
            try { current.close(); }
            finally { chromeCasts.clear(); }
        }
    }

    /**
     * Starts ChromeCast device discovery.
     */
    public static void startDiscovery() throws IOException {
        INSTANCE.doStartDiscovery(null);
    }

    /**
     * Starts ChromeCast device discovery.
     *
     * @param addr the address of the interface that should be used for discovery
     */
    public static void startDiscovery(InetAddress addr) throws IOException {
        INSTANCE.doStartDiscovery(addr);
    }

    /**
     * Stops ChromeCast device discovery.
     */
    public static void stopDiscovery() throws IOException {
        INSTANCE.doStopDiscovery();
    }

    /**
     * Restarts discovery by sequentially calling 'stop' and 'start' methods.
     */
    public static void restartDiscovery() throws IOException {
        stopDiscovery();
        startDiscovery();
    }

    /**
     * Restarts discovery by sequentially calling 'stop' and 'start' methods.
     *
     * @param addr the address of the interface that should be used for discovery
     */
    public static void restartDiscovery(InetAddress addr) throws IOException {
        stopDiscovery();
        startDiscovery(addr);
    }

    public static void registerListener(ChromeCastsListener listener) {
        if (listener != null) {
            INSTANCE.listeners.add(listener);
        }
    }

    public static void unregisterListener(ChromeCastsListener listener) {
        if (listener != null) {
            INSTANCE.listeners.remove(listener);
        }
    }
}

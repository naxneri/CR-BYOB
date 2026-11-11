package com.crbyob.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dashboard model: samples the bridge once a second, keeps 60 s histories, derives the hero state
 * and an estimated link score, and turns state changes into plain-language events. The UI and the
 * foreground notification only read {@link #snap}.
 */
final class Dash {
    static final int HIST = 60;
    static final int OFF = 0, READY = 1, PC = 2, STREAMING = 3, ERROR = 4;

    static final class Stream {
        String name;
        boolean active;
        float fpsSent, fpsCap, mbps;
        int k = 1, fpsReq;
        long lost;                              // frames dropped from the capture queue since the last sample
        String peer, profile;
        final float[] hist = new float[HIST];   // sent fps (Hz for the IMU), oldest first
    }

    /** " · ⅔ in 4 s" style suffix: the next tier up and when the bridge will try it. */
    static String recoverLabel(long ms, int k) {
        if (ms < 0 || k <= 1) return "";
        String next = k - 1 <= 1 ? "full" : "½";
        return ms <= 0 ? " · trying " + next : " · " + next + " in " + (ms + 999) / 1000 + " s";
    }

    static final class Snap {
        boolean running;
        int hero = OFF;
        String heroText = "Bridge off", heroSub = "Tap Start";
        final Stream[] streams = new Stream[4];          // IR L, IR R, Color, IMU
        float totalMbps;
        long totalBytes;         // everything sent to PCs since the bridge started (video + IMU)
        long irRecoverMs = -1;   // countdown to the next IR rate step-up, -1 = at full rate
        final float[] totalHist = new float[HIST];
        int linkScore = -1;
        String linkWord = "No stream yet", linkDetail = "Estimated from delivery once streaming starts";
        double linkDropsPerSec;         // capture-queue drops per second over the last 10 s
        boolean staConnected;
        String staSsid;
        String modelName, modelStatus;      // scanners.json entry of the attached scanner
        int staFreq, staRssi, staLink, staBars;
        int staTx, staRx, staStd;       // Tx/Rx link rate (Mbit/s, -1 unknown), Wi-Fi generation (7 = 802.11be, 0 unknown)
        boolean p2pWanted;
        String p2pState, p2pSsid, p2pPass;
        int p2pFreq, p2pClients;
        List<String> p2pIps = new ArrayList<>();
        String advertise = "";          // network discovery is answered on now
        String advertiseSaved = "";     // the user's choice ("" = automatic)
        String fpsMode = FpsChoice.STOCK;  // scanner frame rate: stock | boost | max
        FpsChoice fpsChoice;            // the attached scanner's rates, null while no scanner is open
        List<FpsChoice> fpsOthers = new ArrayList<>();  // its other IR sizes with rates of their own (Raptor Infrared)
        boolean advertiseDown;          // the network locked at Start has gone away
        String ethName, ethIp, lanIp;
        String ethNoIp;          // wired adapter with link up but no IPv4 (DHCP not done / failed)
        String soc = SOC;        // phone chip, for the device report
        boolean scanner;
        boolean plugged, plugProbed;
        String idleModelStatus;         // bridge off: the plugged-in scanner's scanners.json status (sampleScannerIdle)    // bridge off: a scanner is on USB / its descriptors were read (sampleScannerIdle)
        String scannerState, scannerModel, scannerSn, scannerFw, usbLink;
        boolean usbSuper;
        boolean powered;
        int battery = -1;
        String powerSource;
        boolean brownOut;
        List<String> events = new ArrayList<>();
        String notif = "Bridge starting";
        int pcs;
    }

    private final Bridge b;
    volatile Snap snap = new Snap();
    private Thread thread;
    private volatile boolean running;

    // history / previous counters
    private final Stream[] cur = new Stream[4];
    private final long[][] prev = new long[4][4];       // sentFrames, sentBytes, captured, drops (IMU: reports)
    private long prevT;
    private final float[] totalHist = new float[HIST];
    private final ArrayDeque<long[]> window = new ArrayDeque<>();   // per second {sent, captured, drops}
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final ArrayDeque<Object[]> timed = new ArrayDeque<>();   // {ms, text}, guarded by events
    private long savedAt;                                            // last crash-record save (elapsed ms)
    private volatile boolean eventSince;                             // an event since that save
    private static final SimpleDateFormat HMS = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // previous state for event detection
    private Boolean wasScanner, wasPc, wasStreaming, wasPowered;
    private int wasK = 1, wasP2pClients = -1, wasScore = -2, scoreHold;
    private boolean brownOut;
    private PowerManager power;         // thermal status (Android 10+)

    Dash(Bridge b) {
        this.b = b;
        String[] names = {"IR L", "IR R", "Color", "IMU"};
        for (int i = 0; i < 4; i++) {
            cur[i] = new Stream();
            cur[i].name = names[i];
        }
    }

    void event(String text) {
        long now = System.currentTimeMillis();
        synchronized (events) {
            events.addFirst(HMS.format(new Date(now)) + "  " + text);
            while (events.size() > 30) events.removeLast();
            timed.addFirst(new Object[]{now, text});
            while (timed.size() > CrashRecord.EVENTS) timed.removeLast();
        }
        eventSince = true;                  // the crash record saves at the end of this sample
        BLog.log("event", "text", text);
    }

    /** The latest events with their time, newest first, {Long ms, String text}: for the crash record. */
    List<Object[]> recentEvents() {
        synchronized (events) {
            return new ArrayList<>(timed);
        }
    }

    void start() {
        if (running) return;
        running = true;
        prevT = System.nanoTime();
        thread = new Thread(() -> {
            while (running) {
                try {
                    sample();
                } catch (Throwable t) {
                    BLog.log("dash_error", "err", String.valueOf(t));
                }
                Otter.sleep(1000);
            }
        }, "dash");
        thread.start();
    }

    void stop() {
        running = false;
        Snap s = new Snap();
        s.running = false;
        s.events = eventList();
        s.notif = "Bridge stopped";
        snap = s;
    }

    private List<String> eventList() {
        synchronized (events) {
            List<String> l = new ArrayList<>(events);
            return l.size() > 10 ? l.subList(0, 10) : l;
        }
    }

    private RtspServer server(int port) {
        for (RtspServer s : b.rtsp) if (s.port == port) return s;
        return null;
    }

    static String netName(String ip) {
        if (ip == null) return "?";
        try {
            Object[] ifc = Gvcp.ifaceFor(InetAddress.getByName(ip));
            String n = ifc == null ? "" : String.valueOf(ifc[0]);
            if (n.startsWith("p2p")) return "Wi-Fi Direct";
            if (n.startsWith("wlan")) return "Wi-Fi";
            if (n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis") || n.startsWith("en")) return "Ethernet";
            return n.isEmpty() ? "network" : n;
        } catch (Exception e) {
            return "network";
        }
    }

    /** Color of the Wi-Fi signal bars (dashboard and mirror viewer). */
    static int barsColor(int bars) {
        return bars >= 3 ? Widgets.GREEN : bars == 2 ? Widgets.AMBER : Widgets.RED;
    }

    private static int bars(int rssi) {
        if (rssi >= -55) return 4;
        if (rssi >= -62) return 3;
        if (rssi >= -70) return 2;
        if (rssi >= -78) return 1;
        return 0;
    }

    static int channel(int f) {
        return P2pGroup.channelOf(f);
    }

    /**
     * Link cells for the mirror viewer's stats panel, {label, value}, for the network the bridge streams on
     * ("wifi" | "p2p" | "eth"; "" = this phone's Wi-Fi, the bridge is off). Android has no link rate for Ethernet
     * (sysfs is closed to apps) and no per-client signal for a Wi-Fi Direct group owner.
     */
    static List<String[]> linkStats(Snap s, String net) {
        List<String[]> out = new ArrayList<>();
        if (("wifi".equals(net) || net.isEmpty()) && s.staConnected) {
            out.add(new String[]{"Wi-Fi signal", s.staRssi + " dBm", String.valueOf(s.staBars)});   // [2] = signal bars
            String up = s.staTx > 0 ? s.staTx + "" : s.staLink > 0 ? s.staLink + "" : "?";
            out.add(new String[]{"Link rate", s.staRx > 0 ? "↑" + up + " ↓" + s.staRx + " Mbit/s" : up + " Mbit/s"});
            out.add(new String[]{"Band", (s.staStd > 0 ? "Wi-Fi " + s.staStd + " · " : "") + band(s.staFreq) + " ch " + channel(s.staFreq)});
        } else if ("p2p".equals(net)) {
            out.add(new String[]{"Wi-Fi Direct", s.p2pFreq > 0 ? band(s.p2pFreq) + " ch " + channel(s.p2pFreq) : "starting"});
            out.add(new String[]{"Signal", "n/a (group owner)"});
        } else if ("eth".equals(net)) {
            out.add(new String[]{"Network", "Ethernet (wired)"});
        }
        return out;
    }

    static String band(int f) {
        return f <= 0 ? "?" : f < 3000 ? "2.4 GHz" : f < 5925 ? "5 GHz" : "6 GHz";
    }

    /**
     * Speed warnings about the phone's Wi-Fi (STA) link, each {text, network it matters for: "wifi" | "p2p"}.
     * Pure function of the snapshot, so the UI shows them with the bridge on or off.
     */
    static List<String[]> staWarnings(Snap s) {
        List<String[]> out = new ArrayList<>();
        if (!s.staConnected) return out;
        if (s.staFreq > 0 && s.staFreq < 3000)
            out.add(new String[]{s.staSsid + " is on 2.4 GHz: too slow for streaming. Use 5 GHz or Wi-Fi Direct.", "wifi"});
        if (s.staRssi < -67)
            out.add(new String[]{"Weak " + s.staSsid + " signal (" + s.staRssi + " dBm): may be too slow for smooth streaming.", "wifi"});
        if (s.p2pWanted && s.p2pFreq > 0 && s.p2pFreq == s.staFreq && s.staRssi < -67)
            out.add(new String[]{"Wi-Fi Direct shares channel " + channel(s.staFreq) + " with a weak " + s.staSsid + " link (" + s.staRssi
                    + " dBm): streaming may slow down. Disconnect the phone from " + s.staSsid + " for the best link.", "p2p"});
        return out;
    }

    /** Connected Wi-Fi (STA): SSID, band, signal. Also used by the UI while the bridge is off. */
    @SuppressWarnings("deprecation")
    /** SuperSpeed if a video endpoint takes 1024-byte packets or the device reports USB 3. */
    static boolean usbSuper(UsbDesc d) {
        int mps = 0;
        for (UsbDesc.Vs vs : d.vss.values()) mps = Math.max(mps, vs.maxPacket);
        return mps >= 1024 || d.bcdUsb >= 0x0300;
    }

    /** What sampleScannerIdle learned about the plugged-in scanner, kept per device so it's read once per plug-in. */
    private static final class Plug {
        String key, model, status, sn;
        boolean probed, usbSuper;
    }
    private static volatile Plug plug;

    /**
     * Bridge off: which scanner is plugged in, without starting the bridge. Android lists USB devices without
     * opening them, so the model (from its product ID) is always known. If USB access was already granted, the
     * descriptors are read once (open, read, close: no claim, no scanner protocol) for the serial and USB 3 vs 2.
     * The firmware version needs the scanner protocol, so it's the one saved the last time this serial was opened.
     */
    static void sampleScannerIdle(Context ctx, Snap s) {
        android.hardware.usb.UsbManager usb = (android.hardware.usb.UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbDevice d = null;
        if (usb != null) for (UsbDevice x : usb.getDeviceList().values()) if (Otter.isOrbbec(x)) { d = x; break; }
        if (d == null) {
            plug = null;
            return;
        }
        String key = d.getDeviceName() + "#" + d.getDeviceId();
        Plug p = plug;
        if (p == null || !p.key.equals(key)) {
            p = new Plug();
            p.key = key;
            ScannerModel m = ScannerModel.forPid(ScannerModel.loadDocument(ctx, ctx.getFilesDir()), d.getProductId());
            p.model = m.name;
            p.status = m.status;
            plug = p;
        }
        if (!p.probed && usb.hasPermission(d)) {
            android.hardware.usb.UsbDeviceConnection c = null;
            try {
                c = usb.openDevice(d);
                byte[] raw = c != null ? c.getRawDescriptors() : null;
                if (raw != null) {
                    p.usbSuper = usbSuper(UsbDesc.parse(raw));
                    String sn = null;
                    try { sn = d.getSerialNumber(); } catch (Exception ignored) { }
                    if (sn == null) sn = c.getSerial();
                    p.sn = sn;
                    p.probed = true;
                }
            } catch (Exception e) {
                BLog.log("usb_idle_probe_failed", "err", String.valueOf(e));
            } finally {
                if (c != null) c.close();
            }
        }
        s.plugged = true;
        s.plugProbed = p.probed;
        s.scannerModel = p.model;
        s.idleModelStatus = p.status;
        s.scannerSn = p.sn;
        s.usbSuper = p.usbSuper;
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        if (p.sn != null && !p.sn.isEmpty() && p.sn.equals(prefs.getString("sub_sn", null))) s.scannerFw = prefs.getString("sub_fw", null);
    }

    static void sampleWifi(Context ctx, Snap s) {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm.getConnectionInfo();
        if (wi != null && wi.getSupplicantState() == SupplicantState.COMPLETED && wi.getFrequency() > 0) {
            s.staConnected = true;
            String ssid = wi.getSSID();
            s.staSsid = ssid == null || ssid.contains("unknown ssid") || ssid.isEmpty() ? "Wi-Fi" : ssid.replace("\"", "");
            s.staFreq = wi.getFrequency();
            s.staRssi = wi.getRssi();
            s.staLink = wi.getLinkSpeed();
            s.staBars = bars(s.staRssi);
            s.staTx = android.os.Build.VERSION.SDK_INT >= 29 ? wi.getTxLinkSpeedMbps() : s.staLink;
            s.staRx = android.os.Build.VERSION.SDK_INT >= 29 ? wi.getRxLinkSpeedMbps() : -1;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                int std = wi.getWifiStandard();      // ScanResult.WIFI_STANDARD_*: 4 = 11n, 5 = 11ac, 6 = 11ax, 8 = 11be
                s.staStd = std == 8 ? 7 : std == 6 ? 6 : std == 5 ? 5 : std == 4 ? 4 : 0;
            }
        }

    }

    /** "Qualcomm SM8550" (Android 12+), "" before: USB behavior follows the chipset more than the brand. */
    static final String SOC = Build.VERSION.SDK_INT >= 31
            ? (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).replace(Build.UNKNOWN, "").trim() : "";

    /**
     * Wired (eth, usb, rndis) and Wi-Fi IPv4 addresses. No link speed: /sys/class/net is closed to apps since
     * Android 11 (checked on Android 17), and Android reports none for Ethernet otherwise.
     */
    static void sampleInterfaces(Snap s) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                String n = ni.getName();
                String ip = null;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address) ip = a.getHostAddress();
                boolean wired = n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis");
                if (ip == null) {
                    if (wired && s.ethIp == null) s.ethNoIp = n;
                    continue;
                }
                if (wired) { s.ethName = n; s.ethIp = ip; }
                if (n.startsWith("wlan")) s.lanIp = ip;
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private final int[] prevK = {1, 1, 1};

    private void sample() {
        long now = System.nanoTime();
        double dt = Math.max(0.2, (now - prevT) / 1e9);
        prevT = now;
        Snap s = new Snap();
        s.running = b.running;

        // ---- streams
        int[] ports = {8556, 8557, 8888};
        long wSent = 0, wCap = 0, wDrops = 0;
        int maxK = 1;
        boolean anyVideo = false;
        Set<String> peers = new LinkedHashSet<>();
        float total = 0;
        long bytesAll = 0;
        for (int i = 0; i < 3; i++) {
            Stream st = cur[i];
            RtspServer srv = server(ports[i]);
            if (srv == null) { st.active = false; push(st.hist, 0); continue; }
            RtspServer.StreamStat ss = srv.stat;
            long f = ss.sentFrames.get(), by = ss.sentBytes.get(), c = ss.captured.get(), d = ss.drops.get();
            bytesAll += by;
            long[] p = prev[i];
            st.fpsSent = (float) ((f - p[0]) / dt);
            st.mbps = (float) ((by - p[1]) * 8 / dt / 1e6);
            st.fpsCap = (float) ((c - p[2]) / dt);
            st.lost = d - p[3];
            st.active = ss.active > 0;
            st.k = st.active && i < 2 ? ss.k : 1;
            st.fpsReq = ss.fps;
            st.peer = ss.peer;
            st.profile = ss.profile;
            if (st.active) {
                anyVideo = true;
                if (ss.peer != null) peers.add(ss.peer);
                wSent += f - p[0];
                wCap += c - p[2];
                wDrops += d - p[3];
                maxK = Math.max(maxK, st.k);
            }
            p[0] = f; p[1] = by; p[2] = c; p[3] = d;
            push(st.hist, st.active ? st.fpsSent : 0);
            total += st.active ? st.mbps : 0;
        }
        Stream imu = cur[3];
        long reps = b.imu != null ? b.imu.reportCount() : 0;
        imu.fpsSent = (float) ((reps - prev[3][0]) / dt);
        imu.fpsCap = imu.fpsSent;
        imu.mbps = imu.fpsSent * 248 * 8 / 1e6f;
        imu.active = b.imu != null && b.imu.clientCount() > 0;
        prev[3][0] = reps;
        push(imu.hist, imu.fpsSent);
        total += imu.mbps;
        push(totalHist, total);
        s.totalMbps = total;
        s.irRecoverMs = b.irGate.recoverInMs();
        s.totalBytes = bytesAll + reps * 248L;
        // ---- sticky session stats (once a second; no allocation)
        SessionStats ss = SessionStats.cur;
        long dtMs = Math.round(dt * 1000);
        if (ss.bytesAtStart < 0) ss.bytesAtStart = s.totalBytes;
        ss.bytesNow = s.totalBytes;
        if (anyVideo) ss.streamMs += dtMs;
        if (total > ss.peakMbps) ss.peakMbps = total;
        for (int i = 0; i < 3; i++) {
            RtspServer srv = server(ports[i]);
            if (srv == null) continue;
            RtspServer.StreamStat st = srv.stat;
            long c = st.captured.get(), f = st.sentFrames.get(), d = st.drops.get(), k = st.skipped.get();
            if (!ss.baseSet) { ss.base[i * 4] = c; ss.base[i * 4 + 1] = f; ss.base[i * 4 + 2] = d; ss.base[i * 4 + 3] = k; }
            ss.captured[i] = c - ss.base[i * 4];
            ss.sent[i] = f - ss.base[i * 4 + 1];
            ss.qdrops[i] = d - ss.base[i * 4 + 2];
            ss.skipped[i] = k - ss.base[i * 4 + 3];
            Stream cs = cur[i];
            if (cs.active) {
                if (st.fps > 0) ss.reqFps[i] = st.fps;
                ss.streamingMsPer[i] += dtMs;
                if (cs.k > 1) ss.reducedMs[i] += dtMs;
                if (cs.k > 1 && prevK[i] <= 1) ss.decimations[i]++;
            }
            prevK[i] = cs.active ? cs.k : 1;
        }
        ss.baseSet = true;
        System.arraycopy(totalHist, 0, s.totalHist, 0, HIST);
        for (int i = 0; i < 4; i++) s.streams[i] = copy(cur[i]);

        // ---- link score, estimated from delivery (no per-client RSSI for a P2P GO without root)
        if (anyVideo) window.addLast(new long[]{wSent, wCap, wDrops});
        else window.clear();
        while (window.size() > 60) window.removeFirst();
        if (anyVideo && window.size() >= 3) {
            long sent = 0, capd = 0, drops = 0;
            int n = 0;
            int m = 0;
            for (java.util.Iterator<long[]> it = window.descendingIterator(); it.hasNext() && m < 10; m++) {
                long[] w = it.next();
                if (n < 5) {            // delivery ratio: last 5 s, so recovery shows quickly
                    sent += w[0];
                    capd += w[1];
                    n++;
                }
                drops += w[2];          // queue drops: last 10 s, the window for every drop rate shown
            }
            double r = capd > 0 ? (double) sent / capd : 1.0;
            double dps = (double) drops / Math.max(1, m);
            int score;
            if (maxK == 1) score = r >= 0.97 && dps <= 0.1 ? 4 : r >= 0.85 ? 3 : r >= 0.6 ? 2 : 1;   // Excellent: <= 1 drop in 10 s
            else if (maxK == 2) score = r >= 0.4 ? 2 : 1;
            else score = 1;
            s.linkScore = score;
            s.linkWord = new String[]{"No signal", "Poor", "Marginal", "Good", "Excellent"}[score];
            s.linkDropsPerSec = dps;
            s.linkDetail = String.format(Locale.US, "Estimated from delivery: %d%% of captured frames sent, %.1f drops/s (last 10 s), %s",
                    Math.round(r * 100), dps, maxK == 1 ? "full rate" : maxK == 2 ? "IR at half rate" : "IR at one-third rate");
        }

        sampleWifi(b.ctx, s);

        // ---- Wi-Fi Direct
        P2pGroup g = b.p2p;
        s.p2pWanted = b.p2pWanted();
        s.p2pState = g.state;
        s.p2pSsid = g.ssid;
        s.p2pPass = g.passphrase;
        s.p2pFreq = g.freq;
        s.p2pClients = g.clientCount;
        s.advertise = b.lockedAdvertise != null ? b.lockedAdvertise : b.effectiveAdvertise();
        s.advertiseSaved = b.savedAdvertise();
        s.fpsMode = b.fpsMode();
        s.fpsChoice = b.fpsChoice();
        s.fpsOthers = b.fpsChoiceOthers();
        if (anyVideo && "p2p".equals(s.advertise) && s.staConnected) {   // Wi-Fi Direct sharing the radio with the STA
            ss.p2pDualMs += dtMs;
            ss.p2pDualFreq = s.staFreq;
        }
        boolean[] up = Bridge.networksUp();
        s.advertiseDown = b.running && !"local".equals(s.advertise)    // "This phone only" needs no network
                && ("p2p".equals(s.advertise) ? !up[0] && !s.p2pWanted : "eth".equals(s.advertise) ? !up[1] : !up[2]);

        // peers seen recently (discovery, control, streams), and which of them are on the group
        HostServer host = b.host;
        // A PC counts as connected only while its control socket (8090) is open: Creality Scan closes it on exit,
        // so the state drops at once. The 15 s request window catches a PC that vanished without closing it
        // (Wi-Fi dropped, PC asleep); the app polls every ~3 s while connected.
        boolean pcRecent = host != null && host.clients.get() > 0
                && System.currentTimeMillis() - host.lastRequestMs < 15000;
        if (pcRecent && host.lastPeer != null) peers.add(host.lastPeer);
        List<String> recent = b.gvcp != null ? b.gvcp.recentPeers() : Collections.<String>emptyList();
        for (String ip : recent) if ("Wi-Fi Direct".equals(netName(ip))) s.p2pIps.add(ip);
        for (String ip : peers) if ("Wi-Fi Direct".equals(netName(ip)) && !s.p2pIps.contains(ip)) s.p2pIps.add(ip);
        s.pcs = peers.size();

        sampleInterfaces(s);

        // ---- warnings for the STA: staWarnings(s), computed by the UI so they show with the bridge off too

        // ---- scanner + power
        Otter o = b.otter;
        s.scanner = o != null && !o.isClosed();
        if (s.scanner) {
            ScannerModel m = o.model;
            if (m != null) { s.modelName = m.name; s.modelStatus = m.status; }
            s.scannerState = "Ready";
            s.scannerModel = o.versionName != null ? o.versionName : "CR-Scan Otter";
            s.scannerSn = o.usbSerial.isEmpty() ? o.versionSn : o.usbSerial;
            s.scannerFw = o.fwVersion;
            s.usbSuper = usbSuper(o.desc);
            s.usbLink = s.usbSuper ? "USB 3 SuperSpeed (5 Gbit/s)" : "USB 2 High-Speed (480 Mbit/s): color stalls, use a USB 3 cable/hub";
        } else {
            UsbDevice d = b.findDevice();
            s.scannerState = d == null ? "Not connected"
                    : ((android.hardware.usb.UsbManager) b.ctx.getSystemService(Context.USB_SERVICE)).hasPermission(d) ? "Connecting…" : "Attached, waiting for USB permission";
        }
        Intent bat = b.ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            s.powered = plugged != 0;
            s.powerSource = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "external power (USB-C PD / hub)"
                    : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB power"
                    : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless charger" : "battery";
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            s.battery = lvl >= 0 ? Math.round(lvl * 100f / sc) : -1;
            if (s.powered) {
                ss.poweredMs += dtMs;
                ss.charger = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "USB-C PD or hub"    // as powerSource above
                        : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "plain USB (slow charging)"
                        : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless charger" : "other charger";
            } else ss.batteryMs += dtMs;
            if (s.battery >= 0) {
                if (ss.batteryStart < 0) ss.batteryStart = s.battery;
                ss.batteryEnd = s.battery;
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            if (power == null) power = (PowerManager) b.ctx.getSystemService(Context.POWER_SERVICE);
            if (power != null) ss.thermalMax = Math.max(ss.thermalMax, power.getCurrentThermalStatus());
        }

        // ---- events from transitions
        boolean streaming = anyVideo;
        if (wasScanner != null && wasScanner != s.scanner) {
            if (s.scanner) {
                brownOut = false;
                SessionStats.cur.usbAttach.incrementAndGet();
                event("Scanner connected" + (s.scannerFw != null ? " (fw " + s.scannerFw + ")" : ""));
            } else {
                brownOut = !s.powered;
                SessionStats.cur.usbDetach.incrementAndGet();
                if (Boolean.TRUE.equals(wasStreaming)) SessionStats.cur.detachStreaming.incrementAndGet();
                if (brownOut) SessionStats.cur.brownouts.incrementAndGet();
                event(brownOut ? "Scanner unplugged while the phone was on battery (likely brown-out)" : "Scanner unplugged");
            }
        }
        if (wasPowered != null && wasPowered != s.powered)
            event(s.powered ? "External power connected" : "Phone on battery: scanner may disconnect");
        boolean pc = pcRecent || streaming;
        if (wasPc != null && wasPc != pc) {
            String who = host != null && host.lastPeer != null ? host.lastPeer : null;
            if ("local".equals(b.lockedAdvertise))       // This phone only: the client is the CrealityScan app
                event(pc ? "CrealityScan app connected" : "CrealityScan app disconnected");
            else
                event(pc ? "PC connected" + (who != null ? " (" + who + " via " + netName(who) + ")" : "") : "PC disconnected");
            if (!pc) SessionStats.cur.pcDrops.incrementAndGet();
        }
        if (wasStreaming != null && wasStreaming != streaming) event(streaming ? "Streaming started" : "Streaming stopped");
        if (streaming && maxK != wasK)
            event(maxK == 1 ? "IR back to full rate" : maxK == 2 ? "IR dropped to half rate (link saturated)" : "IR dropped to one-third rate (link saturated)");
        if (s.p2pWanted && wasP2pClients >= 0 && s.p2pClients != wasP2pClients) {
            if (s.p2pClients > wasP2pClients) SessionStats.cur.p2pJoins.incrementAndGet();
            else SessionStats.cur.p2pLeaves.incrementAndGet();
        }
        if (s.p2pWanted && wasP2pClients >= 0 && s.p2pClients != wasP2pClients)
            event(s.p2pClients > wasP2pClients ? "Device joined Wi-Fi Direct (" + s.p2pClients + " connected)" : "Device left Wi-Fi Direct (" + s.p2pClients + " connected)");
        if (s.linkScore != wasScore) {
            if (++scoreHold >= 5 && s.linkScore >= 0) {
                event("Link quality: " + s.linkWord);
                wasScore = s.linkScore;
                scoreHold = 0;
            } else if (s.linkScore < 0) {
                wasScore = s.linkScore;
                scoreHold = 0;
            }
        } else scoreHold = 0;
        // PC-connected time and the link it uses (Wi-Fi STA sampled; a Wi-Fi Direct GO has no per-client RSSI)
        SessionStats sl = SessionStats.cur;
        if (pc) {
            sl.pcMs += Math.round(dt * 1000);
            String net = !peers.isEmpty() ? netName(peers.iterator().next()) : "";
            if ("Wi-Fi Direct".equals(net)) sl.linkNet = "Wi-Fi Direct";
            else if (s.staConnected && ("Wi-Fi".equals(net) || net.isEmpty())) {
                sl.linkNet = s.staSsid;
                sl.rssiMin = sl.rssiN == 0 ? s.staRssi : Math.min(sl.rssiMin, s.staRssi);
                sl.linkMin = sl.rssiN == 0 ? s.staLink : Math.min(sl.linkMin, s.staLink);
                sl.rssiSum += s.staRssi;
                sl.rssiN++;
            } else if (!net.isEmpty()) sl.linkNet = net;
        }
        wasScanner = s.scanner;
        wasPowered = s.powered;
        wasPc = pc;
        wasStreaming = streaming;
        wasK = streaming ? maxK : 1;
        wasP2pClients = s.p2pWanted ? s.p2pClients : -1;
        s.brownOut = brownOut && !s.scanner;

        // ---- hero
        String via = null, who = null;
        if (!peers.isEmpty()) { who = peers.iterator().next(); via = netName(who); }
        if ("Wi-Fi".equals(via) && s.staConnected) via = s.staSsid;
        if ("Ethernet".equals(via) && s.ethName != null) via = "Ethernet (" + s.ethName + ")";
        boolean local = "local".equals(s.advertise);
        if (local && who != null) { who = "CrealityScan on this phone"; via = null; }
        if (!s.running) {
            s.hero = OFF; s.heroText = "Bridge off"; s.heroSub = "Tap Start";
        } else if (!s.scanner) {
            s.hero = ERROR;
            if (s.brownOut) {
                s.heroText = "Scanner lost power";
                s.heroSub = "It disconnected while the phone was on battery. Use a powered USB-C hub.";
            } else if ("Not connected".equals(s.scannerState)) {
                s.heroText = "Scanner not connected";
                s.heroSub = "Plug the scanner in through a powered USB 3 hub";
            } else {
                s.heroText = "Scanner needs permission";
                s.heroSub = "Accept the USB dialog in the headset/device (or tap warning below)";
            }
        } else if (streaming) {
            s.hero = STREAMING;
            s.heroText = "Streaming to " + (who != null ? who : "PC") + (via != null ? " via " + via : "");
            s.heroSub = "";            // per-stream fps/Mbit/s live in the Streams card
        } else if (pc) {
            s.hero = PC;
            s.heroText = local ? "CrealityScan app connected"
                    : "PC connected" + (who != null ? ": " + who : "") + (via != null ? " via " + via : "");
            s.heroSub = "Waiting for stream start";
        } else {
            s.hero = READY;
            s.heroText = "Scanner ready, waiting for PC";
            String adv = "p2p".equals(s.advertise) ? "Wi-Fi Direct" : "wifi".equals(s.advertise) ? (s.staConnected ? s.staSsid : "Wi-Fi")
                    : "eth".equals(s.advertise) ? "Ethernet" : "lan".equals(s.advertise) ? "Wi-Fi + Ethernet" : "all networks";
            s.heroSub = "Advertising on " + adv + ". Start Creality Scan on the PC.";
            if (local) {
                s.heroText = "Scanner ready, waiting for the CrealityScan app";
                s.heroSub = "Open CrealityScan on this phone. Nothing on the network can reach the bridge.";
            } else if ("p2p".equals(s.advertise) && !s.p2pWanted)
                s.heroSub = "Discovery is set to Wi-Fi Direct only, but Wi-Fi Direct is off: the PC cannot find the bridge.";
            else if ("p2p".equals(s.advertise) && s.p2pClients == 0) {
                s.heroText = "No PC on Wi-Fi Direct yet";
                s.heroSub = "On the PC, join " + (s.p2pSsid != null ? s.p2pSsid : "the Wi-Fi Direct network")
                        + " from the Wi-Fi menu, not from Creality Scan (password below).";
            } else if ("p2p".equals(s.advertise)) {
                s.heroText = s.p2pClients == 1 ? "PC joined Wi-Fi Direct" : s.p2pClients + " devices joined Wi-Fi Direct";
                s.heroSub = "Start Creality Scan on the PC.";
            }
            else if ("lan".equals(s.advertise) && !s.staConnected && s.ethIp == null)
                s.heroSub = "Discovery is set to Wi-Fi + Ethernet, but neither is connected.";
            else if ("wifi".equals(s.advertise) && !s.staConnected)
                s.heroSub = "Discovery is set to Wi-Fi only, but Wi-Fi is not connected.";
            else if ("eth".equals(s.advertise) && s.ethIp == null)
                s.heroSub = "Discovery is set to Ethernet only, but no wired adapter is up.";
        }

        if (s.advertiseDown && s.hero != STREAMING) {
            String w = "p2p".equals(s.advertise) ? "Wi-Fi Direct" : "eth".equals(s.advertise) ? "Ethernet" : "Wi-Fi";
            s.hero = ERROR;
            s.heroText = w + " connection lost";
            s.heroSub = "The PC can't reach the bridge. Reconnect " + w + ", or stop the bridge and pick another network.";
        }

        // ---- notification one-liner
        String net = local ? "this phone only" : via != null ? via : s.p2pWanted && s.p2pClients > 0 ? "Wi-Fi Direct" : s.ethIp != null ? "Ethernet"
                : s.staConnected ? s.staSsid : "no network";
        if (s.hero == STREAMING)
            s.notif = String.format(Locale.US, "Streaming %.0f/%.0f/%.0f fps · %s · %d PC", s.streams[0].fpsSent,
                    s.streams[1].fpsSent, s.streams[2].fpsSent, net, Math.max(1, s.pcs));
        else s.notif = s.heroText + (s.hero == READY ? " · " + net : "");

        s.events = eventList();
        snap = s;
        // what this run was doing, on disk, in case it crashes or Android kills it (read back by the next run)
        long el = android.os.SystemClock.elapsedRealtime(), since = el - savedAt;
        if (since >= CrashRecord.SAVE_EVERY_MS || (eventSince && since >= CrashRecord.SAVE_AFTER_EVENT_MS)) {
            savedAt = el;
            eventSince = false;
            CrashRecord.saveNow();
        }
    }

    private static void push(float[] h, float v) {
        System.arraycopy(h, 1, h, 0, h.length - 1);
        h[h.length - 1] = v;
    }

    private static Stream copy(Stream a) {
        Stream c = new Stream();
        c.name = a.name; c.active = a.active; c.fpsSent = a.fpsSent; c.fpsCap = a.fpsCap; c.mbps = a.mbps;
        c.k = a.k; c.lost = a.lost; c.fpsReq = a.fpsReq; c.peer = a.peer; c.profile = a.profile;
        System.arraycopy(a.hist, 0, c.hist, 0, HIST);
        return c;
    }
}

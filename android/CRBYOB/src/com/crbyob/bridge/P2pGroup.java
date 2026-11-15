package com.crbyob.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Optional Wi-Fi Direct group owner on 5 GHz, so the PC can join the phone's own network (as the
 * official bridge is its own AP). SSID/passphrase persist in files/p2p.json, so a saved Windows
 * profile keeps working. The bridge's sockets are bound to 0.0.0.0, so they serve the p2p
 * interface (GO address 192.168.49.1) and the LAN at the same time.
 */
final class P2pGroup {
    /** The old fixed default (shared by every phone); replaced by defaultSsid() unless the user picked a name. */
    static final String DEFAULT_SSID = "DIRECT-SB-CRBYOB";

    static String defaultSsid(Context ctx) {
        return DEFAULT_SSID + "-" + Bridge.idSuffix(ctx);
    }
    /** Android requires group names of the form "DIRECT-xy..."; the app keeps "DIRECT-SB-" and lets the user edit the rest. */
    static final String PREFIX = "DIRECT-SB-";

    /** Slug after PREFIX: 1-22 chars (SSID max 32 bytes), letters, digits, '-' and '_'. Null if valid, else the problem. */
    static String checkSlug(String slug) {
        if (slug == null || slug.isEmpty()) return "Name is empty";
        if (PREFIX.length() + slug.length() > 32) return "Name too long (max " + (32 - PREFIX.length()) + " characters after " + PREFIX + ")";
        if (!slug.matches("[A-Za-z0-9_-]+")) return "Use only letters, digits, - and _";
        return null;
    }

    /** WPA2 passphrase: 8-63 printable ASCII characters. Null if valid, else the problem. */
    static String checkPassphrase(String p) {
        if (p == null || p.length() < 8) return "Password needs at least 8 characters";
        if (p.length() > 63) return "Password can have at most 63 characters";
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c > 0x7E) return "Use printable ASCII characters only";
        }
        return null;
    }

    static String slugOf(String ssid) {
        return ssid != null && ssid.startsWith(PREFIX) ? ssid.substring(PREFIX.length()) : "CRBYOB";
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiP2pManager mgr;
    private WifiP2pManager.Channel ch;
    private volatile boolean wanted;
    private boolean receiverOn;
    volatile String ssid, passphrase, iface, goIp, clients = "", state = "off";
    volatile int freq, clientCount;

    interface Leave { void left(int remaining, String goIp); }
    /** Called (main thread) when the client count goes down or the group disappears. */
    volatile Leave onLeave;

    private void fireLeave(int prev) {
        Leave l = onLeave;
        if (l != null && clientCount < prev) {
            try { l.left(clientCount, goIp); } catch (Exception e) { BLog.log("p2p_leave_error", "err", String.valueOf(e)); }
        }
    }
    private int retries;
    private int cfgFreq;          // p2p.json "freq": 0 = auto (STA channel first), -1 = band only, else MHz
    private int tryFreq;          // frequency of the current attempt, 0 = band

    P2pGroup(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Default Wi-Fi Direct password: 8 random digits (the WPA2 minimum), easy to type on the PC. Anyone who wants a
     * stronger one sets it in "Edit Wi-Fi Direct settings" (8-63 characters).
     */
    static String defaultPassphrase() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append((char) ('0' + r.nextInt(10)));
        return sb.toString();
    }

    private void loadCredentials() {
        File f = new File(ctx.getFilesDir(), "p2p.json");
        try {
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int n = in.read(b);
                    JSONObject j = new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8));
                    ssid = j.optString("ssid", DEFAULT_SSID);
                    passphrase = j.optString("passphrase", null);
                    cfgFreq = j.optInt("freq", 0);
                }
            }
            // unique per phone; only the old shared default is migrated, a user-chosen name is kept
            if (ssid == null || !ssid.startsWith("DIRECT-") || ssid.length() < 9 || ssid.equals(DEFAULT_SSID)) ssid = defaultSsid(ctx);
            if (passphrase == null || passphrase.length() < 8 || passphrase.length() > 63) passphrase = defaultPassphrase();
            save();
        } catch (Exception e) {
            BLog.log("p2p_cred_error", "err", String.valueOf(e));
            if (ssid == null) ssid = defaultSsid(ctx);
        }
    }

    /** Capabilities + STA channel, logged and shown in the UI. */
    String capabilities() {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        StringBuilder sb = new StringBuilder();
        Object b5 = wm.is5GHzBandSupported();
        Object b6 = Build.VERSION.SDK_INT >= 30 ? (Object) wm.is6GHzBandSupported() : "n/a";
        Object loc = Build.VERSION.SDK_INT >= 31 ? (Object) wm.isStaConcurrencyForLocalOnlyConnectionsSupported() : "n/a";
        boolean p2p = ctx.getPackageManager().hasSystemFeature("android.hardware.wifi.direct");
        @SuppressWarnings("deprecation") WifiInfo wi = wm.getConnectionInfo();
        int staFreq = wi != null ? wi.getFrequency() : -1;
        sb.append("5GHz=").append(b5).append(" 6GHz=").append(b6).append(" staConcLocalOnly=").append(loc)
          .append(" p2p=").append(p2p).append(" staFreq=").append(staFreq);
        BLog.log("wifi_caps", "is5GHzBandSupported", b5, "is6GHzBandSupported", b6,
                "isStaConcurrencyForLocalOnlyConnectionsSupported", loc, "p2p_supported", p2p,
                "sta_freq", staFreq, "p2p_freq", freq > 0 ? (Object) freq : null,
                "same_channel", freq > 0 && staFreq > 0 ? (Object) (freq == staFreq) : null);
        return sb.toString();
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(i.getAction())) refreshInfo("connection_changed");
        }
    };

    @SuppressWarnings("deprecation")
    private int staFreq() {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm.getConnectionInfo();
        return wi != null ? wi.getFrequency() : -1;
    }

    synchronized void start() {
        if (wanted) return;
        wanted = true;
        retries = 0;
        loadCredentials();
        // Single-radio phones time-share (MCC) when the GO and the STA use different 5 GHz channels,
        // which collapses TCP throughput. So try the STA's own channel first (SCC), then the band.
        int sf = staFreq();
        tryFreq = cfgFreq > 0 ? cfgFreq : cfgFreq == 0 && sf >= 5000 && sf < 5900 ? sf : 0;
        if (Build.VERSION.SDK_INT < 29 && tryFreq == 0) tryFreq = 5180;   // no band API: pin ch 36, see pinChannel
        mgr = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mgr == null) {
            state = "unsupported";
            BLog.log("p2p_error", "err", "no WifiP2pManager");
            return;
        }
        ch = mgr.initialize(ctx, Looper.getMainLooper(), () -> {
            BLog.log("p2p_channel_lost");
            if (wanted) main.postDelayed(() -> { synchronized (P2pGroup.this) { wanted = false; } start(); }, 2000);
        });
        if (!receiverOn) {
            IntentFilter f = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            else ctx.registerReceiver(rx, f);
            receiverOn = true;
        }
        state = "creating";
        // remove any leftover group first (createGroup fails with BUSY otherwise)
        mgr.removeGroup(ch, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { main.postDelayed(P2pGroup.this::create, 500); }
            @Override public void onFailure(int r) { create(); }
        });
    }

    private void create() {
        if (!wanted) return;
        try {
            WifiP2pManager.ActionListener l = new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    BLog.log("p2p_group_created", "ssid", ssid, "requested", requested());
                    state = "up";
                    main.postDelayed(() -> refreshInfo("created"), 1000);
                }

                @Override
                public void onFailure(int reason) {
                    // 0 ERROR, 1 P2P_UNSUPPORTED, 2 BUSY
                    BLog.log("p2p_group_failed", "reason", reason, "retry", retries, "requested", requested());
                    state = "failed(" + reason + ")";
                    if (Build.VERSION.SDK_INT < 29 && tryFreq > 0 && cfgFreq <= 0) {
                        // pinned channel refused (regulatory, DFS, no MCC): STA ch -> 36 -> 149 -> unpinned (driver's pick, often 2.4 GHz)
                        tryFreq = tryFreq == 5745 ? 0 : tryFreq == 5180 ? 5745 : 5180;
                        main.postDelayed(P2pGroup.this::create, 1000);
                    } else if (tryFreq > 0 && cfgFreq <= 0) {      // driver refused the STA channel (e.g. DFS): use the band
                        tryFreq = 0;
                        main.postDelayed(P2pGroup.this::create, 1000);
                    } else if (wanted && retries++ < 5) main.postDelayed(P2pGroup.this::create, 3000);
                }
            };
            if (Build.VERSION.SDK_INT >= 29) {
                WifiP2pConfig.Builder b = new WifiP2pConfig.Builder()
                        .setNetworkName(ssid)
                        .setPassphrase(passphrase)
                        .enablePersistentMode(false);
                if (tryFreq > 0) b.setGroupOperatingFrequency(tryFreq);
                else b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ);
                WifiP2pConfig cfg = b.build();
                mgr.createGroup(ch, cfg, l);
            } else {
                pinChannel(tryFreq > 0 ? channelOf(tryFreq) : 0);   // queued ahead of CREATE_GROUP on the same channel
                mgr.createGroup(ch, l);   // API 26-28: random DIRECT-xx name/passphrase
            }
        } catch (SecurityException e) {
            state = "no permission";
            BLog.log("p2p_group_failed", "err", String.valueOf(e), "hint", "grant NEARBY_WIFI_DEVICES (33+) / location (<33)");
        }
    }

    private String requested() {
        if (tryFreq > 0) return tryFreq + " MHz";
        return Build.VERSION.SDK_INT >= 29 ? "5GHz band" : "unpinned";
    }

    /**
     * API 26-28 has no band/frequency in createGroup. The hidden setWifiP2pChannels (light greylist on Pie, no
     * permission check beyond CHANGE_WIFI_STATE) makes wpa_supplicant disallow every frequency except channel oc,
     * so the next group must start there or fail. 0 clears it; it otherwise lasts until Wi-Fi restarts.
     */
    private void pinChannel(int oc) {
        try {
            WifiP2pManager.class.getMethod("setWifiP2pChannels", WifiP2pManager.Channel.class, int.class, int.class,
                    WifiP2pManager.ActionListener.class).invoke(mgr, ch, 0, oc, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { BLog.log("p2p_pin", "oc", oc, "ok", true); }
                @Override public void onFailure(int r) { BLog.log("p2p_pin", "oc", oc, "ok", false, "reason", r); }
            });
        } catch (Exception e) {
            BLog.log("p2p_pin", "oc", oc, "err", String.valueOf(e));
        }
    }

    void refreshInfo(String why) {
        if (mgr == null || ch == null) return;
        try {
            mgr.requestGroupInfo(ch, g -> {
                if (g == null) {
                    if (wanted) state = "no group";
                    clients = "";
                    int prev = clientCount;
                    clientCount = 0;
                    fireLeave(prev);
                    return;
                }
                onGroup(g, why);
            });
        } catch (SecurityException e) {
            BLog.log("p2p_info_error", "err", String.valueOf(e));
        }
    }

    private void onGroup(WifiP2pGroup g, String why) {
        ssid = g.getNetworkName();
        if (g.getPassphrase() != null) passphrase = g.getPassphrase();
        iface = g.getInterface();
        freq = Build.VERSION.SDK_INT >= 29 ? g.getFrequency() : 0;
        goIp = ifaceIp(iface);
        StringBuilder cl = new StringBuilder();
        for (WifiP2pDevice d : g.getClientList()) {
            if (cl.length() > 0) cl.append(", ");
            cl.append(d.deviceName == null || d.deviceName.isEmpty() ? d.deviceAddress : d.deviceName + " " + d.deviceAddress);
        }
        clients = cl.toString();
        int prev = clientCount;
        clientCount = g.getClientList().size();
        state = "up";
        fireLeave(prev);
        BLog.log("p2p_group", "why", why, "ssid", ssid, "passphrase", passphrase, "freq", freq,
                "channel", channelOf(freq), "iface", iface, "go_ip", goIp, "is_go", g.isGroupOwner(),
                "clients", g.getClientList().size(), "client_list", clients);
        capabilities();
    }

    static int channelOf(int f) {
        if (f >= 2412 && f <= 2484) return f == 2484 ? 14 : (f - 2407) / 5;
        if (f >= 5000 && f <= 5900) return (f - 5000) / 5;
        if (f >= 5955 && f <= 7115) return (f - 5950) / 5;
        return -1;
    }

    static String ifaceIp(String name) {
        try {
            NetworkInterface ni = name == null ? null : NetworkInterface.getByName(name);
            if (ni != null) for (InetAddress a : Collections.list(ni.getInetAddresses()))
                if (a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) {
        }
        return null;
    }

    private void save() throws Exception {
        JSONObject j = new JSONObject();
        j.put("ssid", ssid);
        j.put("passphrase", passphrase);
        j.put("freq", cfgFreq);
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "p2p.json"))) {
            out.write(j.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** New name slug + passphrase from the UI: validate, persist to p2p.json, recreate the group if it is on. */
    String setCredentials(String slug, String pass) {
        String err = checkSlug(slug);
        if (err == null) err = checkPassphrase(pass);
        if (err != null) return err;
        boolean restart;
        synchronized (this) {
            if (ssid == null) loadCredentials();
            ssid = PREFIX + slug;
            passphrase = pass;
            try {
                save();
            } catch (Exception e) {
                return "Could not save: " + e.getMessage();
            }
            restart = wanted;
        }
        BLog.log("p2p_credentials_changed", "ssid", ssid, "restart", restart);
        if (restart) {
            stop();
            main.postDelayed(this::start, 1500);
        }
        return null;
    }

    /** Current name/passphrase even while the group is off (from p2p.json). */
    synchronized String[] credentials() {
        if (ssid == null || passphrase == null) loadCredentials();
        return new String[]{ssid, passphrase};
    }

    synchronized void stop() {
        wanted = false;
        state = "off";
        if (receiverOn) {
            try { ctx.unregisterReceiver(rx); } catch (Exception ignored) { }
            receiverOn = false;
        }
        if (mgr != null && ch != null) {
            if (Build.VERSION.SDK_INT < 29) pinChannel(0);   // don't leave other Wi-Fi Direct use stuck on one channel
            mgr.removeGroup(ch, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { BLog.log("p2p_group_removed"); }
                @Override public void onFailure(int r) { BLog.log("p2p_group_remove_failed", "reason", r); }
            });
            if (Build.VERSION.SDK_INT >= 27) ch.close();
        }
        ch = null;
    }

    String statusLine() {
        if (!wanted) return "off";
        return state + " ssid=" + ssid + " freq=" + freq + " (ch " + channelOf(freq) + ")"
                + " ip=" + goIp + " if=" + iface + " clients=[" + clients + "]";
    }
}

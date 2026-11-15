package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The pre-filled Device report: its URL, its privacy rules, and that it matches the GitHub form. */
public class DeviceReportTest {
    private static final String REPO = "https://github.com/NickLD/CR-BYOB";

    @Test public void titleWithScanner_fillsThePlaceholderButNotARewrittenTitle() {
        String t = DeviceReport.titleWithScanner("[Device] <scanner> on Google Pixel 9", "<scanner>", "CR-Scan Otter");
        assertEquals("[Device] CR-Scan Otter on Google Pixel 9", t);
        // picking again swaps the last pick; "Other" puts the placeholder back
        assertEquals("[Device] CR-Scan Raptor on Google Pixel 9",
                DeviceReport.titleWithScanner(t, "CR-Scan Otter", "CR-Scan Raptor"));
        assertEquals("[Device] <scanner> on Google Pixel 9", DeviceReport.titleWithScanner(t, "CR-Scan Otter", "<scanner>"));
        assertEquals(null, DeviceReport.titleWithScanner("My Otter keeps dropping", "<scanner>", "CR-Scan Otter"));
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File("../..", rel).toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String url) throws Exception {
        Map<String, String> q = new HashMap<>();
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            int e = kv.indexOf('=');
            q.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
        }
        return q;
    }

    private static Dash.Snap otterOnWifi() {
        Dash.Snap s = new Dash.Snap();
        s.scanner = true;
        s.modelName = "CR-Scan Otter";
        s.scannerModel = "CR-Scan Otter";
        s.scannerSn = "BCA000000X1";
        s.scannerFw = "1.2.2";
        s.usbSuper = true;
        s.fpsMode = "max";
        s.advertise = "wifi";
        s.staConnected = true;
        s.staSsid = "MyHomeNetwork";
        s.staStd = 7;
        s.staFreq = 5260;
        s.staRssi = -44;
        s.staTx = 1921;
        s.linkScore = 4;
        s.linkWord = "Excellent";
        return s;
    }

    @Test
    public void fillsTheFormFromTheSession() throws Exception {
        SessionStats ss = new SessionStats();
        ss.streamMs = 711_000;
        ss.streamingMsPer[0] = 711_000;
        ss.sent[0] = 21_330;
        ss.captured[0] = 21_500;
        DeviceReport.Fields f = DeviceReport.collect(otterOnWifi(), ss, "CR-BYOB 0.2.1 (1, github, abc) · Google Pixel 6 · Android 17 (API 37)", "Google Pixel 6");
        Map<String, String> q = query(DeviceReport.url(REPO, f, false, ""));
        assertEquals("device-report.yml", q.get("template"));
        assertEquals("[Device] CR-Scan Otter on Google Pixel 6", q.get("title"));
        assertEquals("CR-Scan Otter", q.get("scanner"));
        assertEquals("1.2.2", q.get("scanner_fw"));
        assertEquals("Wi-Fi", q.get("network"));
        String d = q.get("details");
        assertTrue(d, d.contains("IR L: 30.0 fps, 99% of captured frames sent"));
        assertTrue(d, d.contains("Frame-rate setting: Max"));
        assertTrue(d, d.contains("Wi-Fi 7, 5 GHz, -44 dBm, 1921 Mbit/s link rate"));
        assertFalse("spaces must be %20, not +", DeviceReport.url(REPO, f, false, "").contains("+"));
        assertFalse("the web form doesn't read the body", q.containsKey("body"));

        // the GitHub app ignores form fields: the same report as body text, in GitHub's submitted-form layout
        Map<String, String> app = query(DeviceReport.url(REPO, f, true, ""));
        assertEquals("[Device] CR-Scan Otter on Google Pixel 6", app.get("title"));
        assertFalse("the GitHub app doesn't read the fields", app.containsKey("details") || app.containsKey("scanner"));
        String body = app.get("body");
        assertTrue(body, body.startsWith("### Scanner\n\nCR-Scan Otter\n\n### Scanner firmware\n\n1.2.2"));
        assertTrue(body, body.contains("### Result\n\n_No response_"));
        assertTrue(body, body.contains("### What happened\n\nMeasured by CR-BYOB"));
        assertTrue(body, body.endsWith("### Log\n\n_No response_"));
    }

    /** A long log for the digest: scanner setup lines plus 300 lines of mixed traffic, with private values. */
    static List<String> pinnedLines() {
        StringBuilder modes = new StringBuilder();
        for (String fmt : new String[]{"GREY", "MJPG", "Y10 "})
            for (String res : new String[]{"320x200", "640x400", "800x600", "1280x800"})
                for (int fps : new int[]{5, 10, 15, 20, 30}) modes.append(fmt.trim()).append(' ').append(res).append('@').append(fps).append(" (fmt 3 frm 1); ");
        List<String> p = new ArrayList<>();
        p.add("06:10:00 kind=usb_open dev=/dev/bus/usb/002/010 vid=2bc5 pid=06a8 serial=BCA674105K2 desc_bytes=2453 summary=2bc5:06a8 | VC0 uvc=110 vs=[1]");
        for (int i : new int[]{1, 3, 5}) p.add("06:10:00 kind=usb_vs_modes iface=" + i + " ep=8" + i + " modes=" + modes);
        p.add("06:10:01 kind=scanner_model pid=0x06A8 model_pid=0x06A8 name=CR-Scan Raptor status=unverified registry=asset:scanners.json notes=PioneerDevice uses the default port bytes = Otter layout [O client].");
        p.add("06:10:01 kind=scanner_roles roles=[ir@VC2:8556, ir_l@VC2:8556, ir_r@VC0:8557, color@VC4:8888] source={ir=table} interfaces={0=Depth, 2=IR, 4=RGB} vs_per_vc={0=1, 2=1, 4=1}");
        p.add("06:10:01 kind=scanner_ready pid=1704 fw=1.3.8 serial=BCA674105K2");
        return p;
    }

    static List<String> recentLines() {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String t = String.format(java.util.Locale.US, "06:%02d:%02d ", 11 + i / 60, i % 60);
            if (i % 3 == 0) r.add(t + "kind=hp_req peer=192.168.1.20:50112 opcode=17 req_id=" + i);
            else if (i % 3 == 1) r.add(t + "kind=uvc_stats node=VC2 fps=0.0 frames=0 bytes=0");
            else r.add(t + "kind=rtsp_req port=8556 method=DESCRIBE url=rtsp://192.168.1.7:8556/ir transport=null n=" + i);
        }
        return r;
    }

    /**
     * With the log included: the web form's link carries a digest and still fits GitHub's limit; the GitHub app
     * (long links, no attachments) gets the readable log in the body, within GitHub's body limit.
     */
    @Test
    public void eachAppGetsTheLogItCanTake() throws Exception {
        DeviceReport.Fields f = DeviceReport.collect(otterOnWifi(), troubledSession(), "CR-BYOB 0.2.4 (1, github, abc) · Google Pixel 8 Pro · Android 17 (API 37)", "Google Pixel 8 Pro");
        f.result = DeviceReport.RESULTS[2];
        f.details = f.details + "Black camera views in both modes, IMU works.";
        String note = DeviceReport.fullLogNote("crbyob-log-20261109-0612.zip", "Downloads");

        String web = DeviceReport.reportUrl(REPO, f, false, note, pinnedLines(), recentLines());
        assertTrue(web.length() + "", web.length() <= DeviceReport.MAX_URL);
        String digest = query(web).get("logs");
        assertTrue(digest, digest.startsWith("Log digest. Full log: crbyob-log-20261109-0612.zip in Downloads, attach it here"));
        assertTrue(digest, digest.contains("scanner_roles roles=[ir@VC2:8556"));
        assertTrue(digest, digest.contains("rtsp_req port=8556 method=DESCRIBE"));
        assertFalse(digest, digest.contains("192.168.") || digest.contains("BCA674105K2"));

        String body = query(DeviceReport.reportUrl(REPO, f, true, note, pinnedLines(), recentLines())).get("body");
        assertTrue(body.length() + "", body.length() <= DeviceReport.MAX_BODY);
        String log = body.substring(body.indexOf("### Log"));
        assertTrue(log, log.startsWith("### Log\n\n```text\nCR-BYOB log (serial numbers"));
        assertTrue(log, log.contains("-- Scanner setup (latest) --"));
        assertTrue("every line, noise included", log.contains("-- Last 300 lines --") && log.contains("hp_req"));
        assertTrue(log, log.endsWith("n=299\n```"));
        assertFalse(log, log.contains("192.168.") || log.contains("BCA674105K2"));

        f.includeLog = false;
        assertFalse(query(DeviceReport.reportUrl(REPO, f, false, note, pinnedLines(), recentLines())).containsKey("logs"));
        assertTrue(query(DeviceReport.reportUrl(REPO, f, true, note, pinnedLines(), recentLines())).get("body")
                .endsWith("### Log\n\n_No response_"));
    }

    /** Sent anonymously with the whole log going privately: the public body keeps only a short tail and says so. */
    @Test
    public void anonymousWithThePrivateLog_keepsAShortTail() throws Exception {
        DeviceReport.Fields f = DeviceReport.collect(otterOnWifi(), troubledSession(), "CR-BYOB 0.2.4 (1, github, abc) · Google Pixel 8 Pro · Android 17 (API 37)", "Google Pixel 8 Pro");
        String shortLog = DeviceReport.anonLog(f, true, pinnedLines(), recentLines());
        assertTrue(shortLog, shortLog.contains("whole log was sent privately"));
        assertTrue(shortLog, shortLog.contains("-- Last " + DeviceReport.SHORT_TAIL_LINES + " lines --"));
        assertTrue(shortLog, shortLog.contains("-- Scanner setup (latest) --"));
        assertFalse(shortLog, shortLog.contains("192.168.") || shortLog.contains("BCA674105K2"));

        assertEquals(DeviceReport.logFor(REPO, f, true, "", pinnedLines(), recentLines()),
                DeviceReport.anonLog(f, false, pinnedLines(), recentLines()));   // no private log: today's long tail
        f.includeLog = false;
        assertEquals("", DeviceReport.anonLog(f, true, pinnedLines(), recentLines()));
    }

    /** A session with every diagnostic set: power, disconnects by phase, reduced rate, errors, latency, heat, chip. */
    private static SessionStats troubledSession() {
        SessionStats ss = new SessionStats();
        ss.streamMs = 100_000;
        ss.streamingMsPer[0] = 100_000;
        ss.sent[0] = 2_500;
        ss.captured[0] = 2_600;
        ss.qdrops[0] = 60;
        ss.skipped[0] = 40;
        ss.reducedMs[0] = 12_000;
        ss.poweredMs = 65_000;
        ss.batteryMs = 35_000;
        ss.usbDetach.set(7);
        ss.usbAttach.set(7);
        ss.detachStreaming.set(2);
        ss.brownouts.set(7);
        ss.pcDrops.set(1);
        ss.rtspErrors.set(2);
        ss.stalledClosed.set(1);
        ss.uvcErrors.set(3);
        ss.mirrorConnects.set(2);
        for (int i = 0; i < 100; i++) ss.frameAge.add(10_000 + i * 200);   // 10..29.8 ms
        for (int i = 0; i < 100; i++) ss.xu.add(3_000);
        ss.thermalMax = 2;
        return ss;
    }

    @Test
    public void reportsTheDiagnostics() {
        Dash.Snap s = otterOnWifi();
        s.soc = "Qualcomm SM8550";
        String d = DeviceReport.collect(s, troubledSession(), "x", "Phone").details;
        assertTrue(d, d.contains("- IR L: 25.0 fps, 96% of captured frames sent, 100 dropped, at reduced rate 12% of the time\n"));
        assertTrue(d, d.contains("- Phone power: on battery 35% of the session\n"));   // no charger seen: nothing to name
        assertTrue(d, d.contains("- Scanner disconnects: 2 while streaming, 5 while not streaming (7 while the phone was on battery)\n"));
        assertTrue(d, d.contains("- Also counted: 1 PC disconnect, 2 stream errors, 1 stalled stream closed, 3 USB frame errors\n"));
        assertTrue(d, d.contains("- Latency: frame age avg 19.9 ms, p95 29 ms; scanner replies p95 4 ms\n"));
        assertTrue(d, d.contains("- Mirror viewer: used (2 connections)\n"));
        assertTrue(d, d.contains("- Phone heat: moderate at worst (Android's thermal status)\n"));
        assertTrue(d, d.contains("- Phone chip: Qualcomm SM8550\n"));
    }

    @Test
    public void aQuietSessionStaysShort() {
        SessionStats ss = new SessionStats();
        ss.streamMs = 60_000;
        ss.streamingMsPer[0] = 60_000;
        ss.sent[0] = 1800;
        ss.captured[0] = 1800;
        ss.poweredMs = 60_000;
        String d = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details;
        assertTrue(d, d.contains("- IR L: 30.0 fps, 100% of captured frames sent\n"));
        ss.thermalMax = 0;
        assertTrue(DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details.contains("- Phone heat: normal\n"));
        ss.thermalMax = -1;                       // unknown (Android 9 and older): no line
        assertTrue(d, d.contains("- Phone power: external the whole session\n"));
        assertTrue(d, d.contains("- Scanner disconnects: none\n"));
        assertTrue(d, d.contains("- Mirror viewer: not used\n"));
        for (String absent : new String[]{"Also counted", "Latency", "Phone heat", "Phone chip", "dropped", "reduced rate"})
            assertFalse(absent + " in\n" + d, d.contains(absent));

        ss.poweredMs = 0;
        ss.batteryMs = 60_000;
        assertTrue(DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details.contains("- Phone power: battery the whole session\n"));
    }

    @Test
    public void reportsTargetRateBatteryChargerAndWorstLink() {
        SessionStats ss = troubledSession();
        ss.reqFps[0] = 30;
        ss.batteryStart = 80;
        ss.batteryEnd = 72;
        ss.charger = "USB-C PD or hub";
        ss.rssiSum = -50 * 10;
        ss.rssiN = 10;
        ss.rssiMin = -61;
        ss.linkMin = 433;
        String d = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details;
        assertTrue(d, d.contains("- IR L: 25.0 of 30 fps, 96% of captured frames sent"));
        assertTrue(d, d.contains("- Phone power: on battery 35% of the session, otherwise USB-C PD or hub\n"));
        assertTrue(d, d.contains("- Phone battery: 80% at the start, 72% at the end\n"));
        assertTrue(d, d.contains("- Wi-Fi signal during the session: avg -50 dBm, worst -61 dBm, lowest link rate 433 Mbit/s\n"));
        ss.batteryMs = 0;
        assertTrue(DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details
                .contains("- Phone power: external the whole session (USB-C PD or hub)\n"));
    }

    @Test
    public void wifiDirectSaysWhetherThePhoneSharedTheRadio() {
        Dash.Snap s = otterOnWifi();
        s.advertise = "p2p";
        s.p2pFreq = 5180;
        SessionStats ss = troubledSession();
        ss.p2pDualMs = 40_000;
        ss.p2pDualFreq = 2437;
        String d = DeviceReport.collect(s, ss, "x", "Phone").details;
        assertTrue(d, d.contains("- Phone also on a Wi-Fi network: 40% of the streaming time (2.4 GHz), sharing the radio with Wi-Fi Direct\n"));
        assertFalse("no network name", d.contains("MyHomeNetwork"));
        ss.p2pDualMs = 0;
        assertTrue(DeviceReport.collect(s, ss, "x", "Phone").details.contains("- Phone also on a Wi-Fi network: no\n"));
        s.advertise = "wifi";                                     // only asked about Wi-Fi Direct
        assertFalse(DeviceReport.collect(s, ss, "x", "Phone").details.contains("Phone also on a Wi-Fi network"));
    }

    @Test
    public void appHealthShowsBatteryOptimizationAndTheLastExit() {
        DeviceReport.AppHealth h = new DeviceReport.AppHealth();
        h.unrestricted = false;
        h.lastExit = DeviceReport.exitLine(3, 12 * 60_000L);
        String d = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone", h).details;
        assertTrue(d, d.contains("- Battery optimization: on for CR-BYOB (Android may stop it in the background)\n"));
        assertTrue(d, d.contains("- App crashes and kills: killed by Android (low memory), 12 min ago\n"));
        h.unrestricted = true;
        h.lastExit = null;
        d = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone", h).details;
        assertTrue(d, d.contains("- Battery optimization: off for CR-BYOB (unrestricted)\n"));
        assertFalse(d, d.contains("App crashes and kills"));
        assertEquals("crashed, 3 h ago", DeviceReport.exitLine(4, 3 * 3_600_000L));
        assertEquals("stopped responding (ANR), just now", DeviceReport.exitLine(6, 20_000L));
        assertEquals("closed for an app update, 2 d ago", DeviceReport.exitLine(16, 50 * 3_600_000L));
        assertEquals("other (reason 99), 1 min ago", DeviceReport.exitLine(99, 60_000L));
    }

    private static final int FG = 100, FG_SERVICE = 125, CACHED = 400;   // RunningAppProcessInfo.IMPORTANCE_*

    /** A crash shouldn't hide behind a later normal close (reopened, then swiped away). Newest first. */
    @Test
    public void lastExitPicksTheLatestProblemOfTheDay() {
        long min = 60_000L, h = 60 * min;
        // swiped away 5 min ago, crashed 40 min ago, low memory 3 h ago
        assertEquals("crashed, 40 min ago", DeviceReport.lastProblemExit(
                new int[]{10, 4, 3}, new int[]{FG, FG, FG_SERVICE}, new long[]{5 * min, 40 * min, 3 * h}));
        // only normal closes today, a crash two days ago: nothing to blame
        assertEquals("no crash or kill by Android in the last 24 h", DeviceReport.lastProblemExit(
                new int[]{1, 10, 16, 4}, new int[]{FG, FG, CACHED, FG}, new long[]{min, 2 * h, 5 * h, 48 * h}));
        assertEquals(null, DeviceReport.lastProblemExit(new int[0], new int[0], new long[0]));   // no history at all
    }

    /** Android clearing a cached background app is routine; killing it while the bridge ran isn't. Crashes always count. */
    @Test
    public void killsCountOnlyWhileTheAppWasInUse() {
        assertFalse("low memory, cached", DeviceReport.isProblem(3, CACHED));
        assertTrue("low memory, bridge running", DeviceReport.isProblem(3, FG_SERVICE));
        assertTrue("crash, even in the background", DeviceReport.isProblem(4, CACHED));
        assertTrue("ANR", DeviceReport.isProblem(6, CACHED));
        assertFalse("swiped away", DeviceReport.isProblem(10, FG));
        assertFalse("app update", DeviceReport.isProblem(16, FG_SERVICE));
        long min = 60_000L;
        assertEquals(1, DeviceReport.newestProblem(new int[]{3, 13, 4}, new int[]{CACHED, FG_SERVICE, FG}, new long[]{min, 2 * min, 3 * min}));
        assertEquals(-1, DeviceReport.newestProblem(new int[]{3, 4}, new int[]{CACHED, FG}, new long[]{min, 25 * 60 * min}));
    }

    /** This phone only: the phone's own Wi-Fi and any Ethernet adapter play no part, so neither shows up. */
    @Test
    public void thisPhoneOnlyLeavesOutNetworkLinks() {
        Dash.Snap s = otterOnWifi();                  // still connected to home Wi-Fi
        s.advertise = "local";
        SessionStats ss = troubledSession();
        ss.rssiSum = -50 * 10;
        ss.rssiN = 10;
        ss.rssiMin = -61;
        ss.linkMin = 433;
        String d = DeviceReport.collect(s, ss, "x", "Phone").details;
        assertTrue(d, d.contains("- Connection: This phone only (CrealityScan mobile app)\n"));
        for (String absent : new String[]{"dBm", "link rate", "Phone also on a Wi-Fi network"})
            assertFalse(absent + " in\n" + d, d.contains(absent));
    }

    @Test
    public void neverLeaksSerialSsidOrAddresses() {
        Dash.Snap s = otterOnWifi();
        s.lanIp = "192.168.10.116";
        DeviceReport.Fields f = DeviceReport.collect(s, new SessionStats(), "x", "Phone");
        String all = f.title + f.scanner + f.scannerFw + f.appInfo + f.network + f.details;
        assertFalse(all.contains("BCA000000X1"));
        assertFalse(all.contains("MyHomeNetwork"));
        assertFalse(all.contains("192.168."));
    }

    @Test
    public void noScannerLeavesTheDropdownForThePerson() throws Exception {
        DeviceReport.Fields f = DeviceReport.collect(new Dash.Snap(), null, "x", "Phone");
        assertEquals("", f.scanner);
        assertFalse(f.scannerDetected);
        assertFalse(query(DeviceReport.url(REPO, f, false, "")).containsKey("scanner"));
        assertTrue(f.title.contains("<scanner>"));
    }

    @Test
    public void asksForAScanWhenThereAreNoStats() {
        java.text.DateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        hm.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        DeviceReport.Fields none = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone");
        assertFalse("no result picked yet", DeviceReport.needsScanFirst(none));
        none.result = DeviceReport.RESULTS[0];
        assertTrue(DeviceReport.needsScanFirst(none));
        none.result = DeviceReport.RESULTS[1];
        assertTrue(DeviceReport.needsScanFirst(none));
        assertTrue(DeviceReport.statsSource(none, hm).startsWith("No streaming in this bridge session yet"));
        none.result = DeviceReport.RESULTS[2];                     // "Doesn't work": a failed setup may not stream
        assertFalse(DeviceReport.needsScanFirst(none));

        SessionStats ss = new SessionStats();
        ss.streamMs = 711_000;
        ss.stopMs = ss.startMs + 900_000;
        DeviceReport.Fields streamed = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone");
        streamed.result = DeviceReport.RESULTS[0];
        assertFalse(DeviceReport.needsScanFirst(streamed));
        String src = DeviceReport.statsSource(streamed, hm);
        assertTrue(src, src.startsWith("Measurements are from this bridge session: 11m 51s of streaming, started "));
        assertTrue(src, src.contains(", stopped "));
    }

    /** "Scan first" keeps what the person entered; the next report has fresh measurements plus their entries. */
    @Test
    public void aDraftCarriesTheirEntriesIntoTheNextReport() {
        DeviceReport.Fields before = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone");
        before.client = "Creality Scan 4.3.3 (Windows)";
        before.result = DeviceReport.RESULTS[0];
        before.details = before.details + "Scanned a mug, tracking held.";

        SessionStats ss = new SessionStats();
        ss.streamMs = 60_000;
        ss.streamingMsPer[0] = 60_000;
        ss.sent[0] = 1800;
        ss.captured[0] = 1800;
        DeviceReport.Fields after = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone");
        before.includeLog = false;
        DeviceReport.restore(after, before);
        assertFalse("their choice about the log carries over too", after.includeLog);
        assertEquals("Creality Scan 4.3.3 (Windows)", after.client);
        assertEquals(DeviceReport.RESULTS[0], after.result);
        assertTrue(after.details, after.details.contains("IR L: 30.0 fps"));                 // fresh measurements
        assertFalse(after.details, after.details.contains("no streaming in this session"));  // not the old ones
        assertTrue(after.details, after.details.trim().endsWith("Scanned a mug, tracking held."));
        assertEquals("Scanned a mug, tracking held.", DeviceReport.notesOf(after.details));
        assertFalse(DeviceReport.needsScanFirst(after));
    }

    @Test
    public void aDetectedScannerIsFixed() {
        DeviceReport.Fields f = DeviceReport.collect(otterOnWifi(), null, "x", "Phone");
        assertEquals("CR-Scan Otter", f.scanner);
        assertTrue(f.scannerDetected);
    }

    @Test
    public void anUnlistedScannerIsOtherWithItsUsbId() {
        Dash.Snap s = otterOnWifi();
        s.modelName = "PID 0x0C42";                   // ScannerModel's name for a product ID not in scanners.json
        DeviceReport.Fields f = DeviceReport.collect(s, null, "x", "Phone");
        assertEquals(DeviceReport.OTHER, f.scanner);
        assertFalse("pick it, or say which in the details", f.scannerDetected);
        assertTrue(f.details, f.details.contains("Scanner USB ID: 0x0C42"));
    }

    /** The ids and options the app fills in exist in the form, or GitHub silently drops them. */
    @Test
    public void matchesTheGitHubForm() throws Exception {
        String yml = read(".github/ISSUE_TEMPLATE/" + DeviceReport.TEMPLATE);
        for (String id : new String[]{"scanner", "scanner_fw", "app_info", "network", "client", "result", "details", "logs"})
            assertTrue("field id " + id, yml.contains("id: " + id + "\n") || yml.contains("id: " + id + "\r\n"));
        List<String> scannerOpts = options(yml, "scanner"), networkOpts = options(yml, "network");
        assertTrue(scannerOpts.contains(DeviceReport.OTHER));
        assertEquals(java.util.Arrays.asList(DeviceReport.NETWORKS), networkOpts);
        assertEquals(java.util.Arrays.asList(DeviceReport.RESULTS), options(yml, "result"));
        for (String a : new String[]{"p2p", "wifi", "eth", "local"})
            assertTrue(a, networkOpts.contains(DeviceReport.networkOption(a)));
        // the form lists exactly the scanners a bridge can serve, like the app's picker (order may differ)
        JSONObject models = new JSONObject(read("models/scanners.json")).getJSONObject("models");
        assertEquals(new java.util.HashSet<>(DeviceReport.scannerOptions(models)), new java.util.HashSet<>(scannerOpts));
        for (Iterator<String> it = models.keys(); it.hasNext(); ) {
            String key = it.next();
            String name = models.getJSONObject(key).optString("name");
            if ("unsupported".equals(DeviceReport.status(models, key)))
                assertFalse("unsupported scanner in the form: " + name, scannerOpts.contains(name));
        }
        for (String gone : new String[]{"Sermoon P1", "Sermoon P1 Lite", "Creality Pika", "Elegoo M1 Ultra"})
            assertFalse(gone, scannerOpts.contains(gone));
        assertTrue(DeviceReport.isFormLink(REPO + "/issues/new?template=device-report.yml"));
    }

    private static List<String> options(String yml, String id) {
        Matcher m = Pattern.compile("id: " + id + "\\s*\\n(?:.*\\n)*?\\s*options:\\s*\\n((?:\\s*- .*\\n)+)").matcher(yml.replace("\r", ""));
        assertTrue("options for " + id, m.find());
        List<String> out = new ArrayList<>();
        for (String l : m.group(1).split("\n")) if (l.trim().startsWith("- ")) out.add(l.trim().substring(2).trim());
        return out;
    }
}

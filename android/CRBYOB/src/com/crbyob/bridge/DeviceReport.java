package com.crbyob.bridge;

import android.app.Activity;
import android.os.Build;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Send a device report": a review dialog with every field of the GitHub Device report form
 * (.github/ISSUE_TEMPLATE/device-report.yml), filled in with what the app already knows; the measured values
 * (firmware, app/phone/Android) are shown but not editable. Continue offers the apps that open GitHub links (the
 * GitHub app, browsers) and opens the form there with exactly those values, through its URL parameters (field
 * id = value), where the person submits it; nothing is sent from the app.
 *
 * Filled in: title, scanner, firmware, app/phone/Android line, the network the bridge ran on, and this session's
 * measurements (fps and delivery per stream, bitrate, frame-rate setting, USB speed, link). With "Include the
 * log" (on by default) the Log field gets the log (LogExport): in full in the GitHub app; in a browser, a digest,
 * with the whole log saved to Downloads as a zip to attach (LogFiles). Never included, because the issue is
 * public: the scanner serial, Wi-Fi network names, passwords, MAC and IP addresses.
 *
 * The link carries only what the chosen app reads: the GitHub app takes title and body and ignores the form's
 * fields; the web form fills its fields by id and doesn't need the body. The GitHub app takes long links but
 * can't attach files, so it gets the readable log in the body. GitHub's website refuses links much over 6,000
 * characters (MAX_URL), so the web form gets a digest in whatever room the report leaves, plus the zip.
 */
final class DeviceReport {
    private DeviceReport() {}

    static final String TEMPLATE = "device-report.yml";
    /** The template's "Other" scanner option, for a scanner whose name isn't in its list. */
    static final String OTHER = "Other (say which below)";

    /** The latest dashboard snapshot (running or idle), kept by MainActivity for reports opened from the docs. */
    static volatile Dash.Snap lastSnap;

    /** Form fields by template id, in form order. Values never null. */
    static final class Fields {
        String title = "", scanner = "", scannerFw = "", appInfo = "", network = "", client = "", result = "", details = "";
        /** The scanner was identified over USB (a model the app knows): shown, not picked. */
        boolean scannerDetected;
        /** Where the measurements come from: the bridge session (SessionStats), 0 = no session / not streamed. */
        long streamMs, sessionStartMs, sessionStopMs;
        /** This session hasn't streamed: the measurements are those of the run that crashed or was killed. */
        boolean fromStoppedRun;
        /** "Include the log": a digest in the Log field and the whole log saved to Downloads to attach. */
        boolean includeLog = true;
    }

    /** The GitHub app's package: it gets title and body, everything else gets the form's fields. */
    static final String GITHUB_APP = "com.github.android";
    /** Longest web-form link. GitHub answered a 6,000-character /issues/new link and failed at 6,500 (Nov 2026). */
    static final int MAX_URL = 5800;
    /** Longest GitHub-app body: GitHub caps an issue body at 65,536 characters. */
    static final int MAX_BODY = 60_000;

    /** The template's Network and Result options (DeviceReportTest checks them against the form). */
    static final String[] NETWORKS = {"Wi-Fi Direct", "Wi-Fi", "Ethernet",
            "This phone only (CrealityScan mobile app)"};
    static final String[] RESULTS = {"Works (preview and a full scan)", "Partly works (say what doesn't)", "Doesn't work"};

    /** Template option for the network the bridge answers on (Snap.advertise). */
    static String networkOption(String advertise) {
        if (advertise == null) return "";
        switch (advertise) {
            case "p2p": return "Wi-Fi Direct";
            case "wifi": return "Wi-Fi";
            case "eth": return "Ethernet";
            case "local": return "This phone only (CrealityScan mobile app)";
            default: return "";
        }
    }

    /**
     * The pre-filled "new issue" URL for the GitHub app (title and body: it ignores the form's fields) or for the
     * web form (its fields by id). `logs` goes in the Log field. Empty fields are left out so the form shows its
     * own placeholder.
     */
    static String url(String repoUrl, Fields f, boolean githubApp, String logs) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("template", TEMPLATE);
        q.put("title", f.title);
        if (githubApp) {
            // the same report as text, laid out the way GitHub writes a submitted form
            q.put("body", body(f, logs));
        } else {
            q.put("scanner", f.scanner);
            q.put("scanner_fw", f.scannerFw);
            q.put("app_info", f.appInfo);
            q.put("network", f.network);
            q.put("client", f.client);
            q.put("result", f.result);
            q.put("details", f.details);
            q.put("logs", logs);
        }
        q.put("labels", "device report");
        StringBuilder sb = new StringBuilder(repoUrl).append("/issues/new");
        char sep = '?';
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sb.append(sep).append(e.getKey()).append('=').append(enc(e.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    /**
     * The report with the log for the app the person picked (logFor). `fullLog` says where the whole log is, for
     * the web form's digest.
     */
    static String reportUrl(String repoUrl, Fields f, boolean githubApp, String fullLog,
                            List<String> pinned, List<String> recent) {
        return url(repoUrl, f, githubApp, logFor(repoUrl, f, githubApp, fullLog, pinned, recent));
    }

    /**
     * The Log field for this app ("" when the log isn't included or nothing fits). The GitHub app takes long
     * links (78 KB tested, Nov 2026) but can't attach files, so it gets the readable log, setup plus as many recent
     * lines as GitHub's body limit allows. The web form's link stops near 6,000 characters, so it gets the digest,
     * and the whole log goes in the zip to attach.
     */
    static String logFor(String repoUrl, Fields f, boolean githubApp, String fullLog,
                         List<String> pinned, List<String> recent) {
        if (!f.includeLog) return "";
        if (githubApp) {
            int room = MAX_BODY - body(f, "").length() - 20;
            return room < 200 ? "" : LogExport.readable(pinned, recent, "CR-BYOB log (serial numbers, Wi-Fi names, "
                    + "passwords, MAC and IP addresses removed). Older lines: Export log under Events in CR-BYOB.",
                    room, new LinkedHashMap<>());
        }
        String bare = url(repoUrl, f, false, "");
        int room = MAX_URL - bare.length() - "&logs=".length();
        for (int tries = 0; tries < 5 && room > 0; tries++) {
            String d = LogExport.digest(pinned, recent, fullLog, room);
            if (d.isEmpty()) break;
            int over = url(repoUrl, f, false, d).length() - MAX_URL;
            if (over <= 0) return d;
            room -= over + 16;
        }
        return "";
    }

    /** Lines of log kept in an anonymous report's body when the whole log goes privately with it. */
    static final int SHORT_TAIL_LINES = 50;

    /**
     * The Log field of an anonymous report. When the whole log goes privately with it (`fullLogSent`), a short tail
     * (the setup lines and the last SHORT_TAIL_LINES lines); otherwise the GitHub app's long tail, as before.
     */
    static String anonLog(Fields f, boolean fullLogSent, List<String> pinned, List<String> recent) {
        if (!f.includeLog) return "";
        if (!fullLogSent) return logFor("", f, true, "", pinned, recent);
        List<String> tail = recent.subList(Math.max(0, recent.size() - SHORT_TAIL_LINES), recent.size());
        int room = MAX_BODY - body(f, "").length() - 20;
        return room < 200 ? "" : LogExport.readable(pinned, tail, "CR-BYOB log (serial numbers, Wi-Fi names, passwords, "
                + "MAC and IP addresses removed). The whole log was sent privately to the maintainer.", room, new LinkedHashMap<>());
    }

    /** The digest's pointer to the whole log: the saved archive, or Export log when it couldn't be saved. */
    static String fullLogNote(String savedName, String where) {
        return savedName != null ? savedName + " in " + where + ", attach it here"
                : "not attached (Export log under Events in CR-BYOB saves it)";
    }

    /** The report as GitHub renders a submitted issue form: "### Label", a blank line, the value ("_No response_"). */
    static String body(Fields f, String logs) {
        String log = logs == null || logs.isEmpty() ? "" : "```text\n" + logs + "\n```";
        String[][] rows = {
                {"Scanner", f.scanner}, {"Scanner firmware", f.scannerFw}, {"App, phone and Android version", f.appInfo},
                {"Network", f.network}, {"Creality Scan version", f.client}, {"Result", f.result},
                {"What happened", f.details}, {"Log", log}};
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("### ").append(r[0]).append("\n\n").append(r[1] == null || r[1].isEmpty() ? "_No response_" : r[1]);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");     // %20: GitHub keeps "+" literally
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Is this link the Device report form (from the docs)? Then the app opens it pre-filled instead. */
    static boolean isFormLink(String url) {
        return url != null && url.contains("/issues/new") && url.contains("template=" + TEMPLATE);
    }

    /** Fills the fields from a dashboard snapshot (may be null), the current session's stats and the build. */
    /** title with its first `was` replaced by `now`, or null when it no longer contains `was`. */
    static String titleWithScanner(String title, String was, String now) {
        int i = title.indexOf(was);
        return i < 0 ? null : title.substring(0, i) + now + title.substring(i + was.length());
    }

    static Fields collect(Dash.Snap s, SessionStats ss, String appLine, String phone) {
        return collect(s, ss, appLine, phone, null);
    }

    /** How Android treats the app on this phone: background limits and why the app last closed. */
    static final class AppHealth {
        Boolean unrestricted;   // exempt from battery optimization; null = unknown
        String lastExit;        // exitLine() of the newest problem exit (or "no crash ..."), null = no history
        CrashRecord.Run crashed;   // that run's saved record (matched by pid), null when none was kept
        long crashTs;              // when it stopped (ApplicationExitInfo timestamp)
    }

    static Fields collect(Dash.Snap s, SessionStats ss, String appLine, String phone, AppHealth health) {
        Fields f = new Fields();
        String model = s == null ? null : s.modelName != null ? s.modelName : (s.scanner || s.plugged) ? s.scannerModel : null;
        boolean unlisted = model != null && model.startsWith("PID ");     // product ID not in scanners.json
        f.scanner = model == null ? "" : unlisted ? OTHER : model;
        f.scannerDetected = model != null && !unlisted;
        f.scannerFw = s != null && s.scannerFw != null ? s.scannerFw : "";
        f.appInfo = appLine;
        f.network = s == null ? "" : networkOption(s.advertise);
        f.title = "[Device] " + (model != null ? model : "<scanner>") + " on " + phone;
        if (ss != null) {
            f.streamMs = ss.streamMs;
            f.sessionStartMs = ss.startMs;
            f.sessionStopMs = ss.stopMs;
        }

        StringBuilder d = new StringBuilder();
        boolean streamed = ss != null && ss.streamMs > 0;
        d.append(streamed ? "Measured by CR-BYOB over " + SessionStats.dur(ss.streamMs) + " of streaming:\n"
                : "Measured by CR-BYOB (no streaming in this session yet):\n");
        appendSetup(d, s, model);
        if (streamed) appendSession(d, s, ss);
        if (s != null && s.soc != null && !s.soc.isEmpty()) d.append("- Phone chip: ").append(s.soc).append('\n');
        if (health != null && health.unrestricted != null)
            d.append("- Battery optimization: ").append(health.unrestricted ? "off for CR-BYOB (unrestricted)"
                    : "on for CR-BYOB (Android may stop it in the background)").append('\n');
        if (health != null && health.lastExit != null) d.append("- App crashes and kills: ").append(health.lastExit).append('\n');
        if (s != null && s.linkScore >= 0 && s.linkWord != null) d.append("- Link quality now: ").append(s.linkWord).append('\n');
        if (f.scanner.isEmpty()) d.append("- Scanner: not plugged in when this report was opened\n");
        if (unlisted) d.append("- Scanner USB ID: ").append(model.substring(4)).append(" (not in the app's model list: please say which model it is)\n");
        if (health != null && health.crashed != null) {
            appendStoppedRun(d, health);
            SessionStats rs = health.crashed.stats;
            if (!streamed && rs != null && rs.streamMs > 0) {      // its measurements stand in for this session's
                f.streamMs = rs.streamMs;
                f.sessionStartMs = rs.startMs;
                f.sessionStopMs = health.crashTs;
                f.fromStoppedRun = true;
            }
        }
        d.append("\n" + NOTES_MARKER + "\n\n");
        f.details = d.toString();
        return f;
    }

    /** USB speed, frame-rate setting and connection of a snapshot (now, or the saved state of a stopped run). */
    private static void appendSetup(StringBuilder d, Dash.Snap s, String model) {
        if (model != null && s != null && (s.scanner || s.plugProbed)) d.append("- USB: ").append(s.usbSuper ? "USB 3" : "USB 2").append('\n');
        if (s != null && s.fpsMode != null) d.append("- Frame-rate setting: ").append(cap(s.fpsMode)).append('\n');
        String network = s == null ? "" : networkOption(s.advertise);
        if (s != null && !network.isEmpty()) {
            d.append("- Connection: ").append(network);
            if ("p2p".equals(s.advertise) && s.p2pFreq > 0) d.append(", ").append(Dash.band(s.p2pFreq));
            if ("wifi".equals(s.advertise) && s.staConnected) {                   // generation, band, signal: no SSID
                d.append(", ");
                if (s.staStd > 0) d.append("Wi-Fi ").append(s.staStd).append(", ");
                d.append(Dash.band(s.staFreq)).append(", ").append(s.staRssi).append(" dBm");
                int rate = s.staTx > 0 ? s.staTx : s.staLink;
                if (rate > 0) d.append(", ").append(rate).append(" Mbit/s link rate");
            }
            d.append('\n');
        }
    }

    /** A streamed session's measurements: per stream, data, link, latency, power, disconnects, errors, heat. */
    private static void appendSession(StringBuilder d, Dash.Snap s, SessionStats ss) {
        {
            String[] n = {"IR L", "IR R", "Color"};
            for (int i = 0; i < 3; i++) {
                if (ss.streamingMsPer[i] <= 0) continue;
                double fps = ss.sent[i] * 1000.0 / ss.streamingMsPer[i];
                d.append(String.format(Locale.US, "- %s: %.1f%s fps, %.0f%% of captured frames sent",
                        n[i], fps, ss.reqFps[i] > 0 ? " of " + ss.reqFps[i] : "", SessionStats.pct(ss.sent[i], ss.captured[i])));
                long dropped = ss.qdrops[i] + ss.skipped[i];
                if (dropped > 0) d.append(", ").append(dropped).append(" dropped");
                if (ss.reducedMs[i] > 0)                   // ½ or ⅓ rate because the link couldn't keep up
                    d.append(String.format(Locale.US, ", at reduced rate %.0f%% of the time",
                            SessionStats.pct(ss.reducedMs[i], ss.streamingMsPer[i])));
                d.append('\n');
            }
            d.append(String.format(Locale.US, "- Data: avg %.0f Mbit/s, peak %.0f Mbit/s%n", ss.avgMbps(), ss.peakMbps));
            // only when the bridge runs on Wi-Fi: with This phone only the phone's Wi-Fi plays no part
            if (s != null && "wifi".equals(s.advertise) && ss.rssiN > 0) {
                d.append(String.format(Locale.US, "- Wi-Fi signal during the session: avg %d dBm, worst %d dBm",
                        ss.rssiSum / ss.rssiN, ss.rssiMin));
                if (ss.linkMin > 0) d.append(", lowest link rate ").append(ss.linkMin).append(" Mbit/s");
                d.append('\n');
            }
            if (s != null && "p2p".equals(s.advertise))   // the two share the phone's radio: Wi-Fi Direct slows down
                d.append("- Phone also on a Wi-Fi network: ").append(ss.p2pDualMs <= 0 ? "no"
                        : String.format(Locale.US, "%.0f%% of the streaming time (%s), sharing the radio with Wi-Fi Direct",
                                SessionStats.pct(ss.p2pDualMs, ss.streamMs), Dash.band(ss.p2pDualFreq))).append('\n');
            if (ss.frameAge.count() > 0) {
                d.append(String.format(Locale.US, "- Latency: frame age avg %.1f ms, p95 %.0f ms",
                        ss.frameAge.avgMs(), ss.frameAge.pMs(0.95)));
                if (ss.xu.count() > 0) d.append(String.format(Locale.US, "; scanner replies p95 %.0f ms", ss.xu.pMs(0.95)));
                d.append('\n');
            }
            long onPower = ss.poweredMs, onBattery = ss.batteryMs;
            if (onPower + onBattery > 0) {
                d.append("- Phone power: ");
                if (onBattery == 0) d.append("external the whole session").append(ss.charger != null ? " (" + ss.charger + ")" : "");
                else if (onPower == 0) d.append("battery the whole session");
                else d.append(String.format(Locale.US, "on battery %.0f%% of the session", SessionStats.pct(onBattery, onPower + onBattery)))
                        .append(ss.charger != null ? ", otherwise " + ss.charger : "");
                d.append('\n');
            }
            if (ss.batteryStart >= 0 && ss.batteryEnd >= 0)
                d.append("- Phone battery: ").append(ss.batteryStart).append("% at the start, ").append(ss.batteryEnd).append("% at the end\n");
            // a deliberate unplug and a brown-out look the same to the app: when it happened tells them apart
            long det = ss.usbDetach.get(), detStreaming = ss.detachStreaming.get(), onBatt = ss.brownouts.get();
            d.append("- Scanner disconnects: ");
            if (det == 0) d.append("none");
            else {
                d.append(detStreaming).append(" while streaming, ").append(det - detStreaming).append(" while not streaming");
                if (onBatt > 0) d.append(" (").append(onBatt).append(" while the phone was on battery)");
            }
            d.append('\n');
            List<String> also = new ArrayList<>();
            count(also, ss.pcDrops.get(), "PC disconnect", "PC disconnects");
            count(also, ss.rtspErrors.get(), "stream error", "stream errors");
            count(also, ss.stalledClosed.get(), "stalled stream closed", "stalled streams closed");
            count(also, ss.uvcErrors.get(), "USB frame error", "USB frame errors");
            if (!also.isEmpty()) d.append("- Also counted: ").append(String.join(", ", also)).append('\n');
            long mirror = ss.mirrorConnects.get();
            d.append("- Mirror viewer: ").append(mirror == 0 ? "not used"
                    : "used (" + mirror + (mirror == 1 ? " connection)" : " connections)")).append('\n');
            if (ss.thermalMax >= 0)
                d.append("- Phone heat: ").append(ss.thermalMax == 0 ? "normal"      // Android's thermal status, worst seen
                        : SessionStats.thermalName(ss.thermalMax).toLowerCase(Locale.ROOT) + " at worst (Android's thermal status)").append('\n');
        }
    }

    /**
     * The run that crashed or was killed, from its CrashRecord: when it stopped relative to its start, what it was
     * doing, where a Java crash happened, its setup and measurements, and its last events timed back from the end.
     */
    private static void appendStoppedRun(StringBuilder d, AppHealth h) {
        CrashRecord.Run r = h.crashed;
        long at = h.crashTs;
        d.append("\nThe run that stopped (").append(h.lastExit).append("):\n");
        d.append("- ").append(cap(stoppedRunSummary(r, at))).append('\n');
        if (r.error != null) d.append("- Error: ").append(r.error).append('\n');
        d.append("- Last saved ").append(SessionStats.dur(Math.max(0, at - r.savedMs))).append(" before it stopped\n");
        String model = r.snap.modelName != null ? r.snap.modelName : r.snap.scanner ? r.snap.scannerModel : null;
        if (r.snap.running) appendSetup(d, r.snap, model);
        if (model != null) d.append("- Scanner: ").append(model).append(r.snap.scannerFw != null ? ", fw " + r.snap.scannerFw : "").append('\n');
        if (r.stats != null && r.stats.streamMs > 0) appendSession(d, r.snap, r.stats);
        if (r.eventMs.length > 0) {
            d.append("- Events before it stopped (newest first):\n");
            for (int i = 0; i < r.eventMs.length; i++)
                d.append("  - ").append(SessionStats.dur(Math.max(0, at - r.eventMs[i]))).append(" before: ").append(r.eventText[i]).append('\n');
        }
    }

    /** "12m 30s after the app started, bridge on 11m 02s, streaming 8m 10s: streaming to a PC over Wi-Fi Direct". */
    static String stoppedRunSummary(CrashRecord.Run r, long at) {
        StringBuilder sb = new StringBuilder();
        sb.append(SessionStats.dur(Math.max(0, at - r.appStartMs))).append(" after the app started");
        SessionStats rs = r.stats;
        if (rs != null && r.snap.running) {
            sb.append(", bridge on ").append(SessionStats.dur(Math.max(0, at - rs.startMs)));
            if (rs.streamMs > 0) sb.append(", streaming ").append(SessionStats.dur(rs.streamMs));
        }
        return sb.append(": ").append(CrashRecord.doing(r)).toString();
    }

    /** The line above "What happened": which session the measurements are from, or that there are none. */
    static String statsSource(Fields f, java.text.DateFormat time) {
        if (f.streamMs <= 0)
            return "No streaming in this bridge session yet, so the report has no frame rates. Run a scan first for a "
                    + "more useful report.";
        String when = f.sessionStartMs > 0 ? ", started " + time.format(new java.util.Date(f.sessionStartMs))
                + (f.sessionStopMs > 0 ? ", stopped " + time.format(new java.util.Date(f.sessionStopMs)) : ", still running")
                : "";
        if (f.fromStoppedRun)
            return "This session hasn't streamed yet: the report has the measurements of the run that stopped unexpectedly ("
                    + SessionStats.dur(f.streamMs) + " of streaming" + when + ").";
        return "Measurements are from this bridge session: " + SessionStats.dur(f.streamMs) + " of streaming" + when + ".";
    }

    /**
     * Ask before sending: nothing was streamed in this session, so the report has no frame rates to back up
     * "Works" or "Partly works". "Doesn't work" is exempt: a setup that fails often can't stream at all.
     */
    static boolean needsScanFirst(Fields f) {
        return f.streamMs <= 0 && !f.result.isEmpty() && !RESULTS[2].equals(f.result);
    }

    /** The line that ends the measurements in "What happened"; what the person types goes after it. */
    static final String NOTES_MARKER = "What I did, what worked, what didn't:";

    /** What the person typed in "What happened" after the measurements (the part a draft keeps). */
    static String notesOf(String details) {
        int i = details.indexOf(NOTES_MARKER);
        return i < 0 ? details.trim() : details.substring(i + NOTES_MARKER.length()).trim();
    }

    /** The person's own entries, kept when they leave to scan first, so the next report starts with them. */
    static volatile Fields draft;

    /** Carries the person's entries from a draft into freshly collected fields (measurements stay fresh). */
    static void restore(Fields f, Fields d) {
        if (d == null) return;
        if (!d.client.isEmpty()) f.client = d.client;
        if (!d.result.isEmpty()) f.result = d.result;
        f.includeLog = d.includeLog;
        if (!f.scannerDetected && !d.scanner.isEmpty()) f.scanner = d.scanner;
        String notes = notesOf(d.details);
        if (!notes.isEmpty()) f.details = f.details.trim() + "\n" + notes + "\n";
    }

    /** Battery-optimization exemption and the previous process's exit (Android 11+), read when the report opens. */
    static AppHealth appHealth(android.content.Context c) {
        AppHealth h = new AppHealth();
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null) h.unrestricted = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception ignored) {
        }
        Exits e = exits(c);
        if (e != null) {
            long[] ages = e.ages();
            h.lastExit = lastProblemExit(e.reasons, e.importance, ages);
            int i = newestProblem(e.reasons, e.importance, ages);
            if (i >= 0) {
                h.crashed = CrashRecord.load(c, e.pids[i]);
                h.crashTs = e.timestamps[i];
            }
        }
        return h;
    }

    /** How the app's last processes ended, newest first (ApplicationExitInfo, Android 11+). */
    static final class Exits {
        int[] reasons = new int[0], importance = new int[0], pids = new int[0];
        long[] timestamps = new long[0];

        long[] ages() {
            long now = System.currentTimeMillis();
            long[] a = new long[timestamps.length];
            for (int i = 0; i < a.length; i++) a[i] = now - timestamps[i];
            return a;
        }
    }

    /** Android keeps these after the process dies, so the next run can see how the last ones ended. Null below 11. */
    static Exits exits(android.content.Context c) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 10);
            Exits e = new Exits();
            e.reasons = new int[l.size()];
            e.importance = new int[l.size()];
            e.pids = new int[l.size()];
            e.timestamps = new long[l.size()];
            for (int i = 0; i < l.size(); i++) {
                e.reasons[i] = l.get(i).getReason();
                e.importance[i] = l.get(i).getImportance();
                e.pids[i] = l.get(i).getPid();
                e.timestamps[i] = l.get(i).getTimestamp();
            }
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Did this exit point at a problem? Crashes, ANRs and failed starts always do. Android killing the app counts
     * only while it was in use (importance VISIBLE = 200 or better: the dashboard open or the bridge's foreground
     * service running); clearing a cached background app is routine. Normal closes never count.
     */
    static boolean isProblem(int reason, int importance) {
        switch (reason) {
            case 4: case 5: case 6: case 7: return true;                          // crash, native crash, ANR, start
            case 1: case 8: case 10: case 11: case 15: case 16: return false;     // itself, permission, user, update
            default: return importance > 0 && importance <= 200;                  // killed by Android
        }
    }

    /** Index of the newest problem exit within the last 24 h (arrays newest first), or -1. */
    static int newestProblem(int[] reasons, int[] importance, long[] agesMs) {
        for (int i = 0; i < reasons.length; i++) {
            if (agesMs[i] > 24 * 3_600_000L) break;
            if (isProblem(reasons[i], importance[i])) return i;
        }
        return -1;
    }

    /**
     * The newest problem exit of the last 24 h in words; a note when there's none; null with no history. Normal
     * closes are skipped, so a crash doesn't hide behind a later swipe-away.
     */
    static String lastProblemExit(int[] reasons, int[] importance, long[] agesMs) {
        if (reasons.length == 0) return null;
        int i = newestProblem(reasons, importance, agesMs);
        return i < 0 ? "no crash or kill by Android in the last 24 h" : exitLine(reasons[i], agesMs[i]);
    }

    /**
     * ApplicationExitInfo.REASON_* in plain words, plus how long ago: "killed by Android (low memory), 12 min ago".
     * Numbers rather than the constants, so the table compiles and tests without the newer API levels' fields.
     */
    static String exitLine(int reason, long agoMs) {
        String why;
        switch (reason) {
            case 1: why = "closed normally"; break;
            case 2: why = "stopped by Android (signal)"; break;
            case 3: why = "killed by Android (low memory)"; break;
            case 4: why = "crashed"; break;
            case 5: why = "crashed (native code)"; break;
            case 6: why = "stopped responding (ANR)"; break;
            case 7: why = "failed to start"; break;
            case 8: why = "restarted after a permission change"; break;
            case 9: why = "killed by Android (too much battery or CPU use)"; break;
            case 10: why = "closed by the user (swiped away or force-stopped)"; break;
            case 11: why = "force-stopped by the user"; break;
            case 12: why = "stopped because a component it relies on stopped"; break;
            case 13: why = "killed by Android (other)"; break;
            case 14: why = "frozen by Android"; break;
            case 15: why = "closed after a change to the app's state"; break;
            case 16: why = "closed for an app update"; break;
            default: why = reason == 0 ? "unknown reason" : "other (reason " + reason + ")";
        }
        long min = Math.max(0, agoMs) / 60_000;
        String ago = min < 1 ? "just now" : min < 60 ? min + " min ago" : min < 48 * 60 ? min / 60 + " h ago" : min / (24 * 60) + " d ago";
        return why + ", " + ago;
    }

    /** "2 stream errors" / "1 PC disconnect", only when the count isn't zero. */
    private static void count(List<String> out, long n, String one, String many) {
        if (n > 0) out.add(n + " " + (n == 1 ? one : many));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Send a device report: with anonymous reports still open, first offers to add the latest logs to one of them
     * (its thread opens with the logs attached) instead of starting a new report. A kept draft (Scan first) goes
     * straight back to the form.
     */
    static void open(Activity a) {
        java.util.List<org.json.JSONObject> open = AnonReports.enabled() && draft == null
                ? AnonReports.openReports(AnonReports.reports(a.getSharedPreferences("bridge", Activity.MODE_PRIVATE)))
                : new ArrayList<>();
        if (open.isEmpty()) {
            openForm(a);
            return;
        }
        String[] items = new String[open.size()];
        for (int i = 0; i < items.length; i++) {
            String t = AnonReports.title(open.get(i));
            items[i] = "#" + open.get(i).optInt("n") + (t.isEmpty() ? "" : " \u00b7 " + t);
        }
        BLog.log("device_report_open_reports", "count", items.length);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                .setTitle("Add your latest logs to an open report?")
                .setItems(items, (x, which) -> {
                    BLog.log("device_report_add_to_open");
                    ReportsActivity.open(a, open.get(which).optInt("n"), true);
                })
                .setPositiveButton("Start a new report", (x, w) -> openForm(a))
                .setNegativeButton("Cancel", null)
                .create();
        Widgets.showStyled(d);
    }

    /** Shows the review dialog, filled in from the bridge (running) or the dashboard's last snapshot. */
    static void openForm(Activity a) {
        Bridge b = BridgeService.current();
        Dash.Snap s = b != null ? b.dash.snap : lastSnap;
        Fields f = collect(s, SessionStats.cur, AppVersion.bugReportLine(), Build.MANUFACTURER + " " + Build.MODEL, appHealth(a));
        restore(f, draft);
        draft = null;
        BLog.log("device_report_review", "scanner", f.scanner, "network", f.network);
        new Review(a, f).show();
    }

    /** Scanner names for the picker: the models in scanners.json a bridge can serve, in its order, then "Other". */
    static java.util.List<String> scannerOptions(android.content.Context c) {
        try {
            return scannerOptions(ScannerModel.loadDocument(c, c.getFilesDir()).getJSONObject("models"));
        } catch (Exception e) {
            return new java.util.ArrayList<>(java.util.Collections.singletonList(OTHER));
        }
    }

    /**
     * Every model name in a scanners.json "models" object except the unsupported ones (self-contained wireless
     * scanners such as the Sermoon P1 and Pika, which no Scan Bridge serves), then "Other". The form's Scanner
     * list must match this (DeviceReportTest).
     */
    static java.util.List<String> scannerOptions(org.json.JSONObject models) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (java.util.Iterator<String> it = models.keys(); it.hasNext(); ) {
            String key = it.next();
            org.json.JSONObject m = models.optJSONObject(key);
            if (m == null || "unsupported".equals(status(models, key))) continue;
            String n = m.optString("name", "");
            if (!n.isEmpty() && !out.contains(n)) out.add(n);
        }
        out.add(OTHER);
        return out;
    }

    /** A model's status, following "inherit" (the Sermoon P1 Lite inherits "unsupported" from the P1). */
    static String status(org.json.JSONObject models, String key) {
        for (int i = 0; key != null && i < 8; i++) {
            org.json.JSONObject m = models.optJSONObject(key);
            if (m == null) return "";
            if (m.has("status")) return m.optString("status");
            key = m.optString("inherit", null);
        }
        return "";
    }

    /**
     * The review dialog: the form's fields, in its order, styled like the rest of the app. Lists open a picker;
     * the rest are text fields. Continue needs the form's required fields, then opens GitHub in the browser.
     */
    private static final class Review {
        private final Activity a;
        private final Fields f;
        private final android.widget.LinearLayout body;
        private android.widget.EditText title, client, details;
        private String measured = "";   // f.details up to the notes marker: sent above what the person types
        private android.widget.TextView scanner, network, result, error;
        private android.widget.TextView facts;       // scanner model/firmware and app/phone line, kept current
        private String titleScanner = "<scanner>";    // the scanner the title names, until the reporter rewrites it
        private android.widget.CheckBox includeLog;
        private LogFiles.Saved saved;                // the log archive saved for this report, null = none
        private android.widget.ScrollView scroll;
        private boolean tried;                       // a send was tapped once: the "Still needed" line shows
        private android.app.AlertDialog dialog;
        private android.widget.TextView githubLink;   // "Send Report with my own GitHub account instead", null when anon is off
        private android.widget.Button sendBtn, cancelBtn;   // the anonymous dialog's own buttons, null when anon is off
        private android.widget.LinearLayout sending;          // spinner + "Sending…", shown in the link's place
        private boolean anon;                        // this submit goes to the report Worker, not the GitHub form
        // the whole log for an anonymous send, built while the form is open; null when anon is off
        private java.util.concurrent.FutureTask<java.io.File> logZip;

        Review(Activity a, Fields f) {
            this.a = a;
            this.f = f;
            body = new android.widget.LinearLayout(a);
            body.setOrientation(android.widget.LinearLayout.VERTICAL);
            body.setPadding(dp(20), dp(4), dp(20), dp(8));
        }

        private int dp(float v) {
            return Widgets.dp(a, v);
        }

        private android.widget.TextView text(float sp, int color) {
            android.widget.TextView t = new android.widget.TextView(a);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            return t;
        }

        private void label(String s, boolean required) {
            android.widget.TextView t = text(13, Widgets.SUB);
            t.setText(required ? s + " *" : s);
            t.setPadding(0, dp(12), 0, dp(4));
            body.addView(t);
        }

        private android.widget.EditText field(String value, String hint, boolean multi) {
            android.widget.EditText e = new android.widget.EditText(a);
            e.setTextColor(Widgets.TEXT);
            e.setHintTextColor(Widgets.GREY);
            e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            mark(e, false);
            e.setPadding(dp(10), dp(8), dp(10), dp(8));
            e.setText(value);
            e.setHint(hint);
            e.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence x, int a1, int b1, int c1) { }
                @Override public void onTextChanged(CharSequence x, int a1, int b1, int c1) { }
                @Override public void afterTextChanged(android.text.Editable x) { check(); }
            });
            if (multi) {
                e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                e.setSingleLine(false);
                e.setMinLines(6);
                e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            } else {
                // one line of input (Enter doesn't add a line break) that still wraps on screen, so long values
                // such as the app/phone line show in full
                e.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                e.setHorizontallyScrolling(false);
                e.setMaxLines(4);
            }
            body.addView(e, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            return e;
        }

        /** A field that picks one of `options` in a styled list; "Choose…" until set. */
        private android.widget.TextView picker(String title, String value, java.util.List<String> options) {
            android.widget.TextView t = text(15, Widgets.TEXT);
            mark(t, false);
            t.setPadding(dp(10), dp(10), dp(10), dp(10));
            setPick(t, value);
            t.setOnClickListener(v -> {
                String cur = (String) t.getTag();
                android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                        .setTitle(title)
                        .setSingleChoiceItems(options.toArray(new String[0]), options.indexOf(cur), (dd, which) -> {
                            setPick(t, options.get(which));
                            if (t == scanner) retitle(options.get(which));
                            dd.dismiss();
                        })
                        .setNegativeButton("Cancel", null)
                        .create();
                Widgets.showStyled(d);
            });
            body.addView(t, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            return t;
        }

        /** A picked scanner goes into the title in place of "<scanner>" (or the last pick), unless it was rewritten. */
        private void retitle(String pick) {
            String now = OTHER.equals(pick) ? "<scanner>" : pick;
            String t = titleWithScanner(title.getText().toString(), titleScanner, now);
            if (t == null) return;                    // the reporter wrote their own title
            title.setText(t);
            titleScanner = now;
        }

        private void setPick(android.widget.TextView t, String value) {
            boolean set = value != null && !value.isEmpty();
            t.setTag(set ? value : "");
            t.setText(set ? value + "  \u25BE" : "Choose\u2026  \u25BE");
            t.setTextColor(set ? Widgets.TEXT : Widgets.GREY);
            check();
        }

        /** A small note line under a field (hidden until set). */
        private android.widget.TextView note(int color) {
            android.widget.TextView t = text(13, color);
            t.setPadding(0, dp(4), 0, dp(2));
            t.setVisibility(android.view.View.GONE);
            body.addView(t);
            return t;
        }

        /** An editable field's box: filled, with a red outline while it's required and still empty. */
        private void mark(android.view.View v, boolean missing) {
            android.graphics.drawable.GradientDrawable g = Widgets.roundRect(Widgets.LINE, dp(8));
            if (missing) g.setStroke(Math.max(1, dp(2)), Widgets.RED);
            int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
            v.setBackground(g);
            v.setPadding(l, t, r, b);                // setBackground can reset padding
        }

        void show() {
            android.widget.TextView intro = text(14, Widgets.SUB);
            intro.setText("Check what's filled in and add what happened."
                    + (AnonReports.enabled() ? "" : " Continue opens it on GitHub, where you submit it."));
            body.addView(intro);
            label("Title", true);
            title = field(f.title, "[Device] <scanner> on <phone>", false);
            if (!f.scannerDetected) {                  // none plugged in, or a model the app doesn't know
                label("Scanner", true);
                scanner = picker("Scanner", f.scanner, scannerOptions(a));
            }
            // what the scanner and this phone said, as one read-only summary: it goes in the report as-is
            label("From the scanner and this phone", false);
            facts = text(13, Widgets.SUB);
            facts.setText(factsText());
            facts.setTextIsSelectable(true);
            body.addView(facts);
            label("Network", true);
            network = picker("Network", f.network, java.util.Arrays.asList(NETWORKS));
            label("Creality Scan version", false);
            client = field(f.client, "Creality Scan 4.x (Windows) / CrealityScan 3.1.2 (Android)", false);
            label("Result", true);
            result = picker("Result", f.result, java.util.Arrays.asList(RESULTS));
            label("What happened", false);
            android.widget.TextView src = note(f.streamMs > 0 ? Widgets.SUB : Widgets.AMBER);
            src.setText(statsSource(f, android.text.format.DateFormat.getTimeFormat(a)));
            src.setVisibility(android.view.View.VISIBLE);
            // the measurements ride along above the person's own words; the box holds only what they type
            int m = f.details.indexOf(NOTES_MARKER);
            measured = m < 0 ? "" : f.details.substring(0, m + NOTES_MARKER.length()) + "\n\n";
            android.widget.TextView seeMeasured = text(13, Widgets.BLUE);
            seeMeasured.setText("The measurements are attached · See them ›");
            seeMeasured.setPadding(0, dp(2), 0, dp(6));
            seeMeasured.setOnClickListener(v -> showText("Attached measurements",
                    measured.substring(0, Math.max(0, measured.indexOf(NOTES_MARKER))).trim()));
            if (!measured.isEmpty()) body.addView(seeMeasured);
            details = field(notesOf(f.details), "What you did, what worked, what didn't", true);
            details.setMinLines(4);
            includeLog = new android.widget.CheckBox(a);
            includeLog.setText("Include anonymous & redacted logs");
            includeLog.setTextColor(Widgets.TEXT);
            includeLog.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            includeLog.setButtonTintList(android.content.res.ColorStateList.valueOf(Widgets.BLUE));
            includeLog.setChecked(f.includeLog);
            android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(10);
            body.addView(includeLog, clp);
            // one line; the digest/zip details show in the digest view and when a browser opens the form
            android.widget.TextView logNote = text(13, Widgets.SUB);
            android.text.SpannableStringBuilder ln = new android.text.SpannableStringBuilder(
                    "Recent lines only. It helps most when something doesn't work. ");
            appendLink(ln, "See it \u203A", this::showDigest);
            logNote.setText(ln);
            logNote.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            body.addView(logNote);
            if (AnonReports.enabled()) buildLogZip();
            includeLog.setOnCheckedChangeListener((v, on) -> logNote.setAlpha(on ? 1f : 0.5f));
            if (!f.includeLog) logNote.setAlpha(0.5f);
            android.widget.TextView privacy = text(13, Widgets.AMBER);
            privacy.setText("This report will be visible publicly on CR-BYOB's GitHub page. Do not include information "
                    + "you don't want to share.");
            privacy.setPadding(0, dp(12), 0, 0);
            body.addView(privacy);
            error = text(14, Widgets.RED);
            error.setPadding(0, dp(8), 0, 0);
            error.setVisibility(android.view.View.GONE);
            body.addView(error);
            check();                                   // required fields that are empty show red from the start

            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(body);
            scroll = sv;
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(a).setTitle("Device report");
            if (AnonReports.enabled()) {
                b.setView(anonFooter(sv));
            } else {
                b.setView(sv).setPositiveButton("Continue on GitHub", null).setNegativeButton("Cancel", null);
            }
            android.app.AlertDialog d = b.create();
            if (d.getWindow() != null)
                d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (AnonReports.enabled()) {
                sendBtn.setOnClickListener(v -> {
                    anon = true;
                    submit();                          // sendAnon dismisses only once the send worked
                });
                cancelBtn.setOnClickListener(v -> d.dismiss());
                d.setOnDismissListener(x -> deleteZip());
            } else {
                d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    anon = false;
                    if (submit()) d.dismiss();
                }));
            }
            dialog = d;
            Widgets.showStyled(d);
            watchScanner.run();
        }

        /** Builds the whole log off the UI thread while the form is filled in, for an anonymous send. */
        private void buildLogZip() {
            logZip = new java.util.concurrent.FutureTask<>(() -> AnonReports.logZip(a));
            new Thread(() -> {
                logZip.run();
                try {
                    logZip.get();
                } catch (Exception e) {
                    BLog.log("log_export_error", "err", String.valueOf(e));
                }
            }, "report-log-zip").start();
        }

        /** The built log zip when it can go with the report (built, at most MAX_LOG); waits for the build. Off the UI thread. */
        private java.io.File zipToSend() {
            if (logZip == null || !f.includeLog) return null;
            try {
                java.io.File z = logZip.get();
                return z.length() <= AnonReports.MAX_LOG ? z : null;
            } catch (Exception e) {
                return null;
            }
        }

        /** While the form is open: a scanner plugged in after it opened fills in its model and firmware. */
        private final Runnable watchScanner = new Runnable() {
            @Override public void run() {
                if (dialog == null || !dialog.isShowing()) return;
                Bridge b = BridgeService.current();
                Dash.Snap s = b != null ? b.dash.snap : lastSnap;   // the dashboard refreshes it every second
                Fields now = collect(s, null, f.appInfo, "");
                boolean changed = false;
                if (!now.scannerFw.isEmpty() && !now.scannerFw.equals(f.scannerFw)) {
                    f.scannerFw = now.scannerFw;
                    changed = true;
                }
                if (now.scannerDetected && !f.scannerDetected) {
                    f.scannerDetected = true;
                    f.scanner = now.scanner;
                    if (scanner != null) {                 // the picker shown when none was plugged in
                        setPick(scanner, now.scanner);
                        retitle(now.scanner);
                    }
                    changed = true;
                }
                if (changed) facts.setText(factsText());
                body.postDelayed(this, 1000);
            }
        };

        /** The scanner model and firmware (or, in amber, how to get them) over the app/phone line. */
        private CharSequence factsText() {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            if (f.scannerDetected) sb.append(f.scanner).append(" (detected over USB) · ");
            if (f.scannerFw.isEmpty()) {
                int st = sb.length();
                sb.append("Firmware unknown: plug the scanner in to read it");
                sb.setSpan(new android.text.style.ForegroundColorSpan(Widgets.AMBER), st, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else sb.append("Firmware ").append(f.scannerFw);
            return sb.append('\n').append(f.appInfo);
        }

        /**
         * The form above its own buttons, with the GitHub-account way to send centered under them. The dialog's own
         * button bar can't do this: Android sizes only its own panels and drops anything added next to them. The form
         * takes what height is left, so the buttons and the link always show.
         */
        private android.view.View anonFooter(android.widget.ScrollView form) {
            android.widget.LinearLayout col = new android.widget.LinearLayout(a);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.addView(form, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            row.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            row.setPadding(dp(16), dp(10), dp(24), 0);   // 8dp more on the right: each button carries an 8dp left margin
            cancelBtn = new android.widget.Button(a);
            cancelBtn.setText("Cancel");
            sendBtn = new android.widget.Button(a);
            sendBtn.setText("Send Anonymous Report");
            row.addView(cancelBtn);
            row.addView(sendBtn);
            Widgets.styleButton(cancelBtn, false);
            Widgets.styleButton(sendBtn, true);
            col.addView(row, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            githubLink = text(14, Widgets.BLUE);
            githubLink.setText("Send Report with my own GitHub account instead ›");
            githubLink.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            githubLink.setPadding(dp(20), dp(4), dp(20), dp(16));
            githubLink.setOnClickListener(v -> {
                anon = false;
                if (submit()) dialog.dismiss();
            });
            col.addView(githubLink);
            // takes the link's place while a send runs (a few seconds): spinner and "Sending…"
            sending = new android.widget.LinearLayout(a);
            sending.setGravity(android.view.Gravity.CENTER);
            sending.setPadding(dp(20), dp(4), dp(20), dp(16));
            android.widget.ProgressBar spin = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleSmall);
            spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Widgets.BLUE));
            sending.addView(spin);
            android.widget.TextView st = text(14, Widgets.SUB);
            st.setText("Sending…");
            st.setPadding(dp(8), 0, 0, 0);
            sending.addView(st);
            sending.setVisibility(android.view.View.GONE);
            col.addView(sending);
            return col;
        }

        /**
         * No streaming stats in this session: ask before sending. "Scan first" closes the report and keeps the
         * person's entries for the next one; "Send anyway" goes on to GitHub.
         */
        private void confirmNoStats() {
            android.app.AlertDialog c = new android.app.AlertDialog.Builder(a)
                    .setTitle("No scan in this report")
                    .setMessage("No streaming stats were captured in this bridge session, so the report can't show "
                            + "frame rates or how the scan went. Can you do a quick scan first? Start the bridge, scan "
                            + "for a minute, then come back here: what you entered is kept.")
                    .setPositiveButton("Scan first", (x, w) -> {
                        draft = f;
                        BLog.log("device_report_scan_first");
                        if (dialog != null) dialog.dismiss();
                    })
                    .setNegativeButton("Send anyway", (x, w) -> {
                        if (dialog != null && !anon) dialog.dismiss();
                        proceed();
                    })
                    .create();
            Widgets.showStyled(c);
        }

        /**
         * Asks which app opens the report: the GitHub app and every browser, in a list styled like the app.
         * Android's own chooser can't do this: it goes straight to the GitHub app, which is the verified handler
         * for github.com links. With a single app it opens that one directly.
         */
        private void openWith() {
            android.content.pm.PackageManager pm = a.getPackageManager();
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(BuildConfig.REPO_URL + "/issues/new"))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            java.util.List<android.content.pm.ResolveInfo> found = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (android.content.Intent probe : new android.content.Intent[]{view, new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE)}) {   // the browsers, too
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(probe, android.content.pm.PackageManager.MATCH_ALL)) {
                    String pkg = ri.activityInfo.packageName;
                    if (!"android".equals(pkg) && seen.add(pkg)) found.add(ri);
                }
            }
            if (found.isEmpty()) {
                android.widget.Toast.makeText(a, "No app can open GitHub links", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            if (found.size() == 1) {
                launch(view, found.get(0).activityInfo.packageName);
                return;
            }
            String[] labels = new String[found.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(found.get(i).loadLabel(pm));
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                    .setTitle("Open the report in")
                    .setItems(labels, (dd, which) -> launch(view, found.get(which).activityInfo.packageName))
                    .setNegativeButton("Cancel", null)
                    .create();
            Widgets.showStyled(d);
        }

        /**
         * The person picked `pkg`. For a browser with Include the log, saves the zip to Downloads first (off the
         * UI thread: it redacts the whole log). The GitHub app can't attach files and gets the log in the body.
         */
        private void launch(android.content.Intent view, String pkg) {
            saved = null;
            if (GITHUB_APP.equals(pkg) || !f.includeLog) {
                open(view, pkg);
                return;
            }
            android.widget.Toast.makeText(a, "Saving the log to Downloads\u2026", android.widget.Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                LogFiles.Saved s = null;
                try {
                    s = LogFiles.saveToDownloads(a);
                } catch (Exception e) {
                    BLog.log("log_export_error", "err", String.valueOf(e));
                }
                LogFiles.Saved done = s;
                a.runOnUiThread(() -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    saved = done;
                    if (done == null)
                        android.widget.Toast.makeText(a, "Couldn't save the log file. The report still has a digest.",
                                android.widget.Toast.LENGTH_LONG).show();
                    open(view, pkg);
                });
            }, "report-log").start();
        }

        /** Opens the report in `pkg`, with the link made for that app (the GitHub app or the web form). */
        private void open(android.content.Intent view, String pkg) {
            LogFiles.Lines l = LogFiles.lines(a);
            String url = reportUrl(BuildConfig.REPO_URL, f, GITHUB_APP.equals(pkg), fullLog(), l.pinned, l.recent);
            BLog.log("device_report_opened", "app", pkg, "scanner", f.scanner, "result", f.result,
                    "url_chars", url.length(), "log_file", saved != null);
            try {
                a.startActivity(new android.content.Intent(view).setData(android.net.Uri.parse(url)).setPackage(pkg));
                if (saved != null)
                    android.widget.Toast.makeText(a, "Attach " + saved.name + " from " + saved.where + " in the Log field",
                            android.widget.Toast.LENGTH_LONG).show();
            } catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(a, "Couldn't open " + pkg, android.widget.Toast.LENGTH_LONG).show();
            }
        }

        private String fullLog() {
            return saved != null ? fullLogNote(saved.name, saved.where) : fullLogNote(null, null);
        }

        /** Continue: sends it anonymously, or asks which app opens the report (launch saves the log file when it's a browser). */
        private void proceed() {
            if (anon) sendAnon();
            else openWith();
        }

        /**
         * Posts the report to the Worker off the UI thread, with the GitHub app's log (no file to attach). The review
         * stays open, buttons off, until it's done: on failure the person's entries are still there with the error.
         */
        private void sendAnon() {
            String issueTitle = f.title;
            enableButtons(false);
            error.setVisibility(android.view.View.GONE);
            new Thread(() -> {
                AnonReports.Sent s = null;
                int code = 0;
                String msg = null;
                try {
                    LogFiles.Lines l = LogFiles.lines(a);
                    java.io.File zip = zipToSend();   // the short tail only when the whole log goes along
                    String report = body(f, anonLog(f, zip != null, l.pinned, l.recent));
                    s = AnonReports.send(a, issueTitle, report, zip);
                } catch (java.io.IOException e) {
                    code = e instanceof AnonReports.HttpError ? ((AnonReports.HttpError) e).code : 0;
                    msg = AnonReports.errorMessage(e);
                }
                AnonReports.Sent sent = s;
                int c = code;
                String m = msg;
                a.runOnUiThread(() -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    if (sent == null) {
                        BLog.log("anon_report_failed", "code", c);
                        enableButtons(true);
                        error.setText(m);
                        error.setVisibility(android.view.View.VISIBLE);
                        scroll.post(() -> scroll.smoothScrollTo(0, error.getBottom()));
                        return;
                    }
                    draft = null;
                    dialog.dismiss();
                    if (a instanceof MainActivity) ((MainActivity) a).refreshReplies();   // a dialog: no onResume follows
                    android.text.SpannableStringBuilder msg2 = new android.text.SpannableStringBuilder("Report sent as: ");
                    appendLink(msg2, sent.url.replaceFirst("^https?://", ""), () -> {
                        try {
                            a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(sent.url)));
                        } catch (android.content.ActivityNotFoundException e) {
                            android.widget.Toast.makeText(a, "No app can open GitHub links",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                    msg2.append("\n\nReplies will show on the dashboard.");
                    String logNow = AnonReports.logOutcome(sent.log);
                    if (logNow != null) msg2.append("\n\n").append(logNow);
                    android.widget.TextView sentText = text(15, Widgets.TEXT);
                    sentText.setText(msg2);
                    sentText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    sentText.setPadding(dp(24), dp(8), dp(24), dp(4));
                    android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                            .setTitle("Report sent")
                            .setView(sentText)
                            .setPositiveButton("OK", null)
                            .create();
                    Widgets.showStyled(d);
                });
            }, "anon-report").start();
        }

        /** The built zip is only for this form's send: gone once the form closes, sent or not. */
        private void deleteZip() {
            if (logZip == null) return;
            java.util.concurrent.FutureTask<java.io.File> z = logZip;
            new Thread(() -> {
                try {
                    z.get().delete();
                } catch (Exception ignored) { }
            }, "report-log-delete").start();
        }

        /** Off while a send runs, so it can't go twice or be closed before the result shows. */
        private void enableButtons(boolean on) {
            for (android.view.View v : new android.view.View[]{sendBtn, cancelBtn}) {
                v.setEnabled(on);
                v.setAlpha(on ? 1f : 0.5f);
            }
            githubLink.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
            sending.setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE);
            dialog.setCancelable(on);
        }

        /**
         * "See it": the log as the main send will carry it. Anonymous: the readable log, exactly as sent. Otherwise
         * what a browser's Log field gets (the digest, with a placeholder file name for the zip).
         */
        private void showDigest() {
            check();
            boolean was = f.includeLog;
            f.includeLog = true;
            LogFiles.Lines l = LogFiles.lines(a);
            boolean anonSend = AnonReports.enabled();
            String d = logFor(BuildConfig.REPO_URL, f, anonSend,
                    fullLogNote(LogExport.fileName(System.currentTimeMillis()), "Downloads"), l.pinned, l.recent);
            f.includeLog = was;
            if (d.isEmpty()) showText("Log", "Nothing to show yet: the log is empty, or the report leaves no room.");
            else if (anonSend) showText("Log in this report", d);
            else showText("Log digest", "In a browser the Log field gets this digest, and the whole log is saved to "
                    + "Downloads as a zip to attach. In the GitHub app it gets the recent log instead, as much as GitHub "
                    + "allows (around 500 lines).\n\n" + d);
        }

        /** A read-only text in its own scrolling dialog (selectable, so it can be copied). */
        private void showText(String title, String s) {
            android.widget.TextView t = text(12, Widgets.TEXT);
            t.setText(s);
            t.setTextIsSelectable(true);
            t.setPadding(dp(20), dp(8), dp(20), dp(8));
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(t);
            android.app.AlertDialog dd = new android.app.AlertDialog.Builder(a)
                    .setTitle(title)
                    .setView(sv)
                    .setPositiveButton("OK", null)
                    .create();
            Widgets.showStyled(dd);
        }

        /** Appends a tappable blue `label` that runs `onTap`, for inline links such as "More ›". */
        private void appendLink(android.text.SpannableStringBuilder sb, String label, Runnable onTap) {
            int start = sb.length();
            sb.append(label);
            sb.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(android.view.View w) { onTap.run(); }
                @Override public void updateDrawState(android.text.TextPaint p) { p.setColor(Widgets.BLUE); }
            }, start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        /** Copies the dialog into the fields; false (and says what's missing) while a required one is empty. */
        private boolean submit() {
            tried = true;
            android.view.View first = check();
            if (first != null) {                      // show the first one that still needs an answer
                scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, first.getTop() - dp(40))));
                return false;
            }
            if (needsScanFirst(f)) {                   // no stats to back the result: ask first
                confirmNoStats();
                return false;
            }
            proceed();
            return true;
        }

        /**
         * Copies the dialog into the fields and outlines every required one that's still empty in red; the line
         * at the bottom lists them once a send was tapped. Returns the first missing field's view, or null when the report is complete.
         */
        private android.view.View check() {
            if (title == null || network == null || result == null || details == null || error == null) return null;
            f.title = title.getText().toString().trim();
            if (scanner != null) f.scanner = (String) scanner.getTag();
            f.network = (String) network.getTag();
            f.client = client.getText().toString().trim();
            f.result = (String) result.getTag();
            f.details = (measured + details.getText().toString().trim()).trim();   // the report's text, as before
            if (includeLog != null) f.includeLog = includeLog.isChecked();
            java.util.List<String> missing = new java.util.ArrayList<>();
            android.view.View first = null;
            Object[][] req = {{"Title", title, f.title}, {"Scanner", scanner, f.scanner}, {"Network", network, f.network},
                    {"Result", result, f.result}};
            for (Object[] r : req) {
                android.view.View v = (android.view.View) r[1];
                if (v == null) continue;                  // the scanner shown as detected, not picked
                boolean empty = ((String) r[2]).isEmpty();
                mark(v, empty);
                if (empty) {
                    missing.add((String) r[0]);
                    if (first == null) first = v;
                }
            }
            error.setText(missing.isEmpty() ? "" : "Still needed: " + String.join(", ", missing));
            error.setVisibility(missing.isEmpty() || !tried ? android.view.View.GONE : android.view.View.VISIBLE);
            return first;
        }
    }
}

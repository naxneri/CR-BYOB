package com.crbyob.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Anonymous device reports: the app posts to the report Worker (which files the GitHub issue), keeps a random
 * device ID and the list of its own reports, and later looks for developer replies. Only the tag (a hash of the
 * ID) ever appears on GitHub. The ID and tag are never logged.
 */
final class AnonReports {
    private AnonReports() {}

    /** log: the Worker's answer about the private log ("stored", "refused: why"), or null when none was sent. */
    static final class Sent { int number; String url; String log; }

    /** The largest log zip the Worker stores; a bigger one isn't sent and the report keeps the long tail. */
    static final long MAX_LOG = 5L * 1024 * 1024;
    /** A report or reply with the log zip after the JSON (crbyob-report-worker's framed request). */
    private static final String FRAMED = "application/vnd.crbyob.report";

    /** code 0 = no connection or timeout, otherwise the HTTP status; serverError = the Worker's {error}, or null. */
    static final class HttpError extends IOException {
        final int code;
        final String serverError;
        HttpError(int code, String msg) { this(code, msg, null); }
        HttpError(int code, String msg, String serverError) { super(msg); this.code = code; this.serverError = serverError; }
    }

    private static final String K_ID = "anon_id", K_BOT = "anon_bot", K_REPORTS = "anon_reports",
            K_LAST = "anon_last_check";
    /** Held around every read-modify-write of anon_reports (the background check merges under it too). */
    static final Object LOCK = new Object();
    /** Minimum gap between background checks: while the dashboard stays open, and when it comes back up. */
    static final long CHECK_INTERVAL_MS = 15L * 60 * 1000, RESUME_GAP_MS = 60_000;

    static boolean enabled() { return !BuildConfig.REPORT_WORKER_URL.isEmpty(); }

    static String newId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b, 32);
    }

    private static String hex(byte[] b, int chars) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.substring(0, chars);
    }

    static String deviceId(SharedPreferences p) {
        String id = p.getString(K_ID, null);
        if (id == null) {
            id = newId();
            p.edit().putString(K_ID, id).apply();
        }
        return id;
    }

    static String tagOf(String id) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)), 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is guaranteed on every Android
        }
    }

    static boolean isMine(JSONObject comment, String bot, String tag) {
        JSONObject u = comment.optJSONObject("user");
        String head = "**Reply from the reporter** (tag `" + tag + "`", body = comment.optString("body");
        return u != null && bot != null && bot.equals(u.optString("login"))
                && (body.startsWith(head + ")") || body.startsWith(head + ", full log Id: `"));
    }

    /** True when this phone's reply carried a log the Worker stored (its header names it). */
    static boolean carriedLog(String body) {
        int nl = body.indexOf('\n');
        return (nl < 0 ? body : body.substring(0, nl)).contains(", full log Id: `");
    }

    /** How long ago, for "Refreshed … ago": just now, then seconds, minutes, hours, days. */
    static String ago(long ms) {
        if (ms < 2_000) return "just now";
        if (ms < 60_000) return (ms / 1000) + " s ago";
        long min = ms / 60_000;
        if (min < 60) return min + " min ago";
        if (min < 24 * 60) return (min / 60) + " h ago";
        long d = min / (24 * 60);
        return d + (d == 1 ? " day ago" : " days ago");
    }

    /** The same reply text, ignoring the zero-width joiners the Worker puts after each @ and surrounding space. */
    static boolean sameReply(String posted, String typed) {
        return posted.replace("\u200D", "").trim().equals(typed.replace("\u200D", "").trim());
    }

    /** Reports that still take replies (not closed, locked or deleted), newest first. */
    static java.util.List<JSONObject> openReports(JSONArray a) {
        java.util.List<JSONObject> open = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r != null && !r.optBoolean("closed") && !r.optBoolean("locked") && !r.optBoolean("gone")) open.add(r);
        }
        java.util.Collections.sort(open, (x, y) -> Integer.compare(y.optInt("n"), x.optInt("n")));
        return open;
    }

    /** What to tell the person about the private log, from the Worker's answer; null when it was stored (or none sent). */
    static String logOutcome(String answer) {
        if (answer == null || answer.equals("stored")) return null;
        String why = answer.startsWith("refused: ") ? answer.substring(9) : answer;
        String detail;
        switch (why) {
            case "too big": detail = "it was too big"; break;
            case "storage full": detail = "the report server's storage is full right now"; break;
            case "storage": detail = "the report server couldn't store it"; break;
            default: detail = why;
        }
        return "The full log wasn't stored: " + detail + ". The report has the recent lines; if the maintainer asks "
                + "for more, Export log under Events saves it.";
    }

    /** An upload size for people on mobile data: KB under a megabyte (so a small log never reads "0.0 MB"). */
    static String size(long bytes) {
        return bytes < 1024 * 1024 ? Math.max(1, Math.round(bytes / 1024.0)) + " KB"
                : String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** The frame before the zip: the JSON's length (4 bytes, big-endian), then the JSON. */
    static byte[] frameHeader(byte[] json) {
        return java.nio.ByteBuffer.allocate(4 + json.length).putInt(json.length).put(json).array();
    }

    /**
     * Writes the redacted log archive (the same as Export log) to a new file in the cache, for sending with a report
     * or reply. Slow for a big log: call it off the UI thread. Delete the file once it's sent.
     */
    static File logZip(Context c) throws IOException {
        File dir = new File(c.getCacheDir(), "report-log");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File[] old = dir.listFiles();
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        if (old != null) for (File o : old) if (o.lastModified() < hourAgo) o.delete();   // left by a send that never went
        File f = File.createTempFile("crbyob-log-", ".zip", dir);
        try (OutputStream os = new FileOutputStream(f)) {
            LogFiles.write(c, os);
        } catch (IOException | RuntimeException e) {
            f.delete();
            throw e;
        }
        return f;
    }

    static boolean isDeveloper(JSONObject comment, String bot, String tag) {
        String a = comment.optString("author_association");
        JSONObject u = comment.optJSONObject("user");
        boolean isBot = u != null && "Bot".equals(u.optString("type"));
        // a reporter's reply (this phone's or, with any tag, posted by the bot) is never the developer's
        if (isMine(comment, bot, tag) || (isBot && comment.optString("body").startsWith("**Reply from the reporter**")))
            return false;
        return a.equals("OWNER") || a.equals("MEMBER") || a.equals("COLLABORATOR") || isBot;
    }

    static String errorMessage(IOException e) {
        return e instanceof HttpError ? errorMessage(((HttpError) e).code, ((HttpError) e).serverError) : errorMessage(0, null);
    }

    static String errorMessage(int httpCode, String serverError) {
        if (httpCode == 0) return "Couldn't reach the report server. Check the connection and try again.";
        if (httpCode == 429) return "Too many reports from this network just now. Try again in a minute.";
        if (httpCode == 400 || httpCode == 403)          // permanent, so no "try again": the Worker's reason says why
            return serverError != null && !serverError.isEmpty()
                    ? Character.toUpperCase(serverError.charAt(0)) + serverError.substring(1) + "."
                    : "The report server refused this (" + httpCode + ").";
        return "The report server answered " + httpCode + ". Try again later.";
    }

    static JSONArray reports(SharedPreferences p) {
        try {
            return new JSONArray(p.getString(K_REPORTS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void saveReports(SharedPreferences p, JSONArray a) {
        p.edit().putString(K_REPORTS, a.toString()).apply();
    }

    static boolean anyUnread(SharedPreferences p) {
        JSONArray a = reports(p);
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && a.optJSONObject(i).optBoolean("unread")) return true;
        return false;
    }

    /** The issue number when exactly one report is unread, else 0. */
    static int onlyUnread(JSONArray a) {
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || !r.optBoolean("unread")) continue;
            if (n != 0) return 0;
            n = r.optInt("n");
        }
        return n;
    }

    private static boolean anyOpen(SharedPreferences p) {
        JSONArray a = reports(p);
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && !a.optJSONObject(i).optBoolean("closed")) return true;
        return false;
    }

    /** Records the issue's state (a reopened issue counts as open again); true when it has comments not yet read. */
    static boolean applyIssue(JSONObject report, int httpCode, JSONObject issue) {
        try {
            if (httpCode == 404 || httpCode == 410) report.put("gone", true); // 403 is GitHub's rate limit, not a deleted issue
            if (issue != null) report.put("closed", "closed".equals(issue.optString("state")))
                    .put("title", issue.optString("title"))         // the list shows it; the developer may edit it
                    .put("reason", issue.isNull("state_reason") ? "" : issue.optString("state_reason"))
                    .put("locked", issue.optBoolean("locked"));
            return issue != null && issue.optInt("comments") > report.optInt("seen");
        } catch (JSONException e) {
            return false;
        }
    }

    /** The issue title for the list, without the "[Device] " every report title starts with; "" before it's known. */
    static String title(JSONObject r) {
        String t = r.optString("title").trim();
        return t.startsWith("[Device] ") ? t.substring(9).trim() : t;
    }

    /** The status badge: Open, Locked, or how GitHub closed it (Resolved, Not planned, Duplicate, Closed). */
    static String status(JSONObject r) {
        if (!r.optBoolean("closed")) return r.optBoolean("locked") ? "Locked" : "Open";
        switch (r.optString("reason")) {
            case "completed": return "Resolved";
            case "not_planned": return "Not planned";
            case "duplicate": return "Duplicate";
            default: return "Closed";
        }
    }

    /** Deleted issues leave the list: there is nothing left to show for them. */
    static void dropGone(JSONArray a) {
        for (int i = a.length() - 1; i >= 0; i--)
            if (a.optJSONObject(i) != null && a.optJSONObject(i).optBoolean("gone")) a.remove(i);
    }

    static void applyComments(JSONObject report, JSONArray comments, String bot, String tag) {
        long newest = 0;
        for (int i = 0; i < comments.length(); i++) {
            JSONObject c = comments.optJSONObject(i);
            if (c != null && isDeveloper(c, bot, tag)) newest = Math.max(newest, c.optLong("id"));
        }
        try {
            report.put("seen", comments.length()).put("newest", newest)
                    .put("unread", newest > report.optLong("dev"));
        } catch (JSONException ignored) { }
    }

    static void markRead(JSONObject report) {
        try {
            report.put("unread", false).put("dev", report.optLong("newest"));
        } catch (JSONException ignored) { }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    /** Files the report; with `zip` (at most MAX_LOG), the whole log goes privately to the Worker with it. */
    static Sent send(Context c, String title, String body, File zip) throws IOException {
        SharedPreferences p = prefs(c);
        try {
            JSONObject r = new JSONObject(http(BuildConfig.REPORT_WORKER_URL + "/report",
                    new JSONObject().put("id", deviceId(p)).put("title", title).put("body", body).toString(), false, zip));
            Sent s = new Sent();
            s.number = r.getInt("number");
            s.url = r.getString("url");
            s.log = r.optString("log", null);
            synchronized (LOCK) {
                JSONArray all = reports(p);
                all.put(new JSONObject().put("n", s.number).put("title", title).put("seen", 0).put("dev", 0).put("newest", 0)
                        .put("closed", false).put("unread", false));
                p.edit().putString(K_BOT, r.optString("bot")).putString(K_REPORTS, all.toString()).apply();
            }
            // no issue number: a later log on a GitHub-account report would link the two
            BLog.log("anon_report_sent", "log", s.log == null ? "none" : s.log);
            return s;
        } catch (JSONException e) {
            throw new HttpError(502, "bad answer from the report server");
        }
    }

    /** Posts the reply; with `zip`, the whole log goes privately with it. Returns the Worker's log answer, or null. */
    static String reply(Context c, int issue, String text, File zip) throws IOException {
        try {
            JSONObject r = new JSONObject(http(BuildConfig.REPORT_WORKER_URL + "/reply",
                    new JSONObject().put("id", deviceId(prefs(c))).put("issue", issue).put("body", text).toString(), false, zip));
            String log = r.optString("log", null);
            BLog.log("anon_reply_sent", "log", log == null ? "none" : log);
            return log;
        } catch (JSONException e) {
            throw new HttpError(502, "bad answer from the report server");
        }
    }

    // shortcut: first 100 comments only, page if a report thread ever gets that long
    static JSONArray comments(int issue) throws IOException {
        try {
            return new JSONArray(http(issueUrl(issue) + "/comments?per_page=100", null, true, null));
        } catch (JSONException e) {
            throw new HttpError(502, "bad answer from GitHub");
        }
    }

    static JSONObject issue(int n) throws IOException {
        try {
            return new JSONObject(http(issueUrl(n), null, true, null));
        } catch (JSONException e) {
            throw new HttpError(502, "bad answer from GitHub");
        }
    }

    private static String issueUrl(int n) {
        String u = BuildConfig.REPO_URL;
        return "https://api.github.com/repos/" + u.substring(u.indexOf("github.com/") + 11) + "/issues/" + n;
    }

    /**
     * POSTs json (or GETs when null), framed with the zip after it when there is one; returns the body of a 2xx
     * answer, else throws HttpError.
     */
    private static String http(String url, String json, boolean github, File zip) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(zip != null ? 60_000 : 15_000);   // the Worker stores the zip before it answers
            c.setRequestProperty("User-Agent", "CR-BYOB/" + AppVersion.NAME);
            if (github) {
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                c.setRequestProperty("Cache-Control", "no-cache");   // asks any cache on the way for a fresh answer
            }
            if (json != null && zip != null) {
                byte[] head = frameHeader(json.getBytes(StandardCharsets.UTF_8));
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", FRAMED);
                // a declared length streams the upload and lets the Worker read it natively (within its CPU limit)
                c.setFixedLengthStreamingMode((long) head.length + zip.length());
                try (OutputStream o = c.getOutputStream(); InputStream in = new FileInputStream(zip)) {
                    o.write(head);
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                }
            } else if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new HttpError(code, "HTTP " + code, serverError(c));
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } catch (HttpError e) {
            throw e;
        } catch (IOException e) {
            throw new HttpError(0, String.valueOf(e));
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The {error} text of the Worker's JSON error answer, or null. */
    private static String serverError(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) {
            return in == null ? null : new JSONObject(readAll(in)).optString("error", null);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** When at least minGapMs passed since the last check; calls onChanged on the UI thread when a report changed. */
    static void maybeCheck(Activity a, long minGapMs, Runnable onChanged) {
        SharedPreferences p = prefs(a);
        if (!enabled() || !anyOpen(p)    // none open: don't create the ID
                || System.currentTimeMillis() - p.getLong(K_LAST, 0) < minGapMs) return;
        p.edit().putLong(K_LAST, System.currentTimeMillis()).apply();
        new Thread(() -> {
            JSONArray all = reports(p);
            java.util.List<JSONObject> changed = new java.util.ArrayList<>();
            String bot = p.getString(K_BOT, null), tag = tagOf(deviceId(p));
            for (int i = 0; i < all.length(); i++) {
                JSONObject r = all.optJSONObject(i);
                if (r == null || r.optBoolean("closed")) continue;
                int n = r.optInt("n");
                String was = r.toString();
                try {
                    int code = 200;
                    JSONObject issue = null;
                    try {
                        issue = issue(n);
                    } catch (HttpError e) {
                        if (e.code != 404 && e.code != 410) throw e; // transient (incl. 403 rate limit): retry next time
                        code = e.code;
                    }
                    if (applyIssue(r, code, issue)) applyComments(r, comments(n), bot, tag);
                } catch (IOException e) {
                    BLog.log("anon_check_failed", "err", String.valueOf(e));
                }
                if (!r.toString().equals(was)) changed.add(r);
            }
            if (changed.isEmpty()) return;
            // merge into a fresh read: the UI may have sent or marked a report read during the network calls
            synchronized (LOCK) {
                JSONArray fresh = reports(p);
                boolean any = false;
                for (int i = 0; i < fresh.length(); i++) {
                    JSONObject f = fresh.optJSONObject(i);
                    for (JSONObject r : changed) {
                        if (f == null || f.optInt("n") != r.optInt("n")) continue;
                        try {
                            f.put("seen", r.optInt("seen")).put("newest", r.optLong("newest"))
                                    .put("closed", f.optBoolean("closed") || r.optBoolean("closed"))
                                    .put("reason", r.optString("reason")).put("locked", r.optBoolean("locked"))
                                    .put("title", r.optString("title", f.optString("title")))
                                    .put("gone", r.optBoolean("gone"))
                                    .put("unread", r.optLong("newest") > f.optLong("dev"));
                            any = true;
                        } catch (JSONException ignored) { }
                    }
                }
                if (!any) return;
                dropGone(fresh);
                saveReports(p, fresh);
            }
            a.runOnUiThread(onChanged);
        }, "anon-check").start();
    }
}

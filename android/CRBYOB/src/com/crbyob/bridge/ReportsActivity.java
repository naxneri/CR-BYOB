package com.crbyob.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * This phone's anonymous reports, as two pages: the list (no EXTRA_ISSUE), and one report's thread with the
 * developer's comments and this phone's replies, read from GitHub, and a box to answer through the report
 * Worker. A thread always opens on top of the list, so Back leads to the list. Opening a thread marks it read.
 */
public final class ReportsActivity extends Activity {
    private static final String EXTRA_ISSUE = "issue", EXTRA_OPEN = "open", EXTRA_ATTACH = "attach_log";

    /** The list of reports. */
    static void openList(Context c) {
        c.startActivity(new Intent(c, ReportsActivity.class));
    }

    /** One report's thread, over the list. */
    static void open(Context c, int issue) {
        open(c, issue, false);
    }

    /** One report's thread, over the list; with attachLog, the reply box starts with the latest logs attached. */
    static void open(Context c, int issue, boolean attachLog) {
        c.startActivity(new Intent(c, ReportsActivity.class).putExtra(EXTRA_OPEN, issue).putExtra(EXTRA_ATTACH, attachLog));
    }

    private int issue;                   // 0 on the list page
    private SharedPreferences prefs;
    private LinearLayout list;           // the list page's rows, or the thread's messages
    private TextView pill, note, status, error, web;   // pill: status badge; note: why there's no reply box
    private TextView heading;            // the bar's title: the report's title once known, else "Report #N"
    private EditText box;
    private TextView send;               // a styled label, not a Button: no theme insets or shadow to clip
    private View composer;               // the reply box and Send, pinned under the messages
    private LinearLayout sending;        // spinner + "Sending…" while a reply goes out
    private boolean sendingNow;
    private ScrollView scroll;
    private TextView attach, logPill;    // "Attach most recent logs", and the attached log's pill (tap ✕ to remove)
    private java.io.File logZip;         // the attached log, null when none
    private int logBuild;                // bumped by each attach and remove, so a stale build is dropped
    // replies sent from here that GitHub's list doesn't show yet (it serves the list cached for up to a minute):
    // {text, "log" when a log went with it}; shown as "You · just now" until a load finds them
    private final java.util.List<String[]> pending = new java.util.ArrayList<>();
    private JSONObject lastIssue;        // the last thread loaded, to redraw it with a pending reply at once
    private JSONArray lastComments;
    private TextView refreshed;          // "Refreshed 2 min ago · Refresh now"
    private long loadedAt;               // when the thread last loaded, 0 = not yet
    private boolean loading;
    private final Runnable tick = new Runnable() {   // keeps "… ago" current while the page shows
        @Override public void run() {
            showRefreshed();
            scroll.removeCallbacks(this);     // one tick at a time, however often onResume runs
            scroll.postDelayed(this, 1_000);
        }
    };

    private int dp(float v) {
        return Widgets.dp(this, v);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyLockScreenMode(this);
        getWindow().setStatusBarColor(Widgets.BG);
        getWindow().setNavigationBarColor(Widgets.BG);
        Widgets.fitSystemBars(this);
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        issue = getIntent().getIntExtra(EXTRA_ISSUE, 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Widgets.BG);
        root.addView(bar(issue == 0 ? "Your reports" : "Report #" + issue));
        View rule = new View(this);
        rule.setBackgroundColor(Widgets.LINE);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(24));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (issue == 0) {
            TextView about = text(13, Widgets.SUB);
            about.setText("Only reports sent anonymously from this app are listed and checked for replies here. "
                    + "Check GitHub for replies on personally-submitted reports.");
            about.setPadding(dp(4), 0, dp(4), dp(12));
            body.addView(about);
            body.addView(list, wide());
        } else {
            buildThread(body);
            root.addView(composer, wide());
        }
        setContentView(root);

        int open = getIntent().getIntExtra(EXTRA_OPEN, 0);
        if (open > 0 && b == null)     // asked for one report: the list goes under it, for Back
            startActivity(new Intent(this, ReportsActivity.class).putExtra(EXTRA_ISSUE, open)
                    .putExtra(EXTRA_ATTACH, getIntent().getBooleanExtra(EXTRA_ATTACH, false)));
        if (issue != 0) {
            showClosed();
            load();
            if (b == null && getIntent().getBooleanExtra(EXTRA_ATTACH, false) && box.getVisibility() == View.VISIBLE) attachLog();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (issue != 0) scroll.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dropLog();   // an attached log that wasn't sent stays on the phone only as long as this screen
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (issue == 0) fillList();     // a thread may have marked a report read, or dropped a deleted one
        else tick.run();
    }

    /** ← Back, the page title, and (on a thread) "View on GitHub". */
    private View bar(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(8), dp(4));
        TextView back = text(22, Widgets.TEXT);
        back.setText("←");
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(17, Widgets.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setText(title);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading = t;
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (issue != 0) {
            web = text(14, Widgets.TEXT);
            web.setText("View on GitHub ↗");
            web.setBackground(Widgets.roundRect(Widgets.LINE, dp(10)));
            web.setPadding(dp(12), dp(8), dp(12), dp(8));
            web.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(BuildConfig.REPO_URL + "/issues/" + issue)));
                } catch (android.content.ActivityNotFoundException e) {
                    android.widget.Toast.makeText(this, "No app can open GitHub links", android.widget.Toast.LENGTH_LONG).show();
                }
            });
            bar.addView(web);
        }
        return bar;
    }

    // ------------------------------------------------------------------ list page

    /** One row per report, newest first: number, status badge, and a blue marker when a reply is unread. */
    private void fillList() {
        list.removeAllViews();
        JSONArray all = AnonReports.reports(prefs);
        if (all.length() == 0) {
            TextView none = text(15, Widgets.SUB);
            none.setText("No reports on this phone.");
            none.setPadding(dp(8), dp(8), dp(8), dp(8));
            list.addView(none);
            return;
        }
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (int i = all.length() - 1; i >= 0; i--) {
            JSONObject r = all.optJSONObject(i);
            if (r == null) continue;
            int n = r.optInt("n");
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(Widgets.roundRect(Widgets.CARD, dp(12)));
            row.setForeground(getDrawable(ripple.resourceId));
            // the title on top (or "Report #N" until it's known), then number, status badge and "New replies"
            String title = AnonReports.title(r);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView head = text(16, Widgets.TEXT);
            head.setText(title.isEmpty() ? "Report #" + n : title);
            head.setMaxLines(2);
            head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(head);
            LinearLayout meta = new LinearLayout(this);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            meta.setPadding(0, dp(6), 0, 0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.rightMargin = dp(10);
            if (!title.isEmpty()) {
                TextView num = text(14, Widgets.SUB);
                num.setText("#" + n);
                meta.addView(num, blp);
            }
            TextView badge = new TextView(this);
            String s = AnonReports.status(r);
            Widgets.badge(badge, s, statusColor(s));
            meta.addView(badge, blp);
            if (r.optBoolean("unread")) {
                TextView u = text(13, Widgets.BLUE);
                u.setText("● New replies");
                meta.addView(u, blp);
            }
            col.addView(meta);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView chev = text(18, Widgets.SUB);
            chev.setText("›");
            row.addView(chev);
            row.setOnClickListener(v -> startActivity(new Intent(this, ReportsActivity.class).putExtra(EXTRA_ISSUE, n)));
            LinearLayout.LayoutParams lp = wide();
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
    }

    /** Badge colors, GitHub's where it has one: open green, resolved purple, locked amber, deleted red. */
    static int statusColor(String status) {
        switch (status) {
            case "Open": return Widgets.GREEN;
            case "Resolved": return Widgets.PURPLE;
            case "Locked": return Widgets.AMBER;
            case "Deleted": return Widgets.RED;
            default: return Widgets.GREY;   // Not planned, Duplicate, Closed
        }
    }

    // ------------------------------------------------------------------ thread page

    /** This report as stored, or null once it's dropped because GitHub deleted the issue. */
    private JSONObject record() {
        JSONArray all = AnonReports.reports(prefs);
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && r.optInt("n") == issue) return r;
        }
        return null;
    }

    private void buildThread(LinearLayout body) {
        body.addView(list, wide());
        status = text(14, Widgets.SUB);
        status.setPadding(0, dp(8), 0, dp(4));
        body.addView(status);

        // the reply box, then #N, the status badge and Send, pinned under the messages so they ride up with the
        // keyboard; a closed report keeps the bar for its badge, without box or Send
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(Widgets.CARD);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        refreshed = text(13, Widgets.SUB);
        refreshed.setPadding(0, 0, 0, dp(8));
        refreshed.setOnClickListener(v -> load());
        c.addView(refreshed, wide());
        box = new EditText(this);
        box.setTextColor(Widgets.TEXT);
        box.setHintTextColor(Widgets.GREY);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        android.graphics.drawable.GradientDrawable boxBg = Widgets.roundRect(0x00000000, dp(8));   // outlined, not
        boxBg.setStroke(Math.max(1, dp(1.5f)), Widgets.GREY);                                      // filled like a bubble
        box.setBackground(boxBg);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int n, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int bf, int n) { }
            @Override public void afterTextChanged(android.text.Editable s) { updateSend(); }
        });
        box.setHint("Reply (public on GitHub)");
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setSingleLine(false);
        box.setMinLines(2);
        box.setMaxLines(6);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10_000)});  // Worker's limit
        c.addView(box, wide());
        // the log row: a quick action, or the attached log as a pill
        LinearLayout logRow = new LinearLayout(this);
        logRow.setGravity(Gravity.CENTER_VERTICAL);
        logRow.setPadding(0, dp(6), 0, 0);
        attach = text(14, Widgets.BLUE);
        attach.setText("\uD83D\uDCCE Attach most recent logs");
        attach.setPadding(0, dp(4), dp(8), dp(4));
        attach.setOnClickListener(v -> attachLog());
        logRow.addView(attach);
        logPill = text(13, Widgets.TEXT);
        logPill.setBackground(Widgets.roundRect(Widgets.LINE, dp(14)));
        logPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        logPill.setVisibility(View.GONE);
        logPill.setOnClickListener(v -> dropLog());
        logRow.addView(logPill);
        c.addView(logRow, wide());
        error = text(14, Widgets.RED);
        error.setPadding(0, dp(8), 0, 0);
        error.setVisibility(View.GONE);
        c.addView(error);

        LinearLayout row = new LinearLayout(this);               // #N [badge] note … Sending… [Send]
        row.setPadding(0, dp(8), 0, 0);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = text(14, Widgets.SUB);
        num.setText("#" + issue);
        num.setPadding(0, 0, dp(10), 0);
        meta.addView(num);
        pill = new TextView(this);
        meta.addView(pill);
        note = text(13, Widgets.SUB);
        note.setPadding(dp(10), 0, 0, 0);
        meta.addView(note);
        row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sending = new LinearLayout(this);
        sending.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Widgets.BLUE));
        sending.addView(spin);
        TextView st = text(14, Widgets.SUB);
        st.setText("Sending…");
        st.setPadding(dp(8), 0, 0, 0);
        sending.addView(st);
        sending.setVisibility(View.GONE);
        sending.setPadding(0, 0, dp(10), 0);
        row.addView(sending);
        send = text(15, Widgets.BG);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setGravity(Gravity.CENTER);
        send.setBackground(Widgets.roundRect(Widgets.BLUE, dp(10)));
        send.setPadding(dp(20), dp(10), dp(20), dp(10));
        send.setText("Send");
        row.addView(send);
        send.setOnClickListener(v -> send());
        c.addView(row, wide());
        composer = c;
        updateSend();
    }

    /** Closed, locked and deleted issues take no replies: the badge and a line say why, and the box goes. */
    private void showClosed() {
        JSONObject r = record();
        String s = r == null ? "Deleted" : AnonReports.status(r);
        boolean shut = !s.equals("Open");
        if (r != null && !AnonReports.title(r).isEmpty()) heading.setText(AnonReports.title(r));   // known after the first read
        Widgets.badge(pill, s, statusColor(s));
        note.setText(r == null ? "Removed from GitHub." : "No longer takes replies.");
        note.setVisibility(shut ? View.VISIBLE : View.GONE);
        if (shut) error.setVisibility(View.GONE);         // a refused reply's error only repeats the badge
        web.setVisibility(r == null ? View.GONE : View.VISIBLE);
        box.setVisibility(shut ? View.GONE : View.VISIBLE);   // the bar stays: it carries the badge
        send.setVisibility(shut ? View.GONE : View.VISIBLE);
        if (shut) dropLog();
        attach.setVisibility(shut || logPill.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        updateSend();
    }

    /**
     * Re-reads the issue (it may have been reopened, closed, locked or deleted since the last check) and its
     * comments, marks the report read, and shows the developer's comments and this phone's replies.
     */
    private void load() {
        if (loading) return;
        loading = true;
        showRefreshed();
        status.setTextColor(Widgets.SUB);
        status.setText("Loading replies…");
        status.setVisibility(View.VISIBLE);
        new Thread(() -> {
            JSONObject iss = null;
            JSONArray cs = null;
            int code = 0;
            try {
                iss = AnonReports.issue(issue);
                cs = AnonReports.comments(issue);
            } catch (IOException e) {
                code = e instanceof AnonReports.HttpError ? ((AnonReports.HttpError) e).code : 0;
            }
            String bot = prefs.getString("anon_bot", null), tag = AnonReports.tagOf(AnonReports.deviceId(prefs));
            synchronized (AnonReports.LOCK) {
                JSONArray all = AnonReports.reports(prefs);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject r = all.optJSONObject(i);
                    if (r == null || r.optInt("n") != issue) continue;
                    AnonReports.applyIssue(r, iss != null ? 200 : code, iss);   // reopened: background checks resume
                    if (cs != null) {
                        AnonReports.applyComments(r, cs, bot, tag);
                        AnonReports.markRead(r);
                    }
                }
                AnonReports.dropGone(all);
                AnonReports.saveReports(prefs, all);
            }
            JSONArray got = cs;
            JSONObject report = iss;
            int c = code;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                if (got != null) loadedAt = System.currentTimeMillis();
                showRefreshed();
                showClosed();
                if (got == null) {
                    if (record() == null) {                    // deleted: the badge says so, an error would mislead
                        status.setVisibility(View.GONE);
                        return;
                    }
                    status.setTextColor(Widgets.RED);
                    status.setText(c == 0 ? "Couldn't reach GitHub. Check the connection and try again."
                            : "GitHub answered " + c + ". Try again later.");
                    return;
                }
                render(report, got, bot, tag);
            });
        }, "anon-thread").start();
    }

    /** "Refreshed 2 min ago · Refresh now" (the whole line taps), or "Refreshing…" while a load runs. */
    private void showRefreshed() {
        if (refreshed == null) return;
        if (loading) {
            refreshed.setText("Refreshing\u2026");
            return;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(
                loadedAt == 0 ? "Not loaded yet" : "Refreshed " + AnonReports.ago(System.currentTimeMillis() - loadedAt));
        sb.append(" \u00b7 ");
        int from = sb.length();
        sb.append("Refresh now");
        sb.setSpan(new android.text.style.ForegroundColorSpan(Widgets.BLUE), from, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        refreshed.setText(sb);
    }

    private static final String LOG_NOTE = "\n\n_\uD83D\uDCCE Full log sent privately_";

    /** The report itself (iss, when it loaded) first, then the replies, newest at the bottom and flashed. */
    private void render(JSONObject iss, JSONArray cs, String bot, String tag) {
        lastIssue = iss;
        lastComments = cs;
        list.removeAllViews();
        java.util.List<String> mineTexts = new java.util.ArrayList<>();
        String report = iss == null ? "" : iss.optString("body").trim();
        if (!report.isEmpty()) bubble(true, "Your report · " + localTime(iss.optString("created_at")), report, "report");
        View newest = null;
        for (int i = 0; i < cs.length(); i++) {             // GitHub lists them oldest first
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            boolean mine = AnonReports.isMine(c, bot, tag);
            if (!mine && !AnonReports.isDeveloper(c, bot, tag)) continue;
            JSONObject u = c.optJSONObject("user");
            String who = mine ? "You" : u != null ? u.optString("login") : "";
            String text = c.optString("body");
            if (mine) {                                       // drop the "Reply from the reporter (tag …)" header
                boolean withLog = AnonReports.carriedLog(text);
                int nl = text.indexOf('\n');
                text = nl < 0 ? "" : text.substring(nl + 1).trim();
                mineTexts.add(text);
                if (withLog) text += LOG_NOTE;
            }
            if (text.trim().isEmpty()) continue;
            newest = bubble(mine, who + " · " + localTime(c.optString("created_at")), text, "reply");
        }
        for (java.util.Iterator<String[]> it = pending.iterator(); it.hasNext(); ) {
            String[] p = it.next();
            boolean arrived = false;
            for (String m : mineTexts) arrived |= AnonReports.sameReply(m, p[0]);
            if (arrived) it.remove();
            else newest = bubble(true, "You · just now", p[0] + (p[1] != null ? LOG_NOTE : ""), "reply");
        }
        status.setVisibility(newest == null ? View.VISIBLE : View.GONE);
        status.setTextColor(Widgets.SUB);
        status.setText("No replies yet.");
        View flash = newest;
        scroll.post(() -> {                                   // newest at the bottom, like a chat
            scroll.fullScroll(View.FOCUS_DOWN);
            if (flash != null) highlight(flash);
        });
    }

    /**
     * A chat-style bubble: the reporter's own messages tinted blue and set in from the left, the developer's
     * grey and set in from the right, so who said what reads at a glance. A long text shows its start and a
     * "… Show full report" (or "reply") link at its end.
     */
    private View bubble(boolean mine, String header, String text, String what) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Widgets.roundRect(mine ? (Widgets.BLUE & 0x00FFFFFF) | 0x33000000 : Widgets.LINE, dp(10)));
        bubble.setPadding(dp(12), dp(8), dp(12), dp(10));
        TextView head = text(12, Widgets.SUB);
        head.setText(header);
        head.setPadding(0, 0, 0, dp(2));
        bubble.addView(head);
        TextView t = text(15, Widgets.TEXT);
        String start = preview(text);
        if (start == null) t.setText(Markdown.render(this, text, true));   // GitHub text is Markdown
        else {   // the start, then "… Show full report" as a link in the text itself
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(Markdown.render(this, start, true));
            while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) sb.delete(sb.length() - 1, sb.length());
            int from = sb.length() + 1;
            sb.append(" … Show full ").append(what);
            sb.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(View w) { t.setText(Markdown.render(ReportsActivity.this, text, true)); }
                @Override public void updateDrawState(android.text.TextPaint p) { p.setColor(Widgets.BLUE); }
            }, from, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.setText(sb);
        }
        t.setLinkTextColor(Widgets.BLUE);
        t.setTextIsSelectable(true);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());   // after: links stay tappable
        bubble.addView(t);
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(list.getChildCount() == 0 ? 4 : 8);
        if (mine) lp.leftMargin = dp(32);
        else lp.rightMargin = dp(32);
        list.addView(bubble, lp);
        return bubble;
    }

    /** The first lines of a long text (whole lines, up to 12 and about 800 characters), or null when it's short. */
    static String preview(String s) {
        int end = 0;
        for (int lines = 0; lines < 12 && end < s.length(); lines++) {
            int nl = s.indexOf('\n', end);
            int next = nl < 0 ? s.length() : nl + 1;
            if (next > 800) break;
            end = next;
        }
        if (end == 0) end = Math.min(s.length(), 800);       // one very long first line
        return end >= s.length() ? null : s.substring(0, end).trim();
    }

    /** Fades a blue outline in and out over the newest reply, like the docs do for the section they open at. */
    private void highlight(View v) {
        android.graphics.drawable.GradientDrawable box = Widgets.roundRect((Widgets.BLUE & 0x00FFFFFF) | 0x24000000, dp(10));
        box.setStroke(Math.max(1, dp(1.5f)), (Widgets.BLUE & 0x00FFFFFF) | 0x99000000);
        v.getOverlay().add(box);
        box.setAlpha(0);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(0, 255, 255, 0);   // in, hold, out: 1 s each
        a.setDuration(3000);
        a.addUpdateListener(x -> {          // sized each frame: the bubble may not be laid out when this starts
            box.setBounds(0, 0, v.getWidth(), v.getHeight());
            box.setAlpha((int) x.getAnimatedValue());
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) { v.getOverlay().remove(box); }
        });
        a.start();
    }

    /**
     * Builds the latest log (Export log's archive) off the UI thread and shows it as a pill; tapping the pill removes
     * it. A log over the Worker's limit shows why and isn't attached.
     */
    private void attachLog() {
        int build = ++logBuild;
        attach.setVisibility(View.GONE);
        pill("Preparing the log\u2026", Widgets.SUB);
        new Thread(() -> {
            java.io.File z = null;
            try {
                z = AnonReports.logZip(this);
            } catch (Exception e) {
                BLog.log("log_export_error", "err", String.valueOf(e));
            }
            java.io.File built = z;
            runOnUiThread(() -> {
                if (build != logBuild || isFinishing() || isDestroyed()) {   // removed, or replaced, while it was built
                    if (built != null) built.delete();
                    return;
                }
                if (built == null) {
                    pill("Couldn't prepare the log  \u2715", Widgets.RED);
                    return;
                }
                String mb = AnonReports.size(built.length());
                if (built.length() > AnonReports.MAX_LOG) {
                    built.delete();
                    pill("Log too big to send (" + mb + ")  \u2715", Widgets.RED);
                    return;
                }
                logZip = built;
                BLog.log("anon_reply_log_attached", "bytes", built.length());
                pill("\uD83D\uDCCE Latest log \u00b7 " + mb + " \u00b7 sent privately  \u2715", Widgets.TEXT);
                updateSend();
            });
        }, "reply-log").start();
    }

    private void pill(String s, int color) {
        logPill.setText(s);
        logPill.setTextColor(color);
        logPill.setVisibility(View.VISIBLE);
    }

    /** Removes the attached log (or drops one still being built) and brings the quick action back. */
    private void dropLog() {
        logBuild++;
        if (logZip != null) logZip.delete();
        logZip = null;
        if (logPill == null) return;
        logPill.setVisibility(View.GONE);
        attach.setVisibility(box.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        updateSend();
    }

    /** Posts the reply through the Worker; on failure the text stays in the box with the error under it. */
    private void send() {
        String typed = box.getText().toString().trim();
        java.io.File zip = logZip;
        if (typed.isEmpty() && zip == null) return;
        String text = typed.isEmpty() ? "Here's my latest log." : typed;   // the Worker needs some text
        sendingState(true);
        error.setVisibility(View.GONE);
        new Thread(() -> {
            int code = -1;
            String msg = null, logAnswer = null;
            try {
                logAnswer = AnonReports.reply(this, issue, text, zip);
            } catch (IOException e) {
                code = e instanceof AnonReports.HttpError ? ((AnonReports.HttpError) e).code : 0;
                msg = AnonReports.errorMessage(e);
            }
            int c = code;
            String m = msg, logNow = AnonReports.logOutcome(logAnswer);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sendingState(false);
                if (c >= 0) {
                    BLog.log("anon_reply_failed", "code", c);
                    error.setText(m);
                    error.setVisibility(View.VISIBLE);
                    if (c == 403) load();      // the Worker refuses closed and locked reports: show why, hide the box
                    return;
                }
                box.setText("");
                if (zip != null) dropLog();
                if (logNow != null) android.widget.Toast.makeText(this, logNow, android.widget.Toast.LENGTH_LONG).show();
                // shown at once; GitHub's list can lag a minute, so look again once its cache has expired
                pending.add(new String[]{text, zip != null && logNow == null ? "log" : null});
                if (lastComments != null) render(lastIssue, lastComments, prefs.getString("anon_bot", null),
                        AnonReports.tagOf(AnonReports.deviceId(prefs)));
                load();
                scroll.postDelayed(() -> {
                    if (!pending.isEmpty() && !isFinishing() && !isDestroyed()) load();
                }, 65_000);
            });
        }, "anon-reply").start();
    }

    /** GitHub's UTC timestamp ("2026-11-12T23:48:29Z") as the phone's local date and time, in its 12/24-hour style. */
    private String localTime(String iso) {
        java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            java.util.Date d = utc.parse(iso);
            return android.text.format.DateFormat.getMediumDateFormat(this).format(d) + ", "
                    + android.text.format.DateFormat.getTimeFormat(this).format(d);
        } catch (java.text.ParseException | NullPointerException e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }

    /** While a reply sends: Send and the box are off and dimmed, and the spinner shows. */
    private void sendingState(boolean on) {
        sendingNow = on;
        box.setEnabled(!on);
        logPill.setEnabled(!on);
        sending.setVisibility(on ? View.VISIBLE : View.GONE);
        updateSend();
    }

    /** Send works only with something in the box and no send running; otherwise it's off and dimmed. */
    private void updateSend() {
        if (send == null) return;
        boolean on = !sendingNow && (box.getText().toString().trim().length() > 0 || logZip != null);
        send.setEnabled(on);
        send.setAlpha(on ? 1f : 0.5f);
    }
}

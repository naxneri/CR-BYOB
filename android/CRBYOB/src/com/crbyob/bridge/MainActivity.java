package com.crbyob.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.crbyob.bridge.Widgets.*;

/**
 * Status dashboard (dark, readable from arm's length): a compact status header (state light, one-line
 * status, scanner and power), streams and estimated link quality while a PC is connected, the
 * network card (Wi-Fi Direct join details, Wi-Fi, Ethernet, which interface answers discovery),
 * plain-language events and the raw log. Also the USB_DEVICE_ATTACHED target.
 */
public final class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    // header
    private OrbView orb;
    private TextView heroText, heroSub, scanLine, powerLine, clientChip;
    private LinearLayout warnBox;
    private String warnKey = "";
    private PermissionGuide perms;
    private Button mirrorBtn;
    // Start/Stop: full width under the status, with a spinner while the bridge is still starting or stopping
    private LinearLayout startStop;
    private TextView ssText;
    private android.widget.ProgressBar ssSpin;
    private boolean ssWant;              // what the last tap asked for (true = running)
    private int ssSentAt = -1;           // BridgeService.commands when tapped; -1 = nothing in flight
    private long ssPendingUntil;         // bound on waiting for the service to pick the tap up
    private final Runnable ssPoll = new Runnable() {
        @Override
        public void run() {
            if (ssBusy()) {
                renderStartStop();
                h.postDelayed(this, 150);
            } else {
                refresh();               // settled: the whole dashboard at once, not on the next 1 s tick
            }
        }
    };
    private String mirrorHost;
    private final MirrorWatch uiWatch = new MirrorWatch(false);
    private android.widget.Switch autoSwitch;   // used while the dashboard is visible and no bridge runs     // PC heard broadcasting "OrbbecRTCServer" (Mobile connection dialog open)
    // waiting / streams / link
    private LinearLayout waitCard, streamsCard, linkCard;
    private TextView waitText;
    private final TextView[] stVal = new TextView[4], stSub = new TextView[4], stBadge = new TextView[4];
    private final SparkView[] stSpark = new SparkView[4];
    private TextView totalMbps;
    private BarsView linkBars;
    private TextView linkWord, linkDetail;
    // network
    private LinearLayout p2pBox, p2pEditor, wifiBox, ethBox;
    private TextView p2pSsid, p2pPass, p2pStatus, p2pHint, p2pDual, editPreview, editError;
    private Button p2pDualBtn;
    private LinearLayout p2pDualBanner;
    private EditText editSlug, editPass;
    private Button editBtn;
    private BarsView wifiBars;
    private TextView wifiLine, ethLine;
    private RadioGroup advGroup;
    private TextView advNote, advTitle;
    private P2pGroup offlineP2p;      // Wi-Fi Direct name/password while the bridge is stopped
    private String advKey = "";
    private boolean advUpdating;
    // events + log
    private LinearLayout eventsBox;
    private TextView eventsHeader, logView;
    private boolean eventsOpen = true, logOpen;
    private Dash.Snap last;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, dp(16)));
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(c, lp);
        if (title != null) c.addView(sectionTitle(title));
        return c;
    }

    private TextView sectionTitle(String title) {
        TextView t = text(13, SUB, true);
        t.setText(title.toUpperCase(Locale.US));
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(13, SUB, false);
        t.setText(s);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    /** Rounded colored pill, recolored by setChip. */
    private TextView chip() {
        TextView t = text(14, TEXT, true);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setVisibility(View.GONE);
        return t;
    }

    private void setChip(TextView t, String label, int color) {
        t.setText("●  " + label);
        t.setTextColor(color);
        t.setBackground(rounded((color & 0x00FFFFFF) | 0x33000000, dp(12)));
    }

    /** Devices on the Wi-Fi Direct group: red pill when none, green when joined. */
    private void setClientChip(TextView t, Dash.Snap s) {
        int n = s.p2pClients;
        String ips = s.p2pIps.isEmpty() ? "" : " (" + TextUtils.join(", ", s.p2pIps) + ")";
        if (n == 0) setChip(t, "No device connected to Wi-Fi Direct", RED);
        else setChip(t, n + (n == 1 ? " device" : " devices") + " connected to Wi-Fi Direct" + ips, GREEN);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(TEXT);
        b.setBackground(rounded(LINE, dp(10)));
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private EditText edit(int inputType) {
        EditText e = new EditText(this);
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setTypeface(Typeface.MONOSPACE);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setBackground(rounded(LINE, dp(8)));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        return e;
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private static LinearLayout.LayoutParams wrapLeft(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = margin;
        return lp;
    }

    private void copy(String what, String value) {
        if (value == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(what, value));
        Toast.makeText(this, what + " copied", Toast.LENGTH_SHORT).show();
    }

    /**
     * Developer option for a phone left on a USB hub: show the app over the lock screen and wake the screen when it
     * comes to the front. Off by default: for everyone else it makes the phone look unlocked and keeps waking back
     * into the app after the power button. Turn on (debug builds) with
     * `am start -n com.crbyob.bridge/.MainActivity --ez show_when_locked true` (false to turn off).
     */
    static void applyLockScreenMode(android.app.Activity a) {
        if (Build.VERSION.SDK_INT < 27) return;
        boolean on = a.getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("show_when_locked", false);
        a.setShowWhenLocked(on);
        a.setTurnScreenOn(on);
    }

    private static boolean strictModeOn;

    /** Debug builds: log (logcat tag StrictMode) any disk/network access on the UI thread and leaked resources. */
    private static void enableStrictMode() {
        if (!BuildConfig.DEBUG || strictModeOn) return;
        strictModeOn = true;
        android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls()
                .penaltyLog().build());
        android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects().detectLeakedRegistrationObjects().detectActivityLeaks()
                .penaltyLog().build());
    }

    @Override
    protected void onCreate(Bundle b) {
        enableStrictMode();
        super.onCreate(b);
        CrashRecord.install(this);
        BLog.init(this);
        if (b == null) BLog.log("app_version", "name", AppVersion.NAME, "code", AppVersion.CODE,
                "channel", AppVersion.CHANNEL, "commit", AppVersion.COMMIT, "debug", BuildConfig.DEBUG);
        perms = new PermissionGuide(this);
        if (BuildConfig.DEBUG && getIntent() != null && getIntent().hasExtra("show_when_locked"))   // dev toggle, persisted
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("show_when_locked", getIntent().getBooleanExtra("show_when_locked", false)).apply();
        applyLockScreenMode(this);
        getWindow().setStatusBarColor(BG);          // Android 14 and older; 15+ draws edge-to-edge (fitSystemBars)
        getWindow().setNavigationBarColor(BG);
        Widgets.fitSystemBars(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(24));
        scroll.addView(root);

        buildHeader(root);
        buildReportPrompt(root);
        buildCrashPrompt(root);
        buildActivity(root);
        root.removeView(linkCard);                                   // Link quality above Streams
        root.addView(linkCard, root.indexOfChild(streamsCard));
        buildSession(root);
        buildNetwork(root);
        buildExperimental(root);
        buildEvents(root);
        buildFooter(root);
        setContentView(scroll);

        // Runtime permissions: each one is explained before Android's prompt (PermissionGuide). Camera (USB
        // video-class devices need it), notifications and Location (connected SSID; Wi-Fi Direct before 13) on first
        // launch; Nearby Wi-Fi when Wi-Fi Direct is chosen; Camera again at Start if it was skipped.
        // only on a fresh launch: a recreated activity (e.g. after rotation in the back stack) must not replay
        // one-shot extras such as mirror_host (that reopened the viewer after Close)
        if (b == null) {
            perms.firstRun();
            handleIntent(getIntent());
            // release notes after an update; otherwise (and not on first run) the daily update check (GitHub build)
            if (WhatsNew.maybeShowAfterUpdate(this) == WhatsNew.Result.NONE) Updates.maybeAutoCheck(this);
        }
    }

    // ------------------------------------------------------------------ sections
    private void buildHeader(LinearLayout root) {
        LinearLayout c = card(root, null);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        orb = new OrbView(this);
        row.addView(orb, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(8), 0);
        heroText = text(18, TEXT, true);
        col.addView(heroText);
        heroSub = text(13, SUB, false);
        col.addView(heroSub);
        clientChip = chip();
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(6);
        col.addView(clientChip, cl);
        row.addView(col, weight(1));
        TextView help = text(15, TEXT, true);            // the user docs, in the app, opened at Troubleshooting
        help.setText("? Troubleshooting / Help");
        help.setGravity(Gravity.CENTER);
        help.setBackground(rounded(LINE, dp(18)));
        help.setPadding(dp(14), 0, dp(14), 0);
        help.setContentDescription("Help and troubleshooting");
        help.setOnClickListener(v -> help(Docs.HELP_TROUBLESHOOTING));
        row.addView(help, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        c.addView(row);
        buildStartStop(c);
        scanLine = text(13, TEXT, false);
        scanLine.setPadding(0, dp(8), 0, 0);
        c.addView(scanLine);
        powerLine = text(13, SUB, false);
        c.addView(powerLine);
        mirrorBtn = button("Mirror Creality Scan on this phone");
        mirrorBtn.setOnClickListener(v -> openMirror(mirrorHost));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = dp(8);
        c.addView(mirrorBtn, ml);
        autoSwitch = new android.widget.Switch(this);
        autoSwitch.setText("Auto-start screen mirroring when “Mobile connection” is opened in Creality Scan\n"
                + "Creality Scan mirrors to one phone at a time. Turn off if you want to mirror from the CrealityScan mobile app.");
        autoSwitch.setTextColor(SUB);
        autoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        autoSwitch.setChecked(MirrorSession.get(this).autoEnabled());
        autoSwitch.setOnCheckedChangeListener((v, on) -> MirrorSession.get(this).setAutoEnabled(on));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = dp(4);
        c.addView(autoSwitch, al);
        awakeSwitch = new android.widget.Switch(this);
        awakeSwitch.setText("Keep screen on while the bridge is running\n"
                + "Stops the phone sleeping and auto-locking while CR-BYOB is on screen. The power button still locks it.");
        awakeSwitch.setTextColor(SUB);
        awakeSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        awakeSwitch.setChecked(keepAwakePref());
        awakeSwitch.setOnCheckedChangeListener((v, on) -> {
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean(PREF_KEEP_AWAKE, on).apply();
            applyKeepAwake(BridgeService.current() != null);
        });
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.topMargin = dp(8);
        c.addView(awakeSwitch, kl);
        warnBox = new LinearLayout(this);            // one pill per warning, stacked (setWarnings)
        warnBox.setOrientation(LinearLayout.VERTICAL);
        warnBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = dp(4);
        c.addView(warnBox, wl);
    }

    static final int WARN_RED = 0xFF5A1D1D, WARN_AMBER = 0xFF4A3812;
    static final String PREF_KEEP_AWAKE = "keep_screen_on";
    private android.widget.Switch awakeSwitch;
    private boolean keptAwake;

    /** The keep-screen-on toggle; on unless the user has turned it off. */
    private boolean keepAwakePref() {
        return getSharedPreferences("bridge", MODE_PRIVATE).getBoolean(PREF_KEEP_AWAKE, true);
    }

    /**
     * "Keep screen on while the bridge is running": FLAG_KEEP_SCREEN_ON on this window while the toggle is on and the
     * bridge runs. Android honours it only while this activity is visible, so leaving the app restores normal
     * sleep and auto-lock, and the power button still locks the phone.
     */
    private void applyKeepAwake(boolean running) {
        boolean on = running && keepAwakePref();
        if (on == keptAwake) return;
        keptAwake = on;
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BLog.log("keep_screen_on", "on", on);
    }

    /** Warning pill look: rounded red/amber box with padding (header stack and in-card notes). */
    private void pill(TextView t, boolean red) {
        t.setBackground(rounded(red ? WARN_RED : WARN_AMBER, dp(10)));
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private void warnAction(String action) {
        Runnable again = () -> h.postDelayed(this::refresh, 200);
        if ("perm:camera".equals(action)) perms.ensure(PermissionGuide.CAMERA, again);
        else if ("perm:notify".equals(action)) perms.ensure(PermissionGuide.NOTIFY, again);
        else if ("perm:usb".equals(action)) {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um != null) {
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (Otter.isOrbbec(d)) {
                        Bridge b = BridgeService.current();
                        if (b != null) b.resetPermissionPending();
                        BridgeService.requestPermission(this, um, d);
                        BLog.log("usb_manual_retry", "dev", d.getDeviceName());
                        break;
                    }
                }
            }
            again.run();
        }
        else if (action.startsWith("doc:")) help(action.substring(4));
        else if ("report".equals(action)) DeviceReport.open(this);
    }

    /** Opens the user guide at a Troubleshooting question (Docs.HELP_*), expanded and highlighted. */
    private void help(String anchor) {
        DocsActivity.open(this, Docs.HOME, anchor);
    }

    /**
     * `text` followed by a link label ("How to fix ›") that the whole view opens: on a warning pill in bold white
     * (blue would be unreadable on red), elsewhere in the link blue.
     */
    private CharSequence withLink(String text, String label, boolean onPill) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text).append("  ");
        int st = sb.length();
        sb.append(label);
        if (onPill) {
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, sb.length(), 0);
            sb.setSpan(new android.text.style.UnderlineSpan(), st, sb.length(), 0);
        } else {
            sb.setSpan(new android.text.style.ForegroundColorSpan(BLUE), st, sb.length(), 0);
        }
        return sb;
    }

    private void linkTo(TextView t, String anchor) {
        if (anchor == null) {
            t.setOnClickListener(null);
            t.setClickable(false);
        } else {
            t.setOnClickListener(v -> help(anchor));
        }
    }

    /** Rebuilds the warning pills only when the list changes; each {text, "red" | "amber"[, action]}. */
    private void setWarnings(List<String[]> warns) {
        StringBuilder key = new StringBuilder();
        for (String[] w : warns) key.append(w[1]).append(':').append(w[0]).append('\n');
        if (key.toString().equals(warnKey)) return;
        warnKey = key.toString();
        warnBox.removeAllViews();
        for (String[] w : warns) {
            boolean red = "red".equals(w[1]);
            TextView t = text(14, TEXT, red);
            String body = (red ? "" : "⚠  ") + w[0];
            String link = w.length < 3 ? null : w[2].startsWith("doc:") ? "How to fix ›"
                    : "report".equals(w[2]) ? "Tested it? Send a device report ›" : null;
            t.setText(link != null ? withLink(body, link, true) : body);
            pill(t, red);
            if (w.length > 2) {                       // actionable pill: fix it, or open its troubleshooting
                final String action = w[2];
                t.setOnClickListener(v -> warnAction(action));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            warnBox.addView(t, lp);
        }
        show(warnBox, !warns.isEmpty());
    }

    private void buildActivity(LinearLayout root) {
        waitCard = card(root, null);
        waitText = text(16, SUB, false);
        waitText.setText("Waiting for PC");
        waitCard.addView(waitText);

        streamsCard = card(root, "Streams");
        String[] names = {"IR L", "IR R", "Color", "IMU"};
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            TextView name = text(16, SUB, true);
            name.setText(names[i]);
            row.addView(name, new LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            stVal[i] = text(20, TEXT, true);
            top.addView(stVal[i]);
            stBadge[i] = text(12, BG, true);
            stBadge[i].setPadding(dp(8), dp(2), dp(8), dp(2));
            stBadge[i].setBackground(rounded(AMBER, dp(8)));
            top.addView(stBadge[i], wrapLeft(dp(8)));
            mid.addView(top);
            stSub[i] = text(12, SUB, false);
            mid.addView(stSub[i]);
            row.addView(mid, weight(1));
            stSpark[i] = new SparkView(this);
            row.addView(stSpark[i], new LinearLayout.LayoutParams(dp(100), dp(36)));
            streamsCard.addView(row);
        }
        totalMbps = text(15, TEXT, true);
        totalMbps.setGravity(Gravity.END);
        totalMbps.setPadding(0, dp(4), 0, 0);
        streamsCard.addView(totalMbps);

        linkCard = card(root, "Link quality");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        linkBars = new BarsView(this);
        row.addView(linkBars, new LinearLayout.LayoutParams(dp(48), dp(34)));
        linkWord = text(22, TEXT, true);
        linkWord.setPadding(dp(12), 0, 0, 0);
        row.addView(linkWord);
        linkCard.addView(row);
        linkDetail = text(13, SUB, false);
        linkDetail.setPadding(0, dp(6), 0, 0);
        linkCard.addView(linkDetail);
    }

    // ------------------------------------------------------------------ session stats
    private LinearLayout sessCard, sessDetails;
    private TextView sessDetailsHdr;
    private boolean sessOpen;
    private final TextView[] sTime = new TextView[3], sData = new TextView[3];
    private final TextView[][] sFrames = new TextView[4][3];
    private static final String[] DET = {"PC control drops", "RTSP closes / errors", "IMU reconnects", "Scanner USB detach / attach",
            "Suspected brown-outs", "Wi-Fi Direct joins / leaves", "Mirror connects", "UVC frame errors",
            "Stalled sessions closed / taken over", "Closed on PC / peer loss",
            "Scanner reply (avg / p95 / max)", "Frame age (avg / p95 / max)", "TCP RTT", "Link", "RSSI min / avg", "Lowest link rate"};
    private final TextView[] sDet = new TextView[DET.length];

    private LinearLayout cell(LinearLayout row, String label, TextView[] out, int idx) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(11, SUB, false);
        l.setText(label);
        TextView v = text(16, TEXT, true);
        v.setText("—");
        c.addView(l);
        c.addView(v);
        out[idx] = v;
        row.addView(c, weight(1));
        return c;
    }

    private TextView groupTitle(LinearLayout parent, String t) {
        TextView g = text(12, SUB, true);
        g.setText(t);
        g.setLetterSpacing(0.06f);
        g.setPadding(0, dp(10), 0, dp(2));
        parent.addView(g);
        return g;
    }

    private void buildSession(LinearLayout root) {
        sessCard = card(root, null);
        LinearLayout hdr = new LinearLayout(this);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = sectionTitle("Session stats");
        t.setPadding(0, 0, 0, 0);
        hdr.addView(t, weight(1));
        Button reset = button("Reset");
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        reset.setOnClickListener(v -> {
            SessionStats.reset();
            if (BridgeService.current() == null) SessionStats.cur.stopMs = SessionStats.cur.startMs;
            refreshSession();
        });
        hdr.addView(reset);
        sessCard.addView(hdr);

        groupTitle(sessCard, "TIME");
        LinearLayout r1 = new LinearLayout(this);
        cell(r1, "Bridge up", sTime, 0);
        cell(r1, "PC connected", sTime, 1);
        cell(r1, "Streaming", sTime, 2);
        sessCard.addView(r1);
        groupTitle(sessCard, "DATA");
        LinearLayout r2 = new LinearLayout(this);
        cell(r2, "Sent", sData, 0);
        cell(r2, "Avg while streaming", sData, 1);
        cell(r2, "Peak", sData, 2);
        sessCard.addView(r2);

        groupTitle(sessCard, "FRAMES");
        String[] rows = {"Sent / captured", "Dropped", "At reduced rate", "Times decimated"};
        LinearLayout head = new LinearLayout(this);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1.4f));
        for (String n : new String[]{"IR L", "IR R", "Color"}) {
            TextView h = text(11, SUB, true);
            h.setText(n);
            head.addView(h, weight(1));
        }
        sessCard.addView(head);
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(2), 0, 0);
            TextView l = text(12, SUB, false);
            l.setText(rows[r]);
            row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
            for (int i = 0; i < 3; i++) {
                sFrames[r][i] = text(13, TEXT, true);
                row.addView(sFrames[r][i], weight(1));
            }
            sessCard.addView(row);
        }

        sessDetailsHdr = text(12, SUB, true);
        sessDetailsHdr.setLetterSpacing(0.06f);
        sessDetailsHdr.setPadding(0, dp(12), 0, dp(2));
        sessDetailsHdr.setOnClickListener(v -> { sessOpen = !sessOpen; refreshSession(); });
        sessCard.addView(sessDetailsHdr);
        sessDetails = new LinearLayout(this);
        sessDetails.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < DET.length; i++) {
            if (i == 0) groupTitle(sessDetails, "RELIABILITY");
            if (i == 10) groupTitle(sessDetails, "LATENCY");
            if (i == 13) groupTitle(sessDetails, "LINK");
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(2), 0, 0);
            TextView l = text(12, SUB, false);
            l.setText(DET[i]);
            row.addView(l, weight(1));
            sDet[i] = text(13, TEXT, true);
            sDet[i].setGravity(Gravity.END);
            row.addView(sDet[i], weight(1));
            sessDetails.addView(row);
        }
        sessCard.addView(sessDetails);
        Button report = button("Send a device report");          // the session's numbers, pre-filled on GitHub
        report.setOnClickListener(v -> DeviceReport.open(this));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(10);
        sessCard.addView(report, rl);
    }

    private void refreshSession() {
        SessionStats ss = SessionStats.cur;
        boolean have = BridgeService.current() != null || BridgeService.stopping || ss.stopMs > 0;
        show(sessCard, have);
        if (!have) return;
        boolean stopped = ss.stopMs > 0;
        sTime[0].setText(SessionStats.dur(ss.uptimeMs()) + (stopped ? " (stopped)" : ""));
        sTime[1].setText(SessionStats.dur(ss.pcMs));
        sTime[2].setText(SessionStats.dur(ss.streamMs));
        sData[0].setText(String.format(Locale.US, "%.2f GB", ss.gb()));
        sData[1].setText(String.format(Locale.US, "%.0f Mbit/s", ss.avgMbps()));
        sData[2].setText(String.format(Locale.US, "%.0f Mbit/s", ss.peakMbps));
        for (int i = 0; i < 3; i++) {
            sFrames[0][i].setText(ss.captured[i] == 0 ? "—" : String.format(Locale.US, "%.0f%%", SessionStats.pct(ss.sent[i], ss.captured[i])));
            sFrames[1][i].setText(String.valueOf(ss.qdrops[i] + ss.skipped[i]));
            sFrames[2][i].setText(String.format(Locale.US, "%.0f%%", SessionStats.pct(ss.reducedMs[i], ss.streamingMsPer[i])));
            sFrames[3][i].setText(ss.decimations[i] + "×");
        }
        sessDetailsHdr.setText(sessOpen ? "DETAILS ▾" : "DETAILS ▸");
        show(sessDetails, sessOpen);
        if (!sessOpen) return;
        sDet[0].setText(String.valueOf(ss.pcDrops.get()));
        sDet[1].setText(ss.rtspCloses.get() + " / " + ss.rtspErrors.get());
        sDet[2].setText(String.valueOf(Math.max(0, ss.imuConnects.get() - 1)));
        sDet[3].setText(ss.usbDetach.get() + " / " + ss.usbAttach.get());
        sDet[4].setText(String.valueOf(ss.brownouts.get()));
        sDet[5].setText(ss.p2pJoins.get() + " / " + ss.p2pLeaves.get());
        sDet[6].setText(String.valueOf(ss.mirrorConnects.get()));
        sDet[7].setText(String.valueOf(ss.uvcErrors.get()));
        sDet[8].setText(ss.stalledClosed.get() + " / " + ss.takeovers.get());
        sDet[9].setText(String.valueOf(ss.peerCleanups.get()));
        sDet[10].setText(ss.xu.fmt());
        sDet[11].setText(ss.frameAge.fmt());
        sDet[12].setText("not available");      // no public TCP_INFO in the SDK, and the PC has no echo request
        sDet[13].setText(ss.linkNet.isEmpty() ? "—" : ss.linkNet);
        sDet[14].setText(ss.rssiN == 0 ? (ss.linkNet.startsWith("Wi-Fi Direct") ? "n/a (group owner)" : "—")
                : ss.rssiMin + " / " + Math.round((double) ss.rssiSum / ss.rssiN) + " dBm");
        sDet[15].setText(ss.rssiN == 0 ? "—" : ss.linkMin + " Mbit/s");
    }

    private void buildNetwork(LinearLayout root) {
        LinearLayout c = card(root, "Network");
        // One choice: which network the PC finds the bridge on. "Wi-Fi Direct" also starts the phone's own network.
        advTitle = label("Run streaming server on");
        advTitle.setPadding(0, 0, 0, dp(2));
        c.addView(advTitle);
        advGroup = new RadioGroup(this);
        advGroup.setOnCheckedChangeListener((g, id) -> {
            if (advUpdating || id == View.NO_ID) return;
            View rb = g.findViewById(id);
            if (rb != null && rb.getTag() != null) setAdvertise((String) rb.getTag());
        });
        c.addView(advGroup);
        advNote = text(14, TEXT, false);
        linkTo(advNote, Docs.HELP_NETWORK);
        pill(advNote, false);
        advNote.setVisibility(View.GONE);
        c.addView(advNote, pillParams());

        // Wi-Fi Direct join details (only while Wi-Fi Direct is the chosen network)
        p2pBox = new LinearLayout(this);
        p2pBox.setOrientation(LinearLayout.VERTICAL);
        // status pill: the network only exists while the bridge runs, so say so up front
        p2pStatus = text(14, TEXT, true);
        p2pStatus.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(8);
        p2pBox.addView(p2pStatus, sl);
        p2pBox.addView(label("Network name"));
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        p2pSsid = text(22, TEXT, true);
        p2pSsid.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        p2pSsid.setTextIsSelectable(true);
        p2pSsid.setMaxLines(1);
        p2pSsid.setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        r1.addView(p2pSsid, weight(1));
        Button c1 = button("Copy");
        c1.setOnClickListener(v -> copy("Network name", last != null ? last.p2pSsid : null));
        r1.addView(c1, wrapLeft(dp(6)));
        p2pBox.addView(r1);
        p2pBox.addView(label("Password"));
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        p2pPass = text(22, TEXT, true);
        p2pPass.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        p2pPass.setTextIsSelectable(true);
        p2pPass.setMaxLines(1);
        p2pPass.setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        r2.addView(p2pPass, weight(1));
        Button c2 = button("Copy");
        c2.setOnClickListener(v -> copy("Password", last != null ? last.p2pPass : null));
        r2.addView(c2, wrapLeft(dp(6)));
        p2pBox.addView(r2);
        editBtn = button("Edit Wi-Fi Direct settings");
        editBtn.setOnClickListener(v -> openEditor());
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.topMargin = dp(8);
        p2pBox.addView(editBtn, el);
        p2pHint = text(14, BLUE, false);
        p2pHint.setPadding(0, dp(8), 0, 0);
        linkTo(p2pHint, Docs.HELP_P2P_JOIN);    // users look for the network in Creality Scan's own Wi-Fi list
        p2pBox.addView(p2pHint);
        // amber banner: phone is on Wi-Fi Direct and a Wi-Fi network at once
        p2pDualBanner = new LinearLayout(this);
        p2pDualBanner.setOrientation(LinearLayout.VERTICAL);
        p2pDualBanner.setBackground(rounded((AMBER & 0x00FFFFFF) | 0x26000000, dp(12)));
        p2pDualBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(10);
        p2pBox.addView(p2pDualBanner, bl);
        p2pDual = text(14, AMBER, true);
        linkTo(p2pDual, Docs.HELP_DUAL);
        p2pDualBanner.addView(p2pDual);
        p2pDualBtn = button("Disconnect phone from Wi-Fi network");
        p2pDualBtn.setOnClickListener(v -> openWifiDisconnect());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(6);
        p2pDualBanner.addView(p2pDualBtn, dl);
        buildEditor();                          // shown in a dialog, not inline
        c.addView(p2pBox);

        // Wi-Fi (STA), only while connected
        wifiBox = new LinearLayout(this);
        wifiBox.setOrientation(LinearLayout.VERTICAL);
        wifiBox.setPadding(0, dp(12), 0, 0);
        LinearLayout wr = new LinearLayout(this);
        wr.setGravity(Gravity.CENTER_VERTICAL);
        wifiBars = new BarsView(this);
        wr.addView(wifiBars, new LinearLayout.LayoutParams(dp(28), dp(20)));
        wifiLine = text(15, TEXT, false);
        wifiLine.setPadding(dp(10), 0, 0, 0);
        wr.addView(wifiLine, weight(1));
        wifiBox.addView(wr);                    // its speed warnings are pills in the header (setWarnings)
        c.addView(wifiBox);

        // Ethernet, only while a wired interface is up
        ethBox = new LinearLayout(this);
        ethBox.setPadding(0, dp(10), 0, 0);
        ethLine = text(14, TEXT, false);
        linkTo(ethLine, Docs.HELP_ETHERNET);
        pill(ethLine, false);
        ethBox.addView(ethLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(ethBox);

    }

    /** Experimental: settings that go beyond what Creality Scan does with its own Scan Bridge. */
    private void buildExperimental(LinearLayout root) {
        LinearLayout c = card(root, "🧪 Experimental");
        TextView intro = text(13, SUB, false);
        intro.setText("Experimental features, may be buggy.");
        intro.setPadding(0, 0, 0, dp(4));
        c.addView(intro);

        // Scanner frame rate: Stock / Boost / Max, resolved against the attached scanner (FpsChoice)
        fpsTitle = label("Scanner frame-rate override");
        fpsTitle.setPadding(0, dp(8), 0, dp(2));
        c.addView(fpsTitle);
        fpsGroup = new RadioGroup(this);
        for (String mode : new String[]{FpsChoice.STOCK, FpsChoice.BOOST, FpsChoice.MAX}) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(mode);
            rb.setTextColor(TEXT);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            rb.setButtonTintList(ColorStateList.valueOf(BLUE));
            rb.setPadding(dp(4), dp(6), 0, dp(6));
            fpsGroup.addView(rb);
        }
        fpsGroup.setOnCheckedChangeListener((g, id) -> {
            if (fpsUpdating || id == View.NO_ID) return;
            View rb = g.findViewById(id);
            if (rb != null && rb.getTag() != null) setFpsMode((String) rb.getTag());
        });
        c.addView(fpsGroup);
        fpsNote = text(13, SUB, false);
        c.addView(fpsNote);

    }

    private void buildEditor() {
        p2pEditor = new LinearLayout(this);
        p2pEditor.setOrientation(LinearLayout.VERTICAL);
        p2pEditor.setPadding(dp(24), dp(4), dp(24), 0);
        p2pEditor.addView(label("Name (Android requires the " + P2pGroup.PREFIX + " prefix)"));
        LinearLayout nr = new LinearLayout(this);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        TextView pre = text(18, SUB, false);
        pre.setTypeface(Typeface.MONOSPACE);
        pre.setText(P2pGroup.PREFIX);
        nr.addView(pre);
        editSlug = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nr.addView(editSlug, weight(1));
        p2pEditor.addView(nr);
        p2pEditor.addView(label("Password (8–63 characters)"));
        editPass = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        p2pEditor.addView(editPass);
        editPreview = text(14, TEXT, false);
        editPreview.setPadding(0, dp(6), 0, 0);
        p2pEditor.addView(editPreview);
        editError = text(14, RED, false);
        p2pEditor.addView(editError);
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { validateEditor(); }
        };
        editSlug.addTextChangedListener(w);
        editPass.addTextChangedListener(w);
        TextView note = text(13, SUB, false);
        note.setText("The PC must re-join with the new name/password afterwards.");
        note.setPadding(0, dp(6), 0, 0);
        p2pEditor.addView(note);
    }

    private P2pGroup p2pAccess() {
        Bridge b = BridgeService.current();
        if (b != null) return b.p2p;
        if (offlineP2p == null) offlineP2p = new P2pGroup(this);
        return offlineP2p;
    }

    /** Merge one key into files/stream_map.json while the bridge is stopped. */
    private void putStreamMapOffline(String key, Object value) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "stream_map.json");
            org.json.JSONObject j = new org.json.JSONObject();
            if (f.exists()) j = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (value == null) j.remove(key); else j.put(key, value);
            java.nio.file.Files.write(f.toPath(), j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Wi-Fi Direct name/password in a dialog; Save validates, restarts the group and closes it. */
    private void openEditor() {
        if (BridgeService.current() != null) {
            Toast.makeText(this, "Stop the bridge to change Wi-Fi Direct settings", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] cr = p2pAccess().credentials();
        editSlug.setText(P2pGroup.slugOf(cr[0]));
        editPass.setText(cr[1] != null ? cr[1] : "");
        validateEditor();
        if (p2pEditor.getParent() != null) ((ViewGroup) p2pEditor.getParent()).removeView(p2pEditor);
        ScrollView sv = new ScrollView(this);
        sv.addView(p2pEditor);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("Wi-Fi Direct settings")
                .setView(sv)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> { if (saveEditor()) d.dismiss(); }));
        d.setOnDismissListener(x -> sv.removeView(p2pEditor));
        d.show();
        Widgets.styleDialog(d);
    }

    private boolean validateEditor() {
        String slug = editSlug.getText().toString().trim(), pass = editPass.getText().toString();
        editPreview.setText("Full name: " + P2pGroup.PREFIX + slug);
        String err = P2pGroup.checkSlug(slug);
        if (err == null) err = P2pGroup.checkPassphrase(pass);
        editError.setText(err == null ? "" : err);
        editError.setVisibility(err == null ? View.GONE : View.VISIBLE);
        return err == null;
    }

    private boolean saveEditor() {
        if (!validateEditor()) return false;
        String slug = editSlug.getText().toString().trim(), pass = editPass.getText().toString();
        String err = p2pAccess().setCredentials(slug, pass);
        if (err != null) {
            editError.setText(err);
            editError.setVisibility(View.VISIBLE);
            return false;
        }
        Toast.makeText(this, "Saved: " + P2pGroup.PREFIX + slug, Toast.LENGTH_SHORT).show();
        refresh();
        return true;
    }

    private void buildEvents(LinearLayout root) {
        LinearLayout c = card(root, null);
        eventsHeader = text(13, SUB, true);
        eventsHeader.setLetterSpacing(0.08f);
        eventsHeader.setOnClickListener(v -> { eventsOpen = !eventsOpen; refresh(); });
        c.addView(eventsHeader);
        eventsBox = new LinearLayout(this);
        eventsBox.setOrientation(LinearLayout.VERTICAL);
        eventsBox.setPadding(0, dp(6), 0, 0);
        c.addView(eventsBox);
        TextView logHeader = text(13, SUB, true);
        logHeader.setText("RAW LOG ▸");
        logHeader.setLetterSpacing(0.08f);
        logHeader.setPadding(0, dp(12), 0, 0);
        logView = text(10, SUB, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setVisibility(View.GONE);
        logHeader.setOnClickListener(v -> {
            logOpen = !logOpen;
            logHeader.setText(logOpen ? "RAW LOG ▾" : "RAW LOG ▸");
            logView.setVisibility(logOpen ? View.VISIBLE : View.GONE);
            refresh();
        });
        // Export log: the whole log, zipped with private details removed (LogExport), to attach to an issue
        TextView copyLog = text(13, BLUE, false);
        copyLog.setText("Export log");
        copyLog.setPadding(dp(12), dp(12), 0, 0);
        copyLog.setOnClickListener(v -> LogFiles.share(this));
        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.addView(logHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        logRow.addView(copyLog);
        c.addView(logRow);
        c.addView(logView);
    }

    /**
     * App version under the last card. Tap: release notes (and "Check for updates" in GitHub builds, whose
     * link says so).
     * Long-press copies a bug-report line (version, phone, Android).
     */
    private void buildFooter(LinearLayout root) {
        TextView v = text(12, SUB, false);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(AppVersion.display() + "  ·  ");
        int st = sb.length();
        sb.append(Updates.SUPPORTED ? "What's new / Check for app updates" : "What's new");
        sb.setSpan(new android.text.style.ForegroundColorSpan(Widgets.BLUE), st, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(sb);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(0, dp(16), 0, dp(4));
        v.setOnClickListener(x -> WhatsNew.showAll(this));
        v.setOnLongClickListener(x -> { copy("Version info", AppVersion.bugReportLine()); return true; });
        root.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView rep = text(13, BLUE, false);                  // works or not: either report helps the device lists
        rep.setText("Tried a scanner or phone? Send a device report ›");
        rep.setGravity(Gravity.CENTER_HORIZONTAL);
        rep.setPadding(0, dp(8), 0, dp(4));
        rep.setOnClickListener(x -> DeviceReport.open(this));
        root.addView(rep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Ko-fi button: GitHub builds only (null in the Play flavor).
        View donate = Donate.footerButton(this);
        if (donate != null) {
            TextView ask = text(13, SUB, false);
            ask.setText("Avoided buying a Scan Bridge, and want to say thanks with a donation?");
            ask.setGravity(Gravity.CENTER_HORIZONTAL);
            ask.setPadding(0, dp(8), 0, 0);
            root.addView(ask, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, dp(6), 0, dp(12));
            root.addView(donate, lp);
        }
    }

    // ------------------------------------------------------------------ actions

    static String dualWarning(String ssid) {
        return "The phone is also connected to " + ssid + ". Running Wi-Fi Direct and " + ssid
                + " at the same time shares the phone's radio and may reduce streaming performance.";
    }

    /**
     * Apps can't disconnect Wi-Fi themselves on Android 10+ (WifiManager.disconnect is a no-op), so open Settings
     * Wi-Fi page: gear next to the network, then Disconnect. Wi-Fi itself must stay on for Wi-Fi Direct.
     */
    private void openWifiDisconnect() {
        String ssid = last != null && last.staSsid != null ? last.staSsid : "the Wi-Fi network";
        // the full Internet / Wi-Fi page lists the connected network with a gear -> Disconnect;
        // the quick Internet panel can show only the on/off switch
        Intent i = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        Toast.makeText(this, "Tap the gear next to " + ssid + ", then Disconnect. Keep Wi-Fi switched on.", Toast.LENGTH_LONG).show();
    }

    private void setAdvertise(String v) {
        if ("p2p".equals(v) && last != null && last.staConnected && !"p2p".equals(last.advertise)) {
            String ssid = last.staSsid;
            android.app.AlertDialog.Builder ab = new android.app.AlertDialog.Builder(this)
                    .setTitle("Run Wi-Fi concurrently?")
                    .setMessage(dualWarning(ssid) + "\n\nFor the best link, disconnect the phone from " + ssid + ".")
                    .setPositiveButton("Disconnect " + ssid, (d, w) -> { applyAdvertise(v); openWifiDisconnect(); })
                    .setNeutralButton("Run both", (d, w) -> applyAdvertise(v))
                    .setNegativeButton("Cancel", (d, w) -> { advKey = ""; refresh(); })
                    .setOnCancelListener(d -> { advKey = ""; refresh(); });
            Widgets.styleDialog(ab.show());
            return;
        }
        applyAdvertise(v);
    }

    private void applyAdvertise(String v) {
        if (!"p2p".equals(v)) {
            applyAdvertiseNow(v);
            return;
        }
        PermissionGuide.Item need = PermissionGuide.p2pItem();      // Wi-Fi Direct can't start without it
        perms.ensure(need, () -> {
            if (perms.granted(need)) {
                applyAdvertiseNow(v);
            } else {                                                   // declined: keep the previous network
                advKey = "";
                refresh();
                Toast.makeText(this, "Wi-Fi Direct needs " + (need == PermissionGuide.NEARBY ? "Nearby devices" : "Location")
                        + " access. Network unchanged.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void applyAdvertiseNow(String v) {
        if (BridgeService.current() != null) {      // locked while running
            Toast.makeText(this, "Stop the bridge to change the network", Toast.LENGTH_SHORT).show();
            advKey = "";
            refresh();
            return;
        }
        setP2p("p2p".equals(v));      // Wi-Fi Direct starts with the bridge only if it is the chosen network
        putStreamMapOffline("advertise", v);
        advKey = "";
        h.postDelayed(this::refresh, 100);
    }

    static String netWord(String v, Dash.Snap s) {
        switch (v) {
            case "p2p": return "Wi-Fi Direct";
            case "eth": return "Ethernet";
            case "local": return "This phone only";
            default: return s != null && s.staConnected ? s.staSsid : "Wi-Fi";
        }
    }

    static String advLabel(String v, Dash.Snap s) {
        switch (v) {
            case "p2p": return "Wi-Fi Direct" + (s != null && s.p2pSsid != null ? " (" + s.p2pSsid + ") · 192.168.49.1"
                    : BridgeService.current() == null ? " · starts with the bridge" : "");
            case "wifi": return (s != null && s.staConnected ? s.staSsid : "Wi-Fi") + " (Wi-Fi)" + (s != null && s.lanIp != null ? " · " + s.lanIp : "");
            case "eth": return "Ethernet" + (s != null && s.ethName != null ? " (" + s.ethName + ")" : "") + (s != null && s.ethIp != null ? " · " + s.ethIp : "");
            case "lan": return "Wi-Fi + Ethernet";
            case "local": return "This phone only (for CrealityScan mobile app)";
            default: return "All";
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (BuildConfig.DEBUG && intent != null && intent.hasExtra("show_when_locked")) {   // dev toggle, open app
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("show_when_locked", intent.getBooleanExtra("show_when_locked", false)).apply();
            applyLockScreenMode(this);
        }
        handleIntent(intent);
    }

    /** Mirror viewer; host == null lets it find the PC (bridge peer, broadcast, saved/manual IP). */
    private void openMirror(String host) {
        Intent m = new Intent(this, MirrorActivity.class);
        m.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);     // an open viewer comes back instead of a second one
        if (host != null) m.putExtra("host", host);
        startActivity(m);
    }

    /** A Creality/Orbbec scanner (VID 0x2BC5) is really on the USB bus (USB_DEVICE_ATTACHED can be sent by any app). */
    private boolean scannerOnUsb() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um == null) return false;
        for (android.hardware.usb.UsbDevice d : um.getDeviceList().values())
            if (d.getVendorId() == 0x2BC5) return true;
        return false;
    }

    // ------------------------------------------------------------------ "First scan go well?" (ReportPrompt)
    private LinearLayout reportCard;
    private TextView reportText;
    private long lastStreamingAt;          // last render that saw the bridge streaming (0 = not while visible)
    private String promptScanner;          // the scanner of the session, kept after the bridge stops
    private boolean promptScannerVerified;
    private List<String> listedPhones;
    private boolean promptTest;            // debug: --ez report_prompt_test true shows the card now, as if unreported

    private void buildReportPrompt(LinearLayout root) {
        reportCard = card(root, null);
        android.graphics.drawable.GradientDrawable bg = rounded(CARD, dp(16));
        bg.setStroke(Math.max(1, dp(1.5f)), (BLUE & 0x00FFFFFF) | 0x99000000);
        reportCard.setBackground(bg);
        LinearLayout row = new LinearLayout(this);
        TextView title = text(16, TEXT, true);
        title.setText("First scan go well?");
        row.addView(title, weight(1));
        TextView close = text(18, SUB, false);
        close.setText("\u2715");
        close.setPadding(dp(12), 0, dp(4), 0);
        close.setContentDescription("Dismiss");
        close.setOnClickListener(v -> finishReportPrompt(false));
        row.addView(close);
        reportCard.addView(row);
        reportText = text(14, TEXT, false);
        reportText.setPadding(0, dp(6), 0, 0);
        reportCard.addView(reportText);
        reportCard.setOnClickListener(v -> finishReportPrompt(true));
        reportCard.setVisibility(View.GONE);
    }

    /** Tapped (opens the report) or dismissed: either way it's answered and never shown again. */
    private void finishReportPrompt(boolean report) {
        getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean(ReportPrompt.PREF_DONE, true).apply();
        BLog.log("report_prompt_" + (report ? "tapped" : "dismissed"));
        promptTest = false;
        reportCard.setVisibility(View.GONE);
        if (report) DeviceReport.open(this);
    }

    private List<String> listedPhones() {
        if (listedPhones == null) {
            String md = null;
            try (java.io.InputStream in = getAssets().open(Docs.ASSET_ROOT + "docs/phones.md")) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                md = new String(o.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ignored) {
            }
            listedPhones = ReportPrompt.listedPhones(md);
        }
        return listedPhones;
    }

    /** Shows the card once the first real scan has ended, if the phone or scanner has no report yet. */
    private void updateReportPrompt(Dash.Snap s, boolean running) {
        if (reportCard == null) return;
        if (running && s.scanner && s.modelName != null) {      // remember the session's scanner past Stop
            promptScanner = s.modelName.startsWith("PID ") ? s.scannerModel : s.modelName;
            promptScannerVerified = "verified".equals(s.modelStatus);
        } else if (!running && promptScanner == null && s.plugged && s.scannerModel != null) {
            promptScanner = s.scannerModel;                      // forgotten (dashboard rebuilt after Stop): the
            promptScannerVerified = "verified".equals(s.idleModelStatus);   // plugged-in scanner, by its USB ID
        }
        boolean streaming = running && s.hero == Dash.STREAMING;
        long now = android.os.SystemClock.elapsedRealtime();
        if (streaming) lastStreamingAt = now;
        boolean done = getSharedPreferences("bridge", MODE_PRIVATE).getBoolean(ReportPrompt.PREF_DONE, false);
        String missing = null;
        if (promptTest) {
            missing = ReportPrompt.missing(false, ReportPrompt.phoneName(Build.MANUFACTURER, Build.MODEL),
                    promptScanner != null ? promptScanner : s.scannerModel, false);
        } else if (!done && ReportPrompt.scanEnded(SessionStats.cur.streamMs, streaming, lastStreamingAt, now)) {
            boolean pretend = pretendUnreported();
            missing = ReportPrompt.missing(!pretend && ReportPrompt.phoneListed(listedPhones(), Build.MANUFACTURER, Build.MODEL),
                    ReportPrompt.phoneName(Build.MANUFACTURER, Build.MODEL), promptScanner, !pretend && promptScannerVerified);
        }
        boolean show = missing != null;
        if (show) reportText.setText(ReportPrompt.message(missing));
        if (show && reportCard.getVisibility() != View.VISIBLE) BLog.log("report_prompt_shown", "missing", missing);
        show(reportCard, show);
    }

    // ------------------------------------------------------------------ "CR-BYOB stopped unexpectedly" (crash card)
    private LinearLayout crashCard;
    private TextView crashText;
    private long crashTs;                  // exit timestamp the card is about

    private void buildCrashPrompt(LinearLayout root) {
        crashCard = card(root, null);
        android.graphics.drawable.GradientDrawable bg = rounded(CARD, dp(16));
        bg.setStroke(Math.max(1, dp(1.5f)), (AMBER & 0x00FFFFFF) | 0x99000000);
        crashCard.setBackground(bg);
        LinearLayout row = new LinearLayout(this);
        TextView title = text(16, TEXT, true);
        title.setText("CR-BYOB stopped unexpectedly");
        row.addView(title, weight(1));
        TextView close = text(18, SUB, false);
        close.setText("✕");
        close.setPadding(dp(12), 0, dp(4), 0);
        close.setContentDescription("Dismiss");
        close.setOnClickListener(v -> finishCrashPrompt(false));
        row.addView(close);
        crashCard.addView(row);
        crashText = text(14, TEXT, false);
        crashText.setPadding(0, dp(6), 0, 0);
        crashCard.addView(crashText);
        crashCard.setOnClickListener(v -> finishCrashPrompt(true));
        crashCard.setVisibility(View.GONE);
        updateCrashPrompt(null);
    }

    /**
     * Shows the card when the last run crashed, hung, or was killed by Android while in use (DeviceReport.isProblem),
     * within the last 24 h and not shown before for that exit. testLine (debug): show it now with that text.
     */
    private void updateCrashPrompt(String testLine) {
        String line = testLine, summary = null;
        long ts = testLine != null ? System.currentTimeMillis() : 0;
        DeviceReport.Exits e = testLine == null ? DeviceReport.exits(this) : null;
        if (e != null) {
            long[] ages = e.ages();
            int i = DeviceReport.newestProblem(e.reasons, e.importance, ages);
            long seen = getSharedPreferences("bridge", MODE_PRIVATE).getLong(ReportPrompt.PREF_CRASH_SEEN, 0);
            if (i >= 0 && ReportPrompt.crashDue(e.timestamps[i], seen)) {
                line = DeviceReport.exitLine(e.reasons[i], ages[i]);
                ts = e.timestamps[i];
                CrashRecord.Run r = CrashRecord.load(this, e.pids[i]);
                if (r != null) summary = DeviceReport.stoppedRunSummary(r, ts);
            }
        }
        if (line == null) return;
        crashTs = ts;
        crashText.setText(ReportPrompt.crashMessage(line, summary));
        if (crashCard.getVisibility() != View.VISIBLE) BLog.log("crash_prompt_shown", "exit", line);
        crashCard.setVisibility(View.VISIBLE);
    }

    /** Tapped (opens the report) or dismissed: either way this exit is answered and its card won't come back. */
    private void finishCrashPrompt(boolean report) {
        getSharedPreferences("bridge", MODE_PRIVATE).edit().putLong(ReportPrompt.PREF_CRASH_SEEN, crashTs).apply();
        BLog.log("crash_prompt_" + (report ? "tapped" : "dismissed"));
        crashCard.setVisibility(View.GONE);
        if (report) DeviceReport.open(this);
    }

    /**
     * Debug builds: act as if this phone and scanner had no compatibility report (the first-scan card and the
     * unverified-scanner warning), to test them on a covered setup. --ez pretend_unreported true|false, persisted.
     */
    private boolean pretendUnreported() {
        return BuildConfig.DEBUG && getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("pretend_unreported", false);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
            boolean real = scannerOnUsb();
            BLog.log("activity_usb_attached", "scanner_present", real);
            if (real) startBridge();
        }
        // Everything below is a test hook for adb (`am start ... --ez autostart true` etc.). MainActivity is exported
        // (launcher + USB intent filter), so other apps could send these too: debug builds only.
        if (!BuildConfig.DEBUG) return;
        // --ei mirror_port 57318 (auto-connect target port), --ez mirror_auto false,
        // --ez mirror_auto_ui true (let the dashboard's watcher auto-connect too); --ez mirror_reset true clears them
        {
            android.content.SharedPreferences.Editor e = getSharedPreferences("bridge", MODE_PRIVATE).edit();
            if (i.getBooleanExtra("mirror_reset", false)) e.remove("mirror_auto_port").remove("mirror_auto_ui");
            if (i.hasExtra("mirror_port")) e.putInt("mirror_auto_port", i.getIntExtra("mirror_port", 57308));
            if (i.hasExtra("mirror_auto")) e.putBoolean("mirror_auto", i.getBooleanExtra("mirror_auto", true));
            if (i.hasExtra("mirror_auto_ui")) e.putBoolean("mirror_auto_ui", i.getBooleanExtra("mirror_auto_ui", false));
            // --ez test_pattern true: synthetic frames when no scanner is attached, GVCP off (stall/takeover tests)
            if (i.hasExtra("test_pattern")) e.putBoolean("test_pattern", i.getBooleanExtra("test_pattern", false));
            // --ez report_prompt_test true: show the "First scan go well?" card now (as if nothing were reported);
            // --ez report_prompt_reset true: forget that it was answered
            if (i.getBooleanExtra("report_prompt_reset", false)) e.remove(ReportPrompt.PREF_DONE);
            if (i.getBooleanExtra("report_prompt_test", false)) promptTest = true;
            // --ez crash_prompt_test true: show the "stopped unexpectedly" card now, with a made-up crash
            if (i.getBooleanExtra("crash_prompt_test", false) && crashCard != null)
                updateCrashPrompt(DeviceReport.exitLine(4, 5 * 60_000L));
            // --ez crash_prompt_reset true: forget which crash it was last shown for
            if (i.getBooleanExtra("crash_prompt_reset", false)) e.remove(ReportPrompt.PREF_CRASH_SEEN);
            if (i.hasExtra("pretend_unreported")) e.putBoolean("pretend_unreported", i.getBooleanExtra("pretend_unreported", false));
            e.apply();
            // --es update_repo owner/name points the GitHub update check elsewhere ("" resets), --ez update_check true runs it
            if (i.hasExtra("update_repo")) Updates.setRepoOverride(this, i.getStringExtra("update_repo"));
            if (i.getBooleanExtra("update_check", false)) Updates.checkNow(this);
            if (autoSwitch != null) autoSwitch.setChecked(getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("mirror_auto", true));
            // --es fps_mode stock|boost|max; --ei fps_floor 20 sets any floor (wins over the mode, 0 removes it)
            if (i.hasExtra("fps_mode")) setFpsMode(i.getStringExtra("fps_mode"));
            if (i.hasExtra("fps_floor")) {
                int fps = i.getIntExtra("fps_floor", 0);
                Bridge b = BridgeService.current();
                if (b != null) new Thread(() -> b.putStreamMap("fps_floor", fps > 0 ? fps : null), "fps_floor").start();
                else putStreamMapOffline("fps_floor", fps > 0 ? fps : null);
            }
        }
        if (i.getBooleanExtra("show_session_stats", false) && BridgeService.current() == null
                && SessionStats.cur.stopMs == 0) SessionStats.cur.stopMs = SessionStats.cur.startMs;
        // --es mirror_host 192.168.1.20 opens the mirror viewer against that PC
        if (i.hasExtra("mirror_host")) {
            String mh = i.getStringExtra("mirror_host");
            i.removeExtra("mirror_host");                 // one-shot
            h.postDelayed(() -> openMirror(mh == null || mh.isEmpty() ? null : mh), 500);
        }
        if (i.getBooleanExtra("autostart", false)) startBridge();
        if (i.hasExtra("p2p")) {
            boolean on = i.getBooleanExtra("p2p", false);
            setP2p(on);
            Bridge b = BridgeService.current();
            if (b != null) new Thread(() -> b.putStreamMap("advertise", on ? "p2p" : ""), "advertise").start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] names, int[] res) {
        perms.onResult(code);
        h.postDelayed(this::refresh, 200);
        Bridge b = BridgeService.current();
        if (b != null) new Thread(b::scanUsb, "usb-rescan").start();
    }

    /** Persist the Wi-Fi Direct choice; applied now if the bridge runs, else at its next start. */
    private void setP2p(boolean on) {
        getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("p2p", on).apply();
        Bridge b = BridgeService.current();
        if (b != null) b.setP2p(on);
        h.postDelayed(this::refresh, 300);
    }

    private void startBridge() {
        Intent s = new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START);
        startForegroundService(s);
    }

    private void buildStartStop(LinearLayout c) {
        startStop = new LinearLayout(this);
        startStop.setGravity(Gravity.CENTER);
        startStop.setMinimumHeight(dp(52));
        startStop.setPadding(dp(14), dp(6), dp(14), dp(6));
        startStop.setClickable(true);
        startStop.setFocusable(true);
        ssSpin = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        ssSpin.setIndeterminate(true);
        ssSpin.setIndeterminateTintList(ColorStateList.valueOf(TEXT));
        ssSpin.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(20), dp(20));
        sl.rightMargin = dp(10);
        startStop.addView(ssSpin, sl);
        ssText = text(17, TEXT, true);
        startStop.addView(ssText);
        startStop.setOnClickListener(v -> {
            if (ssBusy()) return;
            if (BridgeService.current() == null && usb2Block(last)) return;
            if (BridgeService.current() != null) {
                ssPending(false);
                startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
            } else {
                perms.ensure(PermissionGuide.CAMERA, () -> {   // explains Camera first if it's missing
                    ssPending(true);
                    startBridge();
                });
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.addView(startStop, lp);
        renderStartStop();
    }

    private void ssPending(boolean want) {
        ssWant = want;
        ssSentAt = BridgeService.commands;
        ssPendingUntil = android.os.SystemClock.uptimeMillis() + 5000;
        renderStartStop();
        h.removeCallbacks(ssPoll);
        h.postDelayed(ssPoll, 150);
    }

    /** Start or Stop still in flight: tapped but not picked up yet, or the service is still bringing it up/down. */
    private boolean ssBusy() {
        if (BridgeService.switching()) return true;
        if (ssSentAt < 0) return false;
        if (BridgeService.commands != ssSentAt || android.os.SystemClock.uptimeMillis() > ssPendingUntil) {
            ssSentAt = -1;               // picked up (or a failed start already finished), or gave up waiting
            return false;
        }
        return true;
    }

    private void renderStartStop() {
        boolean running = BridgeService.current() != null;
        boolean busy = ssBusy();
        boolean usb2 = !running && !busy && usb2Block(last);
        String label;
        int bg;
        if (usb2) {
            label = "Can't start: scanner is on USB 2";
            bg = WARN_AMBER;
        } else if (busy) {
            boolean stoppingNow = BridgeService.stopping || (!BridgeService.starting && !ssWant);
            label = stoppingNow ? "Stopping…" : "Starting…";
            bg = LINE;
        } else if (running) {
            label = "Stop bridge";
            bg = WARN_RED;
        } else {
            label = "Start bridge";
            bg = 0xFF1D4A2A;                 // dark green, like the mirror viewer's full-rate chips
        }
        ssText.setText(label);
        ssText.setTextColor(usb2 ? AMBER : TEXT);
        ssSpin.setVisibility(busy ? View.VISIBLE : View.GONE);
        startStop.setEnabled(!busy && !usb2);
        startStop.setAlpha(busy ? 0.75f : 1f);
        startStop.setBackground(rounded(bg, dp(12)));
        startStop.setContentDescription(label);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
        if (ssBusy()) h.post(ssPoll);
        if (BridgeService.current() == null) uiWatch.start();   // readiness for the Mirror button even with the bridge off
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        h.removeCallbacks(ssPoll);
        uiWatch.stop();
    }

    // ------------------------------------------------------------------ refresh
    private static String f1(float v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static void show(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    static final String STOCK_NOTE = "Creality Scan Bridge default";
    static final String BOOST_NOTE = "the scanner's rate closest to halfway between Stock and Max";
    static final String MAX_LINK = "needs a very fast and stable link (Wi-Fi 6E/7 or Gigabit Ethernet)";
    private TextView fpsTitle, fpsNote;
    private RadioGroup fpsGroup;
    private boolean fpsUpdating;
    private String fpsKey = "";

    /**
     * Saves the scanner frame-rate mode (stream_map.json "fps_mode") and drops any test "fps_floor" so the choice
     * wins. Creality Scan picks it up on its next connection, even while the bridge runs.
     */
    private void setFpsMode(String mode) {
        String m = FpsChoice.normalize(mode);
        BLog.log("fps_mode_set", "mode", m, "bridge_running", BridgeService.current() != null);
        Object v = FpsChoice.STOCK.equals(m) ? null : m;
        Bridge b = BridgeService.current();
        if (b != null) new Thread(() -> { b.putStreamMap("fps_floor", null); b.putStreamMap("fps_mode", v); }, "fps_mode").start();
        else { putStreamMapOffline("fps_floor", null); putStreamMapOffline("fps_mode", v); }
        fpsKey = "";
        h.postDelayed(this::refresh, 150);
    }

    /** Labels with the attached scanner's rates, and the saved mode checked; rebuilt only when something changes. */
    private void refreshFps(Dash.Snap s) {
        FpsChoice c = s.fpsChoice;
        String saved = s.fpsMode;
        String[] label = new String[3];
        boolean[] offered = {true, c == null || c.boost > 0, c == null || c.max > 0};
        if (c == null) {
            label[0] = "Stock: " + STOCK_NOTE;
            label[1] = "Boost: " + BOOST_NOTE;
            label[2] = "Max: " + MAX_LINK;
        } else {
            label[0] = "Stock · " + (c.stock > 0 ? c.stock + " fps · ~" + c.mbps(c.stock) + " Mbit/s · " : "") + STOCK_NOTE;
            label[1] = "Boost · " + c.boost + " fps · ~" + c.mbps(c.boost) + " Mbit/s · " + BOOST_NOTE;
            label[2] = "Max · " + c.max + " fps · ~" + c.mbps(c.max) + " Mbit/s · " + MAX_LINK;
        }
        boolean none = c != null && c.max == 0 && c.boost == 0;
        // Creality Scan reads the rate list (0xFC1) when it connects, so a change while a PC is connected wouldn't
        // apply until it reconnects: lock it like the network choice.
        boolean locked = s.pcs > 0;
        String note = c == null ? "Actual rates show once the scanner is connected and the bridge is running."
                : none ? "This scanner already streams at its top rate."
                : locked ? "Creality Scan is connected. Close it to change the frame rate."
                : "Applies the next time Creality Scan connects. The bridge can keep running.";
        if (c != null && c.width > 0 && !s.fpsOthers.isEmpty()) note = note + "\n" + FpsChoice.othersNote(c, s.fpsOthers);
        fpsTitle.setText(locked ? "Scanner frame-rate override (close Creality Scan to change)" : "Scanner frame-rate override");
        String key = saved + "|" + String.join("|", label) + "|" + offered[1] + offered[2] + "|" + note + "|" + locked;
        if (key.equals(fpsKey)) return;
        fpsKey = key;
        fpsNote.setText(note);
        // a mode this scanner doesn't offer runs as Stock, or Max as Boost (FpsChoice.floorFor), so show it that way
        String effective = c == null ? FpsChoice.normalize(saved) : c.effective(saved);
        fpsUpdating = true;
        for (int i = 0; i < 3; i++) {
            RadioButton rb = (RadioButton) fpsGroup.getChildAt(i);
            rb.setText(label[i]);
            show(rb, offered[i]);
            rb.setEnabled(!locked);
            rb.setAlpha(locked && !rb.getTag().equals(effective) ? 0.4f : 1f);
            if (rb.getTag().equals(effective)) rb.setChecked(true);
        }
        fpsUpdating = false;
    }

    /** stream_map.json "fps_mode" read directly (the bridge isn't running). */
    private String savedFpsMode() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "stream_map.json");
            if (f.exists()) return FpsChoice.normalize(new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(
                    f.toPath()), java.nio.charset.StandardCharsets.UTF_8)).optString("fps_mode", FpsChoice.STOCK));
        } catch (Exception ignored) {
        }
        return FpsChoice.STOCK;
    }

    /** stream_map.json "advertise" read directly (the bridge isn't running); "" = automatic. */
    private String savedChoice() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "stream_map.json");
            if (f.exists()) {
                byte[] buf = java.nio.file.Files.readAllBytes(f.toPath());
                String v = new org.json.JSONObject(new String(buf, java.nio.charset.StandardCharsets.UTF_8)).optString("advertise", "");
                if (Bridge.isChoice(v)) return v;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // Bridge off: the dashboard still shows the networks and the saved choice. Sampling them reads a file,
    // lists the network interfaces and asks the Wi-Fi service, so it runs on this thread, not the UI thread.
    private final java.util.concurrent.ExecutorService idleSampler = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean idleSampling = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Dash.Snap idleSnap;

    private Dash.Snap sampleIdle() {
        Dash.Snap s = new Dash.Snap();
        s.events = java.util.Collections.emptyList();
        Dash.sampleWifi(this, s);
        Dash.sampleInterfaces(s);
        s.advertiseSaved = savedChoice();
        s.fpsMode = savedFpsMode();
        s.p2pWanted = getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("p2p", false);
        s.advertise = Bridge.resolveAdvertise(s.advertiseSaved, s.p2pWanted, Bridge.networksUp());
        Dash.sampleScannerIdle(this, s);
        return s;
    }

    @Override
    protected void onDestroy() {
        idleSampler.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        render(true);
    }

    /**
     * Bridge off and the plugged-in scanner's descriptors say USB 2: Start stays disabled. USB 3 is required (the
     * streams need more than USB 2 carries), and a USB 3 device at SuperSpeed always reports USB 3, so this
     * doesn't misfire on a good link. A running bridge isn't stopped if the link drops; it shows the warning instead.
     */
    static boolean usb2Block(Dash.Snap s) {
        return s != null && !s.scanner && s.plugProbed && !s.usbSuper;
    }

    /** "CR-Scan Otter · SN BCA… · fw 1.2.3 · USB 3", or what's missing in plain words. */
    static String scannerLine(Dash.Snap s, boolean running) {
        if (s.scanner || (!running && s.plugProbed)) {
            StringBuilder t = new StringBuilder(s.scannerModel);
            if (s.scannerSn != null && !s.scannerSn.isEmpty()) t.append(" · SN ").append(s.scannerSn);
            if (s.scannerFw != null && !s.scannerFw.isEmpty()) t.append(" · fw ").append(s.scannerFw);
            if (s.usbSuper) return t.append(" · USB 3").toString();
            return t.append(running ? " · USB 2 (too slow)"
                    : " · connected at USB 2 speed, which is too slow. Use a USB 3 cable, port and hub.").toString();
        }
        if (!running) return s.plugged ? s.scannerModel + " plugged in · Android asks for access at Start"
                : "No scanner plugged in · you can start now and plug it in later";
        if ("Not connected".equals(s.scannerState)) return "No scanner plugged in";
        if (s.scannerState != null && s.scannerState.startsWith("Attached")) return "Scanner plugged in · waiting for USB access (tap OK on Android's prompt)";
        return "Scanner: " + s.scannerState;
    }

    /**
     * Updates the views. Bridge off: shows the latest idle snapshot right away and, with resample, starts a fresh
     * one in the background that renders again when done (without resampling, so it can't loop). The very first
     * render samples inline so the screen never starts half-empty.
     */
    private void render(boolean resample) {
        Bridge b = BridgeService.current();
        Dash.Snap s = b != null ? b.dash.snap : null;
        if (s == null) {
            if (idleSnap == null) {
                idleSnap = sampleIdle();
            } else if (resample && idleSampling.compareAndSet(false, true)) {
                try {
                    idleSampler.execute(() -> {
                        try {
                            idleSnap = sampleIdle();
                        } finally {
                            idleSampling.set(false);
                        }
                        h.post(() -> { if (!isFinishing() && !isDestroyed()) render(false); });
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {   // activity being destroyed
                    idleSampling.set(false);
                }
            }
            s = idleSnap;
        }
        last = s;
        DeviceReport.lastSnap = s;
        boolean running = b != null;
        updateReportPrompt(s, running);
        applyKeepAwake(running);               // follows Start/Stop (refresh runs every second while visible)

        // ---- header: status + scanner + power
        orb.set(heroColor(running ? s.hero : Dash.OFF), running && s.hero == Dash.STREAMING);
        heroText.setText(running ? s.heroText : "Bridge off");
        String sub = running ? s.heroSub : "Tap Start to advertise the scanner";
        heroSub.setText(sub);
        show(heroSub, sub != null && !sub.isEmpty());
        renderStartStop();
        boolean pcOn = running && (s.hero == Dash.PC || s.hero == Dash.STREAMING);
        // Readiness = the PC's UDP 57301 broadcast (only while its "Mobile connection" dialog is open and no
        // phone is attached). Never probe TCP 57308: the PC treats any accept as a phone connecting.
        String bh = b != null ? b.mirrorWatch.recentHost(4000) : null;
        if (bh == null) bh = uiWatch.recentHost(4000);
        MirrorSession ms = MirrorSession.get(this);
        if (ms.isActive()) {
            mirrorHost = null;
            mirrorBtn.setText("Mirror · connected, tap to view");
            mirrorBtn.setAlpha(1f);
        } else if (ms.isConnecting()) {
            mirrorHost = null;
            mirrorBtn.setText("Mirror · connecting…");
            mirrorBtn.setAlpha(1f);
        } else if (bh != null) {
            mirrorHost = bh;
            mirrorBtn.setText("Mirror Creality Scan on this phone");
            mirrorBtn.setAlpha(1f);
        } else {
            mirrorHost = null;
            mirrorBtn.setText("Mirror · waiting: press \"Mobile connection\" in Creality Scan");
            mirrorBtn.setAlpha(0.45f);     // still tappable: the viewer has a manual "PC…" for networks without broadcasts
        }
        mirrorBtn.setEnabled(true);
        if (!running && usb2Block(s)) {                // link to the USB 2 fixes; the whole line is the tap target
            android.text.SpannableStringBuilder t = new android.text.SpannableStringBuilder(scannerLine(s, false)).append(' ');
            int st = t.length();
            t.append("Already on USB 3? More fixes ›");
            t.setSpan(new android.text.style.ForegroundColorSpan(BLUE), st, t.length(), 0);
            scanLine.setText(t);
            scanLine.setOnClickListener(v -> help(Docs.HELP_USB2));
        } else {
            scanLine.setText(scannerLine(s, running));
            scanLine.setOnClickListener(null);
            scanLine.setClickable(false);
        }
        boolean details = s.scanner || (!running && s.plugProbed);
        scanLine.setTextColor(details && !s.usbSuper ? AMBER : details || s.plugged ? TEXT : SUB);
        powerLine.setText(s.battery >= 0 ? "Battery " + s.battery + "% · " + (s.powered ? s.powerSource : "on battery") : "");
        List<String[]> warns = new ArrayList<>();
        // missing permissions: tap opens the explanation card (or Settings if Android won't prompt again)
        boolean askable = !perms.dialogShowing();       // no pills behind an open explanation card
        if (askable && perms.seen(PermissionGuide.CAMERA) && !perms.granted(PermissionGuide.CAMERA))
            warns.add(new String[]{"Camera permission is off, so CR-BYOB can't open the scanner (it's a USB camera). Tap to fix.", "red", "perm:camera"});
        if (askable && running && !s.scanner && s.plugged && "Attached, waiting for USB permission".equals(s.scannerState))
            warns.add(new String[]{"Scanner needs USB authorization. Tap to prompt.", "amber", "perm:usb"});
        if (askable && running && perms.seen(PermissionGuide.NOTIFY) && !perms.granted(PermissionGuide.NOTIFY))
            warns.add(new String[]{"Notifications are off: the bridge's status and Stop button are hidden. Tap to fix.", "amber", "perm:notify"});
        if (running && s.brownOut) warns.add(new String[]{"Scanner lost power while the phone was on battery: use a powered USB-C hub", "red", "doc:" + Docs.HELP_POWER});
        else if (running && !s.powered && s.battery >= 0) warns.add(new String[]{"No external power: scanner may disconnect. Use a powered hub.", "red", "doc:" + Docs.HELP_POWER});
        String modelStatus = pretendUnreported() && s.scanner ? "unverified" : s.modelStatus;
        if (s.scanner && modelStatus != null && !"verified".equals(modelStatus))
            warns.add(new String[]{s.modelName + " support is " + modelStatus + " (not tested with this bridge): streams may be wrong.", "amber", "report"});   // the reports it needs
        // Wi-Fi speed warnings, bridge on or off; each only matters for the network the server runs on
        for (String[] w : Dash.staWarnings(s)) if (w[1].equals(s.advertise))
            warns.add(new String[]{w[0], "amber", "doc:" + ("p2p".equals(w[1]) ? Docs.HELP_DUAL : Docs.HELP_SLOW)});
        setWarnings(warns);

        // ---- streams + link only while a PC is connected or streaming
        boolean active = running && (s.hero == Dash.PC || s.hero == Dash.STREAMING);
        show(streamsCard, active);
        show(linkCard, active);
        show(waitCard, running && !active && s.scanner);
        waitText.setText("local".equals(s.advertise) ? "Waiting for the CrealityScan app" : "Waiting for PC");
        if (active) refreshStreams(s);
        refreshSession();

        // ---- network
        boolean p2pOn = getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("p2p", false);
        show(p2pBox, p2pOn);
        if (p2pOn) {
            String[] cr = running && s.p2pSsid != null ? new String[]{s.p2pSsid, s.p2pPass} : p2pAccess().credentials();
            p2pSsid.setText(cr[0] != null ? cr[0] : "…");
            p2pPass.setText(cr[1] != null ? cr[1] : "…");
            // the network exists only while the bridge runs: status pill, dimmed details and hint say so
            String st = s.p2pState == null ? "off" : s.p2pState;
            boolean up = running && "up".equals(st);
            boolean failed = running && (st.startsWith("failed") || st.equals("no permission") || st.equals("unsupported"));
            String status;
            int bg;
            if (!running) {
                status = "○  Not on yet. This network only exists while the bridge is running: tap Start to turn it on.";
                bg = LINE;
            } else if (up) {
                status = "●  On" + (s.p2pFreq > 0 ? " · " + Dash.band(s.p2pFreq) + " ch " + Dash.channel(s.p2pFreq) : "")
                        + " · " + (s.p2pClients == 0 ? "no devices joined yet" : s.p2pClients == 1 ? "1 device joined" : s.p2pClients + " devices joined");
                bg = 0xFF1F4A2C;
            } else if (failed) {
                status = "✕  Wi-Fi Direct couldn't start (" + st + "). Stop and Start the bridge to retry.";
                bg = WARN_RED;
            } else {
                status = "◐  Starting Wi-Fi Direct…";
                bg = WARN_AMBER;
            }
            p2pStatus.setText(status);
            p2pStatus.setBackground(rounded(bg, dp(10)));
            p2pSsid.setAlpha(up ? 1f : 0.55f);
            p2pPass.setAlpha(up ? 1f : 0.55f);
            p2pHint.setText(withLink((up ? "On the PC, join this network" : "After you tap Start, join this network on the PC")
                    + " from the Wi-Fi menu (taskbar on Windows), like any other Wi-Fi network. Creality Scan's own"
                    + " Wi-Fi list won't show it: once the PC has joined, the bridge shows up in Creality Scan by itself.",
                    "Why isn't it in Creality Scan ›", false));
            editBtn.setEnabled(!running);
            editBtn.setAlpha(running ? 0.4f : 1f);
            editBtn.setText(running ? "Edit Wi-Fi Direct settings (stop the bridge first)" : "Edit Wi-Fi Direct settings");
        }
        // header pill while the server runs on Wi-Fi Direct
        boolean hdr = p2pOn && running && "p2p".equals(s.advertise);
        show(clientChip, hdr);
        if (hdr) setClientChip(clientChip, s);
        show(p2pDualBanner, p2pOn && running && s.staConnected);
        if (p2pOn && s.staConnected) {
            p2pDual.setText(withLink("⚠  " + dualWarning(s.staSsid), "How to fix ›", false));
            p2pDualBtn.setText("Disconnect phone from " + s.staSsid);
        }
        show(wifiBox, s.staConnected);
        if (s.staConnected) {
            wifiBars.set(s.staBars, Dash.barsColor(s.staBars));
            String rate = s.staTx > 0 && s.staRx > 0 ? "↑" + s.staTx + " ↓" + s.staRx : String.valueOf(s.staTx > 0 ? s.staTx : s.staLink);
            wifiLine.setText(s.staSsid + " · " + (s.staStd > 0 ? "Wi-Fi " + s.staStd + " · " : "") + Dash.band(s.staFreq)
                    + " ch " + Dash.channel(s.staFreq) + " · " + s.staRssi + " dBm · [" + rate + " Mbit/s link rate]");   // PHY rate, not throughput
        }
        // a working adapter is listed (with its IP) in the radio list; this line only explains a missing IP
        show(ethBox, s.ethIp == null && s.ethNoIp != null);
        if (s.ethIp == null && s.ethNoIp != null) {
            ethLine.setText(withLink("⚠  Ethernet (" + s.ethNoIp + ") is plugged in but has no IP address yet. "
                    + "Check the cable goes to a router or switch with DHCP, turn Wi-Fi off for a few seconds, "
                    + "or unplug and replug the adapter.",
                    "How to fix ›", true));
        }
        refreshAdvertise(s, p2pOn);
        refreshFps(s);

        // ---- events
        eventsHeader.setText(eventsOpen ? "EVENTS ▾" : "EVENTS ▸");
        eventsBox.removeAllViews();
        if (eventsOpen) {
            if (s.events.isEmpty()) {
                TextView t = text(14, SUB, false);
                t.setText("No events yet");
                eventsBox.addView(t);
            }
            for (String e : s.events) {
                TextView t = text(14, TEXT, false);
                t.setText(e);
                t.setPadding(0, dp(2), 0, dp(2));
                eventsBox.addView(t);
            }
        }
        if (logOpen) {
            StringBuilder sb = new StringBuilder();
            for (String l : BLog.recent(80)) sb.append(l).append('\n');
            logView.setText(sb);
        }
    }

    private void refreshStreams(Dash.Snap s) {
        for (int i = 0; i < 4; i++) {
            Dash.Stream st = s.streams[i];
            if (st == null) continue;
            if (i < 3) {
                if (st.active) {
                    stVal[i].setText(f1(st.fpsSent) + " / " + f1(st.fpsCap) + " fps");
                    stSub[i].setText(f1(st.mbps) + " Mbit/s · " + (st.profile != null ? st.profile : "") + " · sent / captured");
                } else {
                    stVal[i].setText("idle");
                    stSub[i].setText("not opened by the PC");
                }
                boolean dec = st.active && st.k > 1;
                show(stBadge[i], dec);
                stBadge[i].setText((st.k == 2 ? "½ rate" : "⅓ rate") + Dash.recoverLabel(s.irRecoverMs, st.k));
                // amber = frames lost (queue overflow) or IR decimated. A short stall only delays frames: they stay
                // queued and go out a moment later, so sent < captured within one second alone isn't a warning.
                int col = !st.active ? GREY : st.k > 1 || st.lost > 0 ? AMBER : GREEN;
                stSpark[i].set(st.hist, Math.max(15, st.fpsReq), col);
            } else {
                stVal[i].setText(st.fpsSent > 0.5f ? Math.round(st.fpsSent) + " reports/s" : st.active ? "connected, no data" : "idle");
                stSub[i].setText(st.fpsSent > 0.5f ? f1(st.mbps) + " Mbit/s · accel + gyro + button" : "starts when the app enables the IMU");
                show(stBadge[i], false);
                stSpark[i].set(st.hist, 120, st.fpsSent > 0.5f ? BLUE : GREY);
            }
        }
        totalMbps.setText("Total " + f1(s.totalMbps) + " Mbit/s · "
                + String.format(Locale.US, "%.2f GB", s.totalBytes / 1e9) + " sent");
        linkBars.set(Math.max(0, s.linkScore), scoreColor(s.linkScore));
        linkWord.setText(s.linkScore >= 0 ? s.linkWord : "No stream yet");
        boolean weak = s.linkScore >= 0 && s.linkScore <= 2;          // No signal, Poor, Marginal
        String detail = s.linkScore >= 0 ? s.linkDetail
                : "Estimated from delivery (sent vs captured frames, drops, decimation) once streaming starts. "
                + "Android gives no per-client signal for a Wi-Fi Direct group owner.";
        linkDetail.setText(weak ? withLink(detail, "How to improve ›", false) : detail);
        linkTo(linkDetail, weak ? Docs.HELP_SLOW : null);
    }

    /** Radio buttons built from the interfaces that are up (one network only); rebuilt only when the set changes. */
    private void refreshAdvertise(Dash.Snap s, boolean p2pOn) {
        List<String[]> opts = new ArrayList<>();
        opts.add(new String[]{"p2p", p2pOn ? advLabel("p2p", s) : "Wi-Fi Direct (phone makes its own network)"});
        if (s.staConnected) opts.add(new String[]{"wifi", advLabel("wifi", s)});
        if (s.ethIp != null) opts.add(new String[]{"eth", advLabel("eth", s)});
        opts.add(new String[]{"local", advLabel("local", s)});       // needs no network: always offered
        boolean present = false;
        for (String[] o : opts) if (o[0].equals(s.advertise)) present = true;
        if (!present) opts.add(new String[]{s.advertise, advLabel(s.advertise, s)});
        // saved network down: say what is used instead (it switches back by itself)
        boolean fb = !s.advertiseSaved.isEmpty() && !s.advertiseSaved.equals(s.advertise);
        show(advNote, fb);
        if (fb) advNote.setText(withLink("⚠  " + netWord(s.advertiseSaved, s) + " isn't connected, so the bridge will start on "
                + netWord(s.advertise, s) + ". Connect " + netWord(s.advertiseSaved, s) + " before starting to use it.",
                "About networks ›", true));
        boolean locked = BridgeService.current() != null;
        advTitle.setText(locked ? "Streaming server runs on (stop the bridge to change)" : "Run streaming server on");
        if (locked) show(advNote, false);     // no switching while running
        StringBuilder key = new StringBuilder(s.advertise + (locked ? "|L" : "|U"));
        for (String[] o : opts) key.append('|').append(o[0]).append('=').append(o[1]);
        if (key.toString().equals(advKey)) return;
        advKey = key.toString();
        advUpdating = true;
        advGroup.removeAllViews();
        for (String[] o : opts) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(o[0]);
            rb.setText(o[1]);
            rb.setTextColor(TEXT);
            rb.setEnabled(!locked);
            rb.setAlpha(locked && !o[0].equals(s.advertise) ? 0.4f : 1f);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            rb.setButtonTintList(ColorStateList.valueOf(BLUE));
            rb.setPadding(dp(4), dp(6), 0, dp(6));
            advGroup.addView(rb);
            if (o[0].equals(s.advertise)) rb.setChecked(true);
        }
        advUpdating = false;
    }
}

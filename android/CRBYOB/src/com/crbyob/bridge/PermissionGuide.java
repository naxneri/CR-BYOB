package com.crbyob.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static com.crbyob.bridge.Widgets.SUB;
import static com.crbyob.bridge.Widgets.TEXT;

/**
 * Explains each runtime permission before Android's own prompt asks for it, so the app doesn't open with a burst of
 * unexplained requests. First launch walks through the ones not seen yet (one card each, then the system prompt);
 * afterwards they're asked in context: Camera when the bridge starts, Nearby Wi-Fi (Location before 13) when Wi-Fi
 * Direct is chosen, and from the header warning pills. A permission Android won't prompt for again (denied twice /
 * "don't ask again") gets an "Open settings" button instead.
 */
final class PermissionGuide {
    static final int REQ = 4201;

    static final class Item {
        final String perm, key, icon, title, why, without;
        final boolean required;
        final int minSdk;

        Item(String perm, int minSdk, String key, String icon, String title, String why, String without, boolean required) {
            this.perm = perm; this.minSdk = minSdk; this.key = key; this.icon = icon; this.title = title;
            this.why = why; this.without = without; this.required = required;
        }
    }

    static final Item CAMERA = new Item("android.permission.CAMERA", 0, "camera", "📷",
            "Open the scanner over USB",
            "Your scanner plugs in as a USB camera, and Android only lets an app open USB cameras if it holds the "
                    + "Camera permission. CR-BYOB never uses the phone's own cameras.",
            "Without it, CR-BYOB can't talk to the scanner at all.", true);

    static final String PERM_HORIZON_USB_CAMERA = "horizonos.permission.USB_CAMERA";

    static boolean hasHorizonUsbCamera(android.content.Context ctx) {
        try {
            ctx.getPackageManager().getPermissionInfo(PERM_HORIZON_USB_CAMERA, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
    static final Item NOTIFY = new Item("android.permission.POST_NOTIFICATIONS", 33, "notify", "🔔",
            "Show the bridge's status notification",
            "While the bridge runs, Android requires it to show a notification. It shows the bridge's status and a "
                    + "Stop button. CR-BYOB sends no other notifications.",
            "The bridge still works, but you won't see it or be able to stop it from the notification shade.", false);
    static final Item NEARBY = new Item("android.permission.NEARBY_WIFI_DEVICES", 33, "nearby", "📶",
            "Create a Wi-Fi Direct network",
            "In Wi-Fi Direct mode the phone makes its own network for your PC to join. Android lists that under "
                    + "“Nearby devices”. CR-BYOB doesn't use it to find or track anything around you.",
            "Wi-Fi Direct mode won't work. Home Wi-Fi and Ethernet still do.", false);
    static final Item LOCATION = new Item("android.permission.ACCESS_FINE_LOCATION", 0, "location", "📍",
            "See which Wi-Fi network you're on",
            "Android hides the name of the connected Wi-Fi network"
                    + (Build.VERSION.SDK_INT < 33 ? " and Wi-Fi Direct" : "")
                    + " behind the Location permission. CR-BYOB uses it only to show the network name and to warn "
                    + "about weak or 2.4 GHz Wi-Fi. It never reads, stores or sends your location.",
            "The network name shows as unknown and the Wi-Fi speed warnings can't appear."
                    + (Build.VERSION.SDK_INT < 33 ? " Wi-Fi Direct mode won't work." : ""), false);

    /** Asked on first launch, in this order. Nearby Wi-Fi is left for when Wi-Fi Direct is chosen. */
    private static final Item[] FIRST_RUN = {CAMERA, NOTIFY, LOCATION};

    private final Activity act;
    private final SharedPreferences prefs;
    private final ArrayDeque<Runnable> afterResult = new ArrayDeque<>();
    private AlertDialog showing;

    PermissionGuide(Activity act) {
        this.act = act;
        this.prefs = act.getSharedPreferences("permissions", Activity.MODE_PRIVATE);
    }

    static boolean applies(Item it) {
        return Build.VERSION.SDK_INT >= it.minSdk;
    }

    boolean granted(Item it) {
        if (!applies(it)) return true;
        if (act.checkSelfPermission(it.perm) != PackageManager.PERMISSION_GRANTED) return false;
        if (it == CAMERA && hasHorizonUsbCamera(act)) {
            return act.checkSelfPermission(PERM_HORIZON_USB_CAMERA) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** The permission Wi-Fi Direct needs on this Android version. */
    static Item p2pItem() {
        return Build.VERSION.SDK_INT >= 33 ? NEARBY : LOCATION;
    }

    /** Android won't show its prompt any more: asked before, denied, and no rationale -> settings only. */
    private boolean blocked(Item it) {
        if (granted(it)) return false;
        if (!prefs.getBoolean("asked_" + it.key, false)) return false;
        boolean rationale = act.shouldShowRequestPermissionRationale(it.perm);
        if (it == CAMERA && hasHorizonUsbCamera(act)) {
            boolean horizonRationale = act.shouldShowRequestPermissionRationale(PERM_HORIZON_USB_CAMERA);
            return !rationale && !horizonRationale;
        }
        return !rationale;
    }

    // ------------------------------------------------------------------ flows
    /** First launch: one card per permission not explained yet, then a note about the USB access prompt. */
    void firstRun() {
        List<Item> todo = new ArrayList<>();
        for (Item it : FIRST_RUN) if (!granted(it) && !prefs.getBoolean("seen_" + it.key, false)) todo.add(it);
        boolean usbNote = !prefs.getBoolean("seen_usb", false);
        if (todo.isEmpty() && !usbNote) return;
        chain(todo, 0, todo.size() + (usbNote ? 1 : 0), usbNote);
    }

    private void chain(List<Item> todo, int i, int total, boolean usbNote) {
        if (i < todo.size()) {
            explain(todo.get(i), (i + 1) + " of " + total, () -> chain(todo, i + 1, total, usbNote));
        } else if (usbNote) {
            usbNote(total > 1 ? total + " of " + total : null);
        }
    }

    /** Contextual: runs then right away if granted, else after the card (and the prompt, if they chose Continue). */
    void ensure(Item it, Runnable then) {
        if (granted(it)) {
            if (then != null) then.run();
            return;
        }
        explain(it, null, then);
    }

    private void explain(Item it, String step, Runnable then) {
        if (showing != null && showing.isShowing()) return;    // one card at a time; the user is mid-flow
        prefs.edit().putBoolean("seen_" + it.key, true).apply();
        boolean toSettings = blocked(it);
        LinearLayout body = card(it.icon, it.title, step);
        String whyText = it.why;
        if (it == CAMERA && hasHorizonUsbCamera(act)) {
            whyText = "Your scanner plugs in as a USB camera. Horizon OS and Android require camera and USB camera permissions so CR-BYOB can communicate with the scanner. CR-BYOB never uses the headset's internal cameras.";
        }
        body.addView(para(whyText, TEXT, 15, dp(10)));
        body.addView(para((it.required ? "Required. " : "Optional. ") + it.without, SUB, 13, dp(10)));
        if (toSettings) body.addView(para("Android won't ask again for this one, so turn it on in Settings › Permissions.", SUB, 13, dp(10)));
        final boolean[] decided = {false};
        final AlertDialog d = new AlertDialog.Builder(act)
                .setView(scroll(body))
                .setOnDismissListener(x -> { if (!decided[0] && then != null) then.run(); })   // back / tap outside = Not now
                .create();
        body.addView(buttons(
                button("Not now", false, v -> { decided[0] = true; d.dismiss(); if (then != null) then.run(); }),
                button(toSettings ? "Open settings" : "Continue", true, v -> {
                    decided[0] = true;
                    d.dismiss();
                    if (toSettings) {
                        openAppSettings();
                        if (then != null) then.run();
                    } else {
                        request(it, then);
                    }
                })));
        showing = d;
        d.show();
        Widgets.styleDialog(d);
    }

    /**
     * In-card buttons, styled like the dashboard's (AlertDialog's own button bar crops buttons this tall when the
     * dialog has a custom view).
     */
    private android.widget.Button button(String label, boolean primary, android.view.View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(primary ? Widgets.BG : TEXT);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(Widgets.roundRect(primary ? Widgets.BLUE : Widgets.LINE, dp(10)));
        b.setPadding(dp(16), dp(4), dp(16), dp(4));
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);              // flat, like the dashboard's buttons
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout buttons(android.widget.Button... bs) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, dp(2));
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(bs[i], lp);
        }
        return row;
    }

    /** True once the card for this permission has been shown (so the header pill doesn't nag before that). */
    boolean seen(Item it) {
        return prefs.getBoolean("seen_" + it.key, false);
    }

    boolean dialogShowing() {
        return showing != null && showing.isShowing();
    }

    private void usbNote(String step) {
        prefs.edit().putBoolean("seen_usb", true).apply();
        LinearLayout body = card("🔌", "Allow access to the scanner", step);
        body.addView(para("When you plug the scanner in, Android asks whether CR-BYOB may access it. Tap OK. "
                + "Tick “Always open CR-BYOB” first, and the app opens by itself whenever the scanner is plugged in.", TEXT, 15, dp(10)));
        body.addView(para("This covers the scanner only. Without the tick, Android asks again each time you plug it in.",
                SUB, 13, dp(10)));
        final AlertDialog d = new AlertDialog.Builder(act).setView(scroll(body)).create();
        body.addView(buttons(button("Got it", true, v -> d.dismiss())));
        showing = d;
        d.show();
        Widgets.styleDialog(d);
    }

    private void request(Item it, Runnable then) {
        prefs.edit().putBoolean("asked_" + it.key, true).apply();
        afterResult.add(then != null ? then : () -> { });
        // Android 12+ ignores a FINE location request that doesn't also ask for COARSE
        String[] perms;
        if (it == LOCATION) {
            perms = new String[]{it.perm, "android.permission.ACCESS_COARSE_LOCATION"};
        } else if (it == CAMERA && hasHorizonUsbCamera(act)) {
            perms = new String[]{it.perm, PERM_HORIZON_USB_CAMERA};
        } else {
            perms = new String[]{it.perm};
        }
        act.requestPermissions(perms, REQ);
    }

    /** From Activity.onRequestPermissionsResult; returns true if it was ours. */
    boolean onResult(int code) {
        if (code != REQ) return false;
        Runnable r = afterResult.poll();
        if (r != null) r.run();
        return true;
    }

    void openAppSettings() {
        act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", act.getPackageName(), null)));
    }

    // ------------------------------------------------------------------ views
    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private TextView para(String s, int color, int sp, int top) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, top, 0, 0);
        return t;
    }

    private LinearLayout card(String icon, String title, String step) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(18), dp(20), dp(14));
        if (step != null) {                      // the dashboard's section-title style
            TextView s = para("PERMISSION " + step.toUpperCase(java.util.Locale.US), SUB, 13, 0);
            s.setTypeface(Typeface.DEFAULT_BOLD);
            s.setLetterSpacing(0.08f);
            c.addView(s);
        }
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, step != null ? dp(10) : 0, 0, 0);
        TextView ic = para(icon, TEXT, 22, 0);      // icon in a rounded badge, like the dashboard's buttons
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Widgets.roundRect(Widgets.LINE, dp(12)));
        row.addView(ic, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView t = para(title, TEXT, 18, 0);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(14), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        c.addView(row);
        return c;
    }

    private ScrollView scroll(LinearLayout body) {
        ScrollView sv = new ScrollView(act);
        sv.addView(body);
        return sv;
    }
}

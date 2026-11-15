package com.crbyob.bridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Small Canvas views for the dashboard (no external libraries). */
final class Widgets {
    static final int BG = 0xFF0E1116, CARD = 0xFF171B22, LINE = 0xFF2A313C, TEXT = 0xFFE6EDF3, SUB = 0xFF8B949E,
            GREEN = 0xFF3FB950, BLUE = 0xFF58A6FF, AMBER = 0xFFD29922, RED = 0xFFF85149, GREY = 0xFF6E7681,
            PURPLE = 0xFFA371F7;

    private Widgets() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Styles t as a small outlined badge: colored border and text, no fill (a report's status). */
    static void badge(android.widget.TextView t, String label, int color) {
        Context c = t.getContext();
        t.setText(label);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        android.graphics.drawable.GradientDrawable g = roundRect(0x00000000, dp(c, 10));
        g.setStroke(Math.max(1, dp(c, 1)), color);
        t.setBackground(g);
    }

    static android.graphics.drawable.GradientDrawable roundRect(int color, float radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /**
     * Dashboard look for an AlertDialog; call right after show(). Window = a dashboard card (CARD, 16 dp corners,
     * inset from the screen edge); positive button filled blue, negative/neutral filled like the dashboard's buttons.
     */
    /**
     * Edge-to-edge (enforced for targetSdk 35+ on Android 15+): the app draws under the status, navigation and
     * cutout areas, so pad the window's content frame by those insets (and by the keyboard at the bottom). The
     * frame outlives setContentView, so this holds when an activity swaps its layout (MirrorActivity on rotation).
     * The padded strips show the window background, set to the dashboard color like the old bar colors.
     * Older Android versions don't draw edge-to-edge and report no insets here, so nothing changes there.
     */
    static void fitSystemBars(android.app.Activity a) {
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        a.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View content = a.findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = in.getInsets(android.view.WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return android.view.WindowInsets.CONSUMED;
        });
        content.requestApplyInsets();
    }

    /** Styles the dialog before its first layout (no flash of the theme's look), then shows it. */
    static void showStyled(android.app.AlertDialog d) {
        d.create();          // inflates the layout so the buttons exist
        styleDialog(d);
        d.show();
    }

    static void styleDialog(android.app.AlertDialog d) {
        Context c = d.getContext();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(roundRect(CARD, dp(c, 16)), dp(c, 16)));
        int[] which = {android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL, android.app.AlertDialog.BUTTON_POSITIVE};
        for (int w : which) {
            android.widget.Button b = d.getButton(w);
            if (b == null || b.getVisibility() != View.VISIBLE || b.getText().length() == 0) continue;
            styleButton(b, w == android.app.AlertDialog.BUTTON_POSITIVE);
        }
        // the title (when the dialog has one) in the dashboard's text color
        int id = c.getResources().getIdentifier("alertTitle", "id", "android");
        android.widget.TextView t = id != 0 ? d.findViewById(id) : null;
        if (t != null) t.setTextColor(TEXT);
    }

    /** A dialog button in the dashboard's style: filled blue when primary, grey otherwise. Call once it has a parent. */
    static void styleButton(android.widget.Button b, boolean primary) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(primary ? BG : TEXT);
        if (primary) b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setBackground(roundRect(primary ? BLUE : LINE, dp(c, 10)));
        b.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 4));
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        android.view.ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
            ((android.view.ViewGroup.MarginLayoutParams) lp).setMargins(dp(c, 8), 0, 0, dp(c, 8));
            b.setLayoutParams(lp);
        }
        // The button bar is a horizontal LinearLayout with gravity=bottom; with baseline alignment on (the
        // default) the restyled buttons end up 2 x the bottom margin above where they belong and their tops
        // are clipped by the bar.
        if (b.getParent() instanceof android.widget.LinearLayout)
            ((android.widget.LinearLayout) b.getParent()).setBaselineAligned(false);
    }

    static int heroColor(int state) {
        switch (state) {
            case Dash.READY: return AMBER;
            case Dash.PC: return BLUE;
            case Dash.STREAMING: return GREEN;
            case Dash.ERROR: return RED;
            default: return GREY;
        }
    }

    /** Big status orb: a glowing ring with a filled core; pulses while streaming. */
    static final class OrbView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG),
                glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = GREY;
        private boolean pulse;

        OrbView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
        }

        void set(int color, boolean pulse) {
            if (color != this.color || pulse != this.pulse) {
                this.color = color;
                this.pulse = pulse;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2f;
            float phase = pulse ? (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 1000.0 * Math.PI)) : 0.6f;
            int a = (int) (60 + 120 * phase);
            glow.setShader(new RadialGradient(cx, cy, r, new int[]{withAlpha(color, a), withAlpha(color, 0)},
                    new float[]{0.45f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r, glow);
            ring.setStrokeWidth(r * 0.12f);
            ring.setColor(color);
            cv.drawCircle(cx, cy, r * 0.62f, ring);
            core.setColor(withAlpha(color, pulse ? (int) (150 + 100 * phase) : 200));
            cv.drawCircle(cx, cy, r * 0.38f, core);
            if (pulse) postInvalidateOnAnimation();
        }

        static int withAlpha(int c, int a) {
            return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF);
        }
    }

    /** Sparkline of the last 60 samples with a soft fill. */
    static final class SparkView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] v = new float[0];
        private float max = 15;
        private int color = GREEN;
        private final Path p = new Path(), f = new Path();

        SparkView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            grid.setColor(LINE);
            grid.setStrokeWidth(1);
        }

        void set(float[] values, float max, int color) {
            this.v = values;
            this.max = Math.max(max, 1e-3f);
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), pad = 2;
            cv.drawLine(0, h - 1, w, h - 1, grid);
            if (v.length < 2) return;
            p.reset();
            f.reset();
            for (int i = 0; i < v.length; i++) {
                float x = w * i / (v.length - 1);
                float y = h - pad - (h - 2 * pad) * Math.min(1f, v[i] / max);
                if (i == 0) { p.moveTo(x, y); f.moveTo(x, h); f.lineTo(x, y); }
                else { p.lineTo(x, y); f.lineTo(x, y); }
            }
            f.lineTo(w, h);
            f.close();
            fill.setColor(OrbView.withAlpha(color, 50));
            cv.drawPath(f, fill);
            line.setColor(color);
            line.setStrokeWidth(Math.max(2f, h / 18f));
            cv.drawPath(p, line);
        }
    }

    /** 0..4 signal bars. */
    static final class BarsView extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level = -1, color = GREEN;

        BarsView(Context c) {
            super(c);
            off.setColor(LINE);
        }

        void set(int level, int color) {
            if (level != this.level || color != this.color) {
                this.level = level;
                this.color = color;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), gap = w * 0.08f, bw = (w - 3 * gap) / 4;
            on.setColor(color);
            for (int i = 0; i < 4; i++) {
                float bh = h * (i + 1) / 4f, x = i * (bw + gap);
                cv.drawRoundRect(x, h - bh, x + bw, h, bw * 0.25f, bw * 0.25f, i < level ? on : off);
            }
        }
    }

    static int scoreColor(int score) {
        return score >= 4 ? GREEN : score == 3 ? GREEN : score == 2 ? AMBER : score >= 0 ? RED : GREY;
    }

    static int parse(String hex) {
        return Color.parseColor(hex);
    }
}

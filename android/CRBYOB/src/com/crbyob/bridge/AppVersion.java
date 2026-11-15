package com.crbyob.bridge;

import android.os.Build;

/**
 * The app's own version (not the Scan Bridge firmware version it advertises to Creality Scan).
 * versionName/versionCode come from the git tag at build time; see build.gradle.kts for the scheme.
 */
final class AppVersion {
    private AppVersion() {}

    static final String NAME = BuildConfig.VERSION_NAME;
    static final int CODE = BuildConfig.VERSION_CODE;
    static final String COMMIT = BuildConfig.GIT_COMMIT;
    /** "github" or "play". */
    static final String CHANNEL = BuildConfig.FLAVOR;

    /** True for builds from an untagged commit or a modified tree. */
    static boolean isDev() {
        return NAME.contains("-dev");
    }

    /** True when this build is (or follows) an -alpha/-beta/-rc tag, or has no tag at all. */
    static boolean isPrerelease() {
        return CODE % 100 != 99;
    }

    // Digit counts capped so parsing can't overflow (a GitHub tag is outside input); ranges are checked below.
    private static final java.util.regex.Pattern SEMVER =
            java.util.regex.Pattern.compile("^v?(\\d{1,4})\\.(\\d{1,2})\\.(\\d{1,2})(?:-(?:(alpha|beta|rc)\\.(\\d{1,2})|(quest\\d*)))?$");

    /**
     * versionCode for a release version ("1.2.3", "v1.3.0-beta.2"), the same formula as build.gradle.kts:
     * MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + STAGE (alpha.N = N, beta.N = 30+N, rc.N = 60+N, final = 99).
     * -1 if the string is not a release version (dev builds, "Unreleased", out-of-range parts).
     */
    static int codeOf(String version) {
        if (version == null) return -1;
        java.util.regex.Matcher m = SEMVER.matcher(version.trim());
        if (!m.matches()) return -1;
        long major = Long.parseLong(m.group(1)), minor = Long.parseLong(m.group(2)), patch = Long.parseLong(m.group(3));
        if (major > 2099 || minor > 99 || patch > 99) return -1;
        int stage = 99;
        if (m.group(4) != null) {
            int n = Integer.parseInt(m.group(5));
            if (n > 29) return -1;
            stage = "alpha".equals(m.group(4)) ? n : "beta".equals(m.group(4)) ? 30 + n : 60 + n;
        } else if (m.group(6) != null) {
            stage = 99;
        }
        return (int) (major * 1_000_000 + minor * 10_000 + patch * 100 + stage);
    }

    static String channelLabel() {
        return "play".equals(CHANNEL) ? "Google Play" : "GitHub";
    }

    /** Footer text, e.g. "CR-BYOB 1.2.3 · GitHub" or "CR-BYOB 1.2.3-dev.4+abc1234 · GitHub · debug". */
    static String display() {
        return "CR-BYOB " + NAME + " · " + channelLabel() + (BuildConfig.DEBUG ? " · debug" : "");
    }

    /** One line for bug reports: app build plus phone and Android version. */
    static String bugReportLine() {
        return "CR-BYOB " + NAME + " (" + CODE + ", " + CHANNEL + (BuildConfig.DEBUG ? " debug" : "") + ", " + COMMIT + ")"
                + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }
}

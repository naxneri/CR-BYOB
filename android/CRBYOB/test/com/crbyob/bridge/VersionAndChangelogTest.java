package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

public class VersionAndChangelogTest {

    // Same vectors as the git-tag scheme in build.gradle.kts; the update check depends on both agreeing.
    @Test public void codeOfReleases() {
        assertEquals(10099, AppVersion.codeOf("0.1.0"));
        assertEquals(1020399, AppVersion.codeOf("1.2.3"));
        assertEquals(1020399, AppVersion.codeOf("v1.2.3"));
        assertEquals(2099999999, AppVersion.codeOf("2099.99.99"));
        assertEquals(21499, AppVersion.codeOf("0.2.14-quest3"));
        assertEquals(21499, AppVersion.codeOf("v0.2.14-quest3"));
    }

    @Test public void codeOfPrereleasesSortBetweenReleases() {
        assertEquals(1030000, AppVersion.codeOf("1.3.0-alpha.0"));
        assertEquals(1030032, AppVersion.codeOf("1.3.0-beta.2"));
        assertEquals(1030061, AppVersion.codeOf("1.3.0-rc.1"));
        assertTrue(AppVersion.codeOf("1.2.99") < AppVersion.codeOf("1.3.0-alpha.0"));
        assertTrue(AppVersion.codeOf("1.3.0-alpha.29") < AppVersion.codeOf("1.3.0-beta.0"));
        assertTrue(AppVersion.codeOf("1.3.0-beta.29") < AppVersion.codeOf("1.3.0-rc.0"));
        assertTrue(AppVersion.codeOf("1.3.0-rc.29") < AppVersion.codeOf("1.3.0"));
    }

    @Test public void codeOfRejectsNonReleases() {
        assertEquals(-1, AppVersion.codeOf("Unreleased"));
        assertEquals(-1, AppVersion.codeOf("1.2.3-dev.4+abc1234"));
        assertEquals(-1, AppVersion.codeOf("1.2.100"));
        assertEquals(-1, AppVersion.codeOf("1.100.0"));
        assertEquals(-1, AppVersion.codeOf("2100.0.0"));
        assertEquals(-1, AppVersion.codeOf("1.3.0-beta.30"));
        assertEquals(-1, AppVersion.codeOf("1.3.0-preview.1"));
        assertEquals(-1, AppVersion.codeOf(null));
    }

    // Tags come from GitHub (outside input): absurd numbers must give -1, never throw and abort the update check.
    @Test public void codeOfNeverThrowsOnHugeNumbers() {
        assertEquals(-1, AppVersion.codeOf("99999999999999999999.0.0"));
        assertEquals(-1, AppVersion.codeOf("1.99999999999999999999.0"));
        assertEquals(-1, AppVersion.codeOf("1.0.99999999999999999999"));
        assertEquals(-1, AppVersion.codeOf("1.0.0-beta.99999999999999999999"));
        assertEquals(-1, AppVersion.codeOf("v"));
        assertEquals(-1, AppVersion.codeOf(""));
    }

    // The versionCode Gradle assigned (build.gradle.kts, Kotlin) must be what the app computes from the
    // versionName (AppVersion.codeOf, Java): the update check compares GitHub tags with the latter.
    // Runs on every build, so a release tag whose two formulas disagree fails its own unit tests in CI.
    @Test public void gradleVersionCodeMatchesJavaFormula() {
        String name = BuildConfig.VERSION_NAME;
        int code = BuildConfig.VERSION_CODE;
        String base = name.contains("-dev") ? name.substring(0, name.indexOf("-dev")) : name;
        if (base.equals("0.0.0")) {
            assertEquals("untagged builds use versionCode 1", 1, code);
        } else {
            assertEquals("versionCode for " + name, AppVersion.codeOf(base), code);
        }
    }

    @Test public void parsesSectionsAndSkipsPreamble() {
        String md = "# Changelog\nintro text\n\n## [Unreleased]\n\n## [0.2.0] - 2026-12-01\n### Fixed\n- a\n\n"
                + "## [0.1.0] - YYYY-MM-DD\n- b\n";
        List<Changelog.Entry> e = Changelog.parse(md);
        assertEquals(3, e.size());
        assertEquals("Unreleased", e.get(0).version);
        assertEquals(-1, e.get(0).code);
        assertEquals("", e.get(0).body);
        assertEquals("0.2.0", e.get(1).version);
        assertEquals("2026-12-01", e.get(1).date);
        assertEquals("### Fixed\n- a", e.get(1).body);
        assertNull("placeholder date is not a date", e.get(2).date);
    }

    @Test public void betweenPicksVersionsSinceLastSeenNewestFirst() {
        List<Changelog.Entry> all = Changelog.parse(
                "## [0.3.0] - 2027-01-01\n- c\n## [0.2.0] - 2026-12-01\n- b\n## [0.1.0] - 2026-11-10\n- a\n");
        List<Changelog.Entry> e = Changelog.between(all, AppVersion.codeOf("0.1.0"), AppVersion.codeOf("0.3.0"));
        assertEquals(2, e.size());
        assertEquals("0.3.0", e.get(0).version);
        assertEquals("0.2.0", e.get(1).version);
    }

    @Test public void repoChangelogParses() throws Exception {
        File f = new File("../../CHANGELOG.md");   // unit tests run from the module directory
        String md = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        List<Changelog.Entry> e = Changelog.parse(md);
        assertTrue("CHANGELOG.md has version sections", e.size() >= 1);
        for (Changelog.Entry x : e)
            assertTrue("heading '" + x.version + "' is Unreleased or a release version",
                    x.version.equals("Unreleased") || x.code > 0);
    }
}

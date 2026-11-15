package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** How much of a long report or reply the thread shows before "Show full". */
public class ReportsActivityTest {
    @Test public void preview_shortTextIsShownWhole() {
        assertNull(ReportsActivity.preview("### Scanner\n\nCR-Scan Otter\n"));
    }

    @Test public void preview_cutsAtTwelveWholeLines() {
        StringBuilder s = new StringBuilder();
        for (int i = 1; i <= 30; i++) s.append("line ").append(i).append('\n');
        String p = ReportsActivity.preview(s.toString());
        assertTrue(p.endsWith("line 12"));
        assertEquals(12, p.split("\n").length);
    }

    @Test public void preview_cutsLongLinesByLength() {
        String line = "x".repeat(300) + "\n";
        String p = ReportsActivity.preview(line.repeat(5));
        assertEquals(2, p.split("\n").length);                  // a third line would pass 800 characters
        assertEquals(800, ReportsActivity.preview("y".repeat(5000)).length());   // one huge line: cut mid-line
    }
}

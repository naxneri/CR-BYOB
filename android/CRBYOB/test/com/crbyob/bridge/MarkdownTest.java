package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** Markdown's block parsing: fenced code in report bodies (the log section) and the blocks around it. */
public class MarkdownTest {
    private static String shape(List<Markdown.Block> blocks) {
        StringBuilder s = new StringBuilder();
        for (Markdown.Block b : blocks) s.append(b.type).append('[').append(b.text).append(']');
        return s.toString();
    }

    @Test public void fencedCodeKeepsItsLinesAndHidesTheFences() {
        assertEquals("h[Log]c[line 1\n\n  line **3**]p[after]",
                shape(Markdown.parse("### Log\n```text\nline 1\n\n  line **3**\n```\nafter")));
    }

    @Test public void emptyFencedCodeIsDropped() {
        assertEquals("h[Log]", shape(Markdown.parse("### Log\n```text\n\n```\n")));
    }

    @Test public void unclosedFenceRunsToTheEnd() {
        assertEquals("p[Log:]c[a\nb]", shape(Markdown.parse("Log:\n```\na\nb")));
        assertEquals("p[Log:]", shape(Markdown.parse("Log:\n```text\n")));   // cut right after the fence
    }

    @Test public void noFencesParsesAsBefore() {
        assertEquals("h[Fixed]b[one]b[two wrapped]p[para]",
                shape(Markdown.parse("## Fixed\n- one\n- two\n  wrapped\n\npara")));
    }
}

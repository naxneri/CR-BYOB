package com.crbyob.bridge;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough Markdown for release notes (CHANGELOG.md sections and GitHub release bodies) and report threads:
 * headings, "-"/"*" bullets (nested by indent, wrapped continuation lines), paragraphs, ``` fenced code (kept
 * line by line in monospace; empty ones dropped), **bold**, `code`, [links](url) (shown as their text), and HTML
 * comments (dropped).
 */
final class Markdown {
    private Markdown() {}

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern INLINE =
            Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");

    static final class Block {
        final char type;    // 'h' heading, 'b' bullet, 'p' paragraph, 'c' fenced code
        final int level;    // bullet nesting
        String text;

        Block(char type, int level, String text) {
            this.type = type;
            this.level = level;
            this.text = text;
        }
    }

    static CharSequence render(Context c, String md) {
        return render(c, md, false);
    }

    /** With `links`, http(s) [links](url) are tappable (the TextView needs a LinkMovementMethod). */
    static CharSequence render(Context c, String md, boolean links) {
        float d = c.getResources().getDisplayMetrics().density;
        List<Block> blocks = parse(md);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        char prev = 0;
        for (Block b : blocks) {
            // blank line between blocks, except inside a list and right under a heading
            if (sb.length() > 0 && prev != 'h' && !(b.type == 'b' && prev == 'b')) sb.append('\n');
            int start = sb.length();
            if (b.type == 'c') sb.append(b.text);                 // code: as written, no inline markup
            else appendInline(sb, b.text, links);
            sb.append('\n');
            int end = sb.length();
            if (b.type == 'h') {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.05f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (b.type == 'b') {
                if (b.level > 0)
                    sb.setSpan(new LeadingMarginSpan.Standard((int) (b.level * 16 * d)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BulletSpan((int) (8 * d)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (b.type == 'c') {
                sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            prev = b.type;
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.delete(sb.length() - 1, sb.length());
        return sb;
    }

    /** The blocks of md, in order. A ``` fence left open (a cut-off text) runs to the end. */
    static List<Block> parse(String md) {
        List<Block> blocks = new ArrayList<>();
        Block open = null;              // paragraph or bullet that a following indented line continues
        Block code = null;              // the fenced block being read
        boolean inComment = false;
        for (String line : md.replace("\r", "").split("\n", -1)) {
            if (code != null) {
                if (line.trim().startsWith("```")) {
                    if (code.text.trim().isEmpty()) blocks.remove(code);
                    code = null;
                } else code.text += (code.text.isEmpty() ? "" : "\n") + line;
                continue;
            }
            if (!inComment && line.trim().startsWith("```")) {    // "```text" and the like: the tag isn't shown
                code = new Block('c', 0, "");
                blocks.add(code);
                open = null;
                continue;
            }
            if (inComment) {
                int e = line.indexOf("-->");
                if (e < 0) continue;
                line = line.substring(e + 3);
                inComment = false;
            }
            line = line.replaceAll("<!--.*?-->", "");
            int s = line.indexOf("<!--");
            if (s >= 0) {
                line = line.substring(0, s);
                inComment = true;
            }
            if (line.trim().isEmpty()) {
                open = null;
                continue;
            }
            Matcher m;
            if ((m = HEADING.matcher(line.trim())).matches()) {
                blocks.add(new Block('h', 0, m.group(1)));
                open = null;
            } else if ((m = BULLET.matcher(line)).matches()) {
                open = new Block('b', m.group(1).length() / 2, m.group(2));
                blocks.add(open);
            } else if (open != null) {
                open.text += " " + line.trim();
            } else {
                open = new Block('p', 0, line.trim());
                blocks.add(open);
            }
        }
        if (code != null && code.text.trim().isEmpty()) blocks.remove(code);
        return blocks;
    }

    private static void appendInline(SpannableStringBuilder sb, String s, boolean links) {
        Matcher m = INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            int st = sb.length();
            if (m.group(1) != null || m.group(2) != null) {
                sb.append(m.group(1) != null ? m.group(1) : m.group(2));
                sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                sb.append(m.group(3));
                sb.setSpan(new TypefaceSpan("monospace"), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(m.group(4));
                if (links && m.group(5).matches("(?i)https?://.*"))
                    sb.setSpan(new android.text.style.URLSpan(m.group(5)), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        sb.append(s, last, s.length());
    }
}

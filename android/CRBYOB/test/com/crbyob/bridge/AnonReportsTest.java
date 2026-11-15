package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The anonymous-report rules: ID and tag, whose comment is whose, and what counts as unread. */
public class AnonReportsTest {
    private static final String TAG = "3eb1bd439947eb762998e566ccc2e099";
    private static final String BOT = "crbyob-bot[bot]";

    private static JSONObject comment(long id, String assoc, String login, String type, String body) throws Exception {
        return new JSONObject().put("id", id).put("author_association", assoc).put("body", body)
                .put("user", new JSONObject().put("login", login).put("type", type));
    }

    private static String ownBody(String tag) {
        return "**Reply from the reporter** (tag `" + tag + "`)\n\nhello";
    }

    private static JSONObject report(int seen, int dev) throws Exception {
        return new JSONObject().put("n", 1).put("seen", seen).put("dev", dev).put("newest", 0)
                .put("closed", false).put("unread", false);
    }

    @Test public void tagOf_vector() {
        assertEquals(TAG, AnonReports.tagOf("0123456789abcdef0123456789abcdef"));
    }

    @Test public void newId_isHexAndRandom() {
        String a = AnonReports.newId();
        assertTrue(a.matches("^[0-9a-f]{32}$"));
        assertNotEquals(a, AnonReports.newId());
    }

    @Test public void isDeveloper_rules() throws Exception {
        assertTrue(AnonReports.isDeveloper(comment(1, "OWNER", "NickLD", "User", "hi"), BOT, TAG));
        assertTrue(AnonReports.isDeveloper(comment(1, "NONE", "some-bot[bot]", "Bot", "hi"), BOT, TAG));
        assertFalse(AnonReports.isDeveloper(comment(1, "NONE", "stranger", "User", "hi"), BOT, TAG));
        assertFalse(AnonReports.isDeveloper(comment(1, "NONE", BOT, "Bot", ownBody(TAG)), BOT, TAG));
        // a reporter reply posted by a bot never counts, whatever its tag or bot login
        assertFalse(AnonReports.isDeveloper(comment(1, "NONE", BOT, "Bot", ownBody("0".repeat(32))), BOT, TAG));
        assertFalse(AnonReports.isDeveloper(comment(1, "NONE", "other-bot[bot]", "Bot", ownBody(TAG)), BOT, TAG));
    }

    @Test public void isMine_needsBotLogin() throws Exception {
        assertTrue(AnonReports.isMine(comment(1, "NONE", BOT, "Bot", ownBody(TAG)), BOT, TAG));
        assertFalse(AnonReports.isMine(comment(1, "NONE", "someone", "User", ownBody(TAG)), BOT, TAG));
        assertFalse(AnonReports.isMine(comment(1, "NONE", BOT, "Bot", ownBody("0".repeat(32))), BOT, TAG));
    }

    @Test public void applyIssue_404And410AreGoneButRateLimit403IsNot() throws Exception {
        JSONObject missing = report(1, 0);
        assertFalse(AnonReports.applyIssue(missing, 404, null));
        assertTrue(missing.optBoolean("gone"));
        JSONObject gone = report(1, 0);
        AnonReports.applyIssue(gone, 410, null);
        assertTrue(gone.optBoolean("gone"));
        JSONObject limited = report(1, 0);
        assertFalse(AnonReports.applyIssue(limited, 403, null));
        assertFalse(limited.optBoolean("gone"));
        assertFalse(limited.getBoolean("closed"));
    }

    @Test public void dropGone_removesOnlyDeleted() throws Exception {
        JSONArray a = new JSONArray().put(report(0, 0).put("n", 1).put("gone", true))
                .put(report(0, 0).put("n", 2)).put(report(0, 0).put("n", 3).put("gone", true));
        AnonReports.dropGone(a);
        assertEquals(1, a.length());
        assertEquals(2, a.getJSONObject(0).getInt("n"));
    }

    @Test public void title_fromIssueWithoutDevicePrefix() throws Exception {
        JSONObject r = report(0, 0);
        assertEquals("", AnonReports.title(r));                  // stored by 0.2.10, not fetched yet
        AnonReports.applyIssue(r, 200, new JSONObject().put("state", "open")
                .put("title", "[Device] CR-Scan Otter on Google Pixel 9"));
        assertEquals("CR-Scan Otter on Google Pixel 9", AnonReports.title(r));
        assertEquals("Freeform post", AnonReports.title(report(0, 0).put("title", "Freeform post")));
    }

    @Test public void applyIssue_reasonLockAndReopen() throws Exception {
        JSONObject r = report(0, 0);
        assertEquals("Open", AnonReports.status(r));
        AnonReports.applyIssue(r, 200, new JSONObject().put("state", "closed").put("state_reason", "not_planned"));
        assertEquals("Not planned", AnonReports.status(r));
        AnonReports.applyIssue(r, 200, new JSONObject().put("state", "closed").put("state_reason", "completed"));
        assertEquals("Resolved", AnonReports.status(r));
        AnonReports.applyIssue(r, 200, new JSONObject().put("state", "open").put("state_reason", JSONObject.NULL));
        assertFalse(r.getBoolean("closed"));                // reopened: replies and checks resume
        assertEquals("Open", AnonReports.status(r));
        AnonReports.applyIssue(r, 200, new JSONObject().put("state", "open").put("locked", true));
        assertEquals("Locked", AnonReports.status(r));
        // a report stored by 0.2.10 has no reason
        assertEquals("Closed", AnonReports.status(report(0, 0).put("closed", true)));
    }

    @Test public void applyIssue_commentCount() throws Exception {
        JSONObject open = new JSONObject().put("state", "open").put("comments", 3);
        assertTrue(AnonReports.applyIssue(report(1, 0), 200, open));
        assertFalse(AnonReports.applyIssue(report(3, 0), 200, open));
        JSONObject r = report(1, 0);
        AnonReports.applyIssue(r, 200, new JSONObject().put("state", "closed").put("comments", 1));
        assertTrue(r.getBoolean("closed"));
    }

    @Test public void applyComments_developerReplyIsUnread_ownIsNot() throws Exception {
        JSONObject r = report(0, 0);
        AnonReports.applyComments(r, new JSONArray().put(comment(5, "NONE", BOT, "Bot", ownBody(TAG))), BOT, TAG);
        assertFalse(r.getBoolean("unread"));
        assertEquals(1, r.getInt("seen"));
        AnonReports.applyComments(r, new JSONArray().put(comment(5, "NONE", BOT, "Bot", ownBody(TAG)))
                .put(comment(9, "OWNER", "NickLD", "User", "thanks")), BOT, TAG);
        assertTrue(r.getBoolean("unread"));
        assertEquals(9, r.getLong("newest"));
        AnonReports.markRead(r);
        assertFalse(r.getBoolean("unread"));
        assertEquals(9, r.getLong("dev"));
    }

    @Test public void errorMessage_texts() {
        assertEquals("Couldn't reach the report server. Check the connection and try again.", AnonReports.errorMessage(0, null));
        assertEquals("Too many reports from this network just now. Try again in a minute.", AnonReports.errorMessage(429, null));
        assertEquals("The report server answered 502. Try again later.", AnonReports.errorMessage(502, null));
    }

    @Test public void errorMessage_400and403AreFinalAndCarryTheServerText() {
        assertEquals("This report is closed, so it no longer takes replies.",
                AnonReports.errorMessage(403, "this report is closed, so it no longer takes replies"));
        assertEquals("The report server refused this (400).", AnonReports.errorMessage(400, null));
        assertEquals("Body is too long.",
                AnonReports.errorMessage(new AnonReports.HttpError(400, "HTTP 400", "body is too long")));
        assertEquals(AnonReports.errorMessage(0, null), AnonReports.errorMessage(new java.io.IOException("x")));
    }

    @Test public void onlyUnread_numberWhenExactlyOne() throws Exception {
        JSONObject a = report(0, 0).put("n", 4), b = report(0, 0).put("n", 7);
        assertEquals(0, AnonReports.onlyUnread(new JSONArray().put(a).put(b)));
        b.put("unread", true);
        assertEquals(7, AnonReports.onlyUnread(new JSONArray().put(a).put(b)));
        a.put("unread", true);
        assertEquals(0, AnonReports.onlyUnread(new JSONArray().put(a).put(b)));
    }

    @Test public void isMine_alsoWhenTheReplyCarriedALog() throws Exception {
        String withLog = "**Reply from the reporter** (tag `" + TAG + "`, full log Id: `0123456789abcdef0123456789abcdef`)\n\nhi";
        assertTrue(AnonReports.isMine(comment(1, "NONE", BOT, "Bot", withLog), BOT, TAG));
        assertFalse(AnonReports.isDeveloper(comment(1, "NONE", BOT, "Bot", withLog), BOT, TAG));
        assertFalse(AnonReports.isMine(comment(1, "NONE", BOT, "Bot", withLog.replace(TAG, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")), BOT, TAG));
    }

    @Test public void frame_isTheJsonLengthBigEndianThenTheJson() {
        byte[] h = AnonReports.frameHeader("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(4 + 7, h.length);
        assertEquals(0, h[0]);
        assertEquals(0, h[1]);
        assertEquals(0, h[2]);
        assertEquals(7, h[3]);
        assertEquals('{', h[4]);
        assertEquals(300, java.nio.ByteBuffer.wrap(AnonReports.frameHeader(new byte[300])).getInt());
    }

    @Test public void openReports_skipClosedLockedAndGone() throws Exception {
        JSONArray a = new JSONArray()
                .put(report(0, 0).put("n", 30).put("title", "[Device] Otter on Pixel 8"))
                .put(report(0, 0).put("n", 31).put("closed", true))
                .put(report(0, 0).put("n", 32).put("locked", true))
                .put(report(0, 0).put("n", 33).put("gone", true))
                .put(report(0, 0).put("n", 34));
        java.util.List<JSONObject> open = AnonReports.openReports(a);
        assertEquals(2, open.size());
        assertEquals(34, open.get(0).optInt("n"));     // newest first
        assertEquals(30, open.get(1).optInt("n"));
    }

    @Test public void logOutcome_saysWhatHappenedToTheLog() {
        assertEquals(null, AnonReports.logOutcome(null));
        assertEquals(null, AnonReports.logOutcome("stored"));
        assertTrue(AnonReports.logOutcome("refused: too big").contains("too big"));
        assertTrue(AnonReports.logOutcome("refused: storage full").contains("storage"));
        assertTrue(AnonReports.logOutcome("refused: whatever").startsWith("The full log wasn't stored"));
    }

    @Test public void size_inKbUnderAMegabyte() {
        assertEquals("1 KB", AnonReports.size(300));
        assertEquals("512 KB", AnonReports.size(512 * 1024));
        assertEquals("1.4 MB", AnonReports.size(1468006));
    }

    @Test public void sameReply_ignoresTheWorkersMentionBreaks() {
        assertTrue(AnonReports.sameReply("Thanks @‍NickLD\n", "Thanks @NickLD"));
        assertFalse(AnonReports.sameReply("Thanks", "Thanks again"));
    }

    @Test public void ago_inPlainSteps() {
        assertEquals("just now", AnonReports.ago(0));
        assertEquals("just now", AnonReports.ago(1_999));
        assertEquals("2 s ago", AnonReports.ago(2_000));
        assertEquals("59 s ago", AnonReports.ago(59_999));
        assertEquals("1 min ago", AnonReports.ago(60_000));
        assertEquals("59 min ago", AnonReports.ago(59 * 60_000L + 59_000));
        assertEquals("1 h ago", AnonReports.ago(60 * 60_000L));
        assertEquals("2 days ago", AnonReports.ago(2 * 86_400_000L));
    }
}

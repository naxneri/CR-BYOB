package com.crbyob.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One RTSP server per port, RTP interleaved over TCP. */
final class RtspServer {
    static final int RTP_CHUNK = 60000;
    /** Limits on what a peer can make us buffer or run (anyone on the LAN / Wi-Fi Direct group can connect). */
    private static final int MAX_SESSIONS = 8, MAX_HEADER = 16 * 1024, MAX_BODY = 64 * 1024;

    /** Cumulative per-port counters for the dashboard (Dash samples them once a second). */
    static final class StreamStat {
        final java.util.concurrent.atomic.AtomicLong skipped = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong sentFrames = new java.util.concurrent.atomic.AtomicLong(),
                sentBytes = new java.util.concurrent.atomic.AtomicLong(),
                captured = new java.util.concurrent.atomic.AtomicLong(),
                drops = new java.util.concurrent.atomic.AtomicLong();
        volatile int active;          // streaming sessions on this port
        volatile int k = 1;           // IR decimation in force
        volatile int fps;             // requested fps
        volatile String peer, profile;
    }

    final StreamStat stat = new StreamStat();

    final Bridge bridge;
    final int port;             // the node it serves comes from the scanner's model per session (Bridge.roleForPort)
    private ServerSocket srv;
    private volatile boolean running;
    private final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
    private static final Random RND = new Random();

    RtspServer(Bridge b, int port) {
        this.bridge = b;
        this.port = port;
    }

    void start() throws Exception {
        srv = new ServerSocket();
        srv.setReuseAddress(true);
        srv.bind(new InetSocketAddress(port), 4);
        running = true;
        Bridge.Role r = bridge.roleForPort(port);
        BLog.log("rtsp_listen", "port", port, "node", r != null ? r.name : null, "vc", r != null ? (Object) r.vc : null);
        new Thread(() -> {
            while (running) {
                try {
                    Socket c = srv.accept();
                    if (sessions.size() >= MAX_SESSIONS) {
                        BLog.log("rtsp_refused", "port", port, "peer", c.getInetAddress().getHostAddress(), "why", "too many sessions");
                        try { c.close(); } catch (Exception ignored) { }
                        continue;
                    }
                    if (!bridge.acceptsPeer(c.getInetAddress(), "rtsp " + port)) {
                        try { c.close(); } catch (Exception ignored) { }
                        continue;
                    }
                    c.setTcpNoDelay(true);
                    // ~0.3-0.6 s of buffering per stream (the kernel doubles it): IR ~120 Mbit/s, color ~14-28.
                    // A bigger color buffer only delays noticing a dead peer (writes keep "succeeding").
                    c.setSendBufferSize(bridge.isColorPort(port) ? 1024 * 1024 : 4 * 1024 * 1024);
                    StallGuard.tune(c);
                    Session s = new Session(c);
                    sessions.add(s);
                    new Thread(s::run, "rtsp-" + port + "-" + c.getPort()).start();
                } catch (Exception e) {
                    if (running) BLog.log("rtsp_accept_error", "port", port, "err", String.valueOf(e));
                }
            }
        }, "rtsp-accept-" + port).start();
    }

    void stop() {
        running = false;
        try { srv.close(); } catch (Exception ignored) { }
        synchronized (sessions) {
            for (Session s : sessions) s.kill();
        }
    }

    /** Closes every session from this IP (its PC control connection dropped / it left the group). */
    int killPeer(java.util.function.Predicate<String> ipMatch, String why) {
        int n = 0;
        synchronized (sessions) {
            for (Session s : sessions) {
                if (ipMatch.test(s.ip)) {
                    BLog.log("rtsp_kill", "port", port, "peer", s.peer, "why", why);
                    s.kill();
                    n++;
                }
            }
        }
        return n;
    }

    void stopAllStreams() {
        synchronized (sessions) {
            for (Session s : sessions) s.streaming = false;
        }
    }

    String statusLine() {
        int n = 0, st = 0;
        StringBuilder sb = new StringBuilder();
        synchronized (sessions) {
            for (Session s : sessions) {
                n++;
                if (s.streaming) {
                    st++;
                    sb.append(' ').append(s.statusText);
                }
            }
        }
        return n + " session(s), " + st + " streaming" + sb;
    }

    /**
     * '/<type>/<w>_<h>_<fps>_<suffix>' -> {stream type, profile[, synthesized fourcc]}.
     * An IR suffix Bridge.IR_ENCODERS can make from GREY (RLE, MJPG) and the model doesn't advertise natively
     * returns the GREY profile plus that fourcc; the session then captures Y8 and encodes.
     */
    static Object[] parseSession(String path, UsbDesc.Vs vs, ScannerModel model) {
        String clean = path.split("\\?")[0];
        List<String> parts = new ArrayList<>();
        for (String p : clean.replaceAll("^/+|/+$", "").split("/")) if (!p.isEmpty() && !p.startsWith("track")) parts.add(p);
        int stype = parts.isEmpty() ? 0 : Bridge.typeOf(parts.get(0));
        try {
            String[] f = parts.get(parts.size() - 1).split("_");
            int w = Integer.parseInt(f[0]), h = Integer.parseInt(f[1]), fps = Integer.parseInt(f[2]);
            String suffix = String.join("_", Arrays.copyOfRange(f, 3, f.length)).toLowerCase(Locale.US);
            String fourcc = model.suffixToV4l2(suffix);
            if (fourcc != null) {
                // IR in a format the model doesn't advertise natively (e.g. the Raptor's MJPG): encode it from Y8,
                // as cameraServer does, even when the scanner has a native mode of that format
                boolean synth = Bridge.isIr(stype) && Bridge.IR_ENCODERS.contains(fourcc);
                boolean native_ = !synth || model.advertise("ir").contains(fourcc);
                if (native_) for (UsbDesc.Profile p : vs.profiles) {
                    if (model.fmt(p.fourcc) != null && p.fourcc.equals(fourcc) && p.width == w && p.height == h && p.fps == fps)
                        return new Object[]{stype, p};
                }
                if (synth) {
                    for (UsbDesc.Profile p : vs.profiles) {
                        if (p.fourcc.equals("GREY") && p.width == w && p.height == h && p.fps == fps)
                            return new Object[]{stype, p, fourcc};
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        String want = stype == Bridge.ST_COLOR ? "MJPG" : "GREY";
        UsbDesc.Profile pick = null;
        for (UsbDesc.Profile p : vs.profiles) if (p.fourcc.equals(want)) { pick = p; break; }
        if (pick == null) for (UsbDesc.Profile p : vs.profiles) if (model.fmt(p.fourcc) != null) { pick = p; break; }
        if (pick == null) return null;
        BLog.log("rtsp_profile_fallback", "path", path, "using", pick.fourcc + pick.width + "x" + pick.height + "@" + pick.fps);
        return new Object[]{stype, pick};
    }

    // ---------------------------------------------------------------------------------------------
    final class Session {
        final Socket conn;
        final String peer, ip;
        final StallGuard guard;
        final String sessionId = String.format("%08X", RND.nextInt());
        final int ssrc = RND.nextInt();
        int seq = RND.nextInt(65536);
        int rtpCh = 0;
        Object[] profile;
        Bridge.Role role;        // node this session streams from, fixed at DESCRIBE/SETUP
        ScannerModel model;
        boolean irJpeg;          // ir_jpeg_over_y8: Y8 captured, JPEG sent, SDP says OB_FMT_MJPEG
        String synth;            // e.g. "RLE ": Y8 captured, encoded by RleCodec, SDP says OB_FMT_RLE
        int jpegQuality = 95;
        volatile boolean streaming;
        volatile String statusText = "";
        private Thread streamThread;
        private OutputStream out;
        private final Object sendLock = new Object();

        Session(Socket c) {
            conn = c;
            ip = c.getInetAddress().getHostAddress();
            peer = ip + ":" + c.getPort();
            guard = StallGuard.watch(c, "rtsp " + port);
        }

        int port() {
            return port;
        }

        void kill() {
            streaming = false;
            try { conn.close(); } catch (Exception ignored) { }
        }

        void send(byte[] b, int off, int len) throws java.io.IOException {
            synchronized (sendLock) {
                guard.write(out, b, off, len);
            }
        }

        void reply(String cseq, int code, String reason, Map<String, String> headers, byte[] body) throws java.io.IOException {
            SimpleDateFormat df = new SimpleDateFormat("EEE, MMM dd yyyy HH:mm:ss 'GMT'", Locale.US);
            df.setTimeZone(TimeZone.getTimeZone("GMT"));
            StringBuilder h = new StringBuilder();
            h.append("RTSP/1.0 ").append(code).append(' ').append(reason).append("\r\n");
            h.append("CSeq: ").append(cseq).append("\r\n");
            h.append("Date: ").append(df.format(new Date())).append("\r\n");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            if (body != null && body.length > 0) h.append("Content-Length: ").append(body.length).append("\r\n");
            h.append("\r\n");
            byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
            byte[] all = body == null ? hb : Arrays.copyOf(hb, hb.length + body.length);
            if (body != null) System.arraycopy(body, 0, all, hb.length, body.length);
            send(all, 0, all.length);
        }

        void run() {
            BLog.log("rtsp_connect", "port", port, "peer", peer);
            // fixed size: one interleaved packet (<= 4 + 65535) or one request (<= MAX_HEADER + 4 + MAX_BODY) fits
            byte[] buf = new byte[4 + 65535 + MAX_HEADER + MAX_BODY];
            int have = 0;
            try {
                out = conn.getOutputStream();
                InputStream in = conn.getInputStream();
                while (true) {
                    if (have == buf.length) throw new java.io.IOException("request too large");
                    int n = in.read(buf, have, buf.length - have);
                    if (n < 0) break;
                    have += n;
                    while (have > 0) {
                        if (buf[0] == '$') {                // interleaved RTCP from client
                            if (have < 4) break;
                            int len = (buf[2] & 0xFF) << 8 | (buf[3] & 0xFF);
                            if (have < 4 + len) break;
                            System.arraycopy(buf, 4 + len, buf, 0, have - 4 - len);
                            have -= 4 + len;
                            continue;
                        }
                        int end = indexOf(buf, Math.min(have, MAX_HEADER + 4), "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        if (end < 0) {
                            if (have > MAX_HEADER) throw new java.io.IOException("request header too large");
                            break;
                        }
                        String head = new String(buf, 0, end, StandardCharsets.UTF_8);
                        Matcher m = Pattern.compile("(?im)^content-length:\\s*(\\d+)").matcher(head);
                        String cl = m.find() ? m.group(1) : "0";
                        int clen = cl.length() <= 6 ? Integer.parseInt(cl) : Integer.MAX_VALUE;
                        if (clen > MAX_BODY) throw new java.io.IOException("request body too large: " + cl);
                        if (have < end + 4 + clen) break;
                        int consumed = end + 4 + clen;
                        System.arraycopy(buf, consumed, buf, 0, have - consumed);
                        have -= consumed;
                        handle(head);
                    }
                }
            } catch (Exception e) {
                BLog.log("rtsp_error", "port", port, "err", String.valueOf(e));
            } finally {
                streaming = false;
                try { conn.close(); } catch (Exception ignored) { }
                guard.release();
                sessions.remove(this);
                BLog.log("rtsp_close", "port", port, "peer", peer);
                SessionStats.cur.rtspCloses.incrementAndGet();
            }
        }

        private int indexOf(byte[] b, int len, byte[] pat) {
            outer:
            for (int i = 0; i + pat.length <= len; i++) {
                for (int j = 0; j < pat.length; j++) if (b[i + j] != pat[j]) continue outer;
                return i;
            }
            return -1;
        }

        void handle(String head) throws java.io.IOException {
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first[0], url = first.length > 1 ? first[1] : "";
            Map<String, String> hdrs = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) hdrs.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US), lines[i].substring(c + 1).trim());
            }
            String cseq = hdrs.containsKey("cseq") ? hdrs.get("cseq") : "0";
            BLog.log("rtsp_req", "port", port, "method", method, "url", url, "transport", hdrs.get("transport"));
            String path = url.replaceFirst("^rtsp://[^/]+", "");
            Map<String, String> h = new java.util.LinkedHashMap<>();
            switch (method) {
                case "OPTIONS":
                    h.put("Public", "OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER");
                    reply(cseq, 200, "OK", h, null);
                    break;
                case "DESCRIBE": {
                    if (profile == null) profile = resolve(path);
                    if (profile == null) { reply(cseq, 404, "Stream Not Found", null, null); break; }
                    int stype = (Integer) profile[0];
                    UsbDesc.Profile p = (UsbDesc.Profile) profile[1];
                    String ip = conn.getLocalAddress().getHostAddress();
                    int pt = 96 + stype;
                    String name = path.replaceAll("^/+|/+$", "");
                    String sdp = "v=0\r\no=- " + (System.currentTimeMillis() * 1000) + " 1 IN IP4 " + ip + "\r\n"
                            + "s=Session streamed by \"OnDemandRTSPServer\"\r\ni=" + name + "\r\n"
                            + "t=0 0\r\na=tool:CR-BYOB\r\na=type:broadcast\r\na=control:*\r\n"
                            + "a=range:npt=0-\r\na=x-qt-text-nam:Session streamed by \"OnDemandRTSPServer\"\r\n"
                            + "a=x-qt-text-inf:" + name + "\r\n"
                            + "m=VIDEO 0 RTP/AVP " + pt + "\r\nc=IN IP4 0.0.0.0\r\nb=AS:500000\r\n"
                            + "a=rtpmap:" + pt + " " + (synth != null ? model.rtpName(synth) : irJpeg ? "OB_FMT_MJPEG" : model.rtpName(p.fourcc)) + "/90000\r\n"
                            + "a=fmtp:" + pt + " width=" + p.width + ";height=" + p.height + ";fps=" + p.fps + "\r\n"
                            + "a=control:track1\r\n";
                    h.put("Content-Base", url.replaceAll("/+$", "") + "/");
                    h.put("Content-Type", "application/sdp");
                    reply(cseq, 200, "OK", h, sdp.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "SETUP": {
                    String t = hdrs.containsKey("transport") ? hdrs.get("transport") : "";
                    if (!t.toUpperCase(Locale.US).contains("TCP")) {
                        reply(cseq, 461, "Unsupported Transport", null, null);
                        return;
                    }
                    Matcher m = Pattern.compile("interleaved=(\\d+)-(\\d+)").matcher(t);
                    int ch2 = 1;
                    if (m.find()) { rtpCh = Integer.parseInt(m.group(1)); ch2 = Integer.parseInt(m.group(2)); }
                    else rtpCh = 0;
                    if (profile == null) profile = resolve(path);
                    h.put("Transport", "RTP/AVP/TCP;unicast;destination=" + conn.getInetAddress().getHostAddress() + ";"
                            + "source=" + conn.getLocalAddress().getHostAddress() + ";interleaved=" + rtpCh + "-" + ch2);
                    h.put("Session", sessionId + ";timeout=65");
                    reply(cseq, 200, "OK", h, null);
                    break;
                }
                case "PLAY":
                    h.put("Range", "npt=0.000-");
                    h.put("Session", sessionId);
                    h.put("RTP-Info", "url=" + url.replaceAll("/+$", "") + "/track1;seq=" + seq + ";rtptime=0");
                    reply(cseq, 200, "OK", h, null);
                    if (!streaming) {
                        // PLAY right after TEARDOWN: let the old loop see streaming=false and exit first, or two
                        // threads would send the same stream
                        Thread old = streamThread;
                        if (old != null && old.isAlive()) {
                            try { old.join(1000); } catch (InterruptedException ignored) { }
                            if (old.isAlive()) {
                                BLog.log("rtsp_play_busy", "port", port, "peer", peer);
                                break;
                            }
                        }
                        streaming = true;
                        streamThread = new Thread(this::streamLoop, "stream-" + port);
                        streamThread.setPriority(Thread.MAX_PRIORITY - 1);
                        streamThread.start();
                    }
                    break;
                case "TEARDOWN":
                    streaming = false;
                    h.put("Session", sessionId);
                    reply(cseq, 200, "OK", h, null);
                    break;
                case "GET_PARAMETER":
                case "SET_PARAMETER":
                case "PAUSE":
                    h.put("Session", sessionId);
                    reply(cseq, 200, "OK", h, null);
                    break;
                default:
                    reply(cseq, 405, "Method Not Allowed", null, null);
            }
        }

        private Object[] resolve(String path) {
            role = bridge.roleForPort(port);
            model = bridge.model;
            UsbDesc.Vs vs = role != null ? bridge.vsFor(role.name) : null;
            if (vs == null) {
                BLog.log("rtsp_no_node", "port", port, "path", path,
                        "why", role == null ? "no stream role on this port for " + model.summary() : "scanner not attached");
                return null;
            }
            Object[] pr = parseSession(path, vs, model);
            synth = pr != null && pr.length > 2 ? (String) pr[2] : null;
            if ("MJPG".equals(synth)) {                       // the Raptor's ir_l/ir_r *_mjpg: JPEG from Y8
                irJpeg = true;
                jpegQuality = bridge.streamMap().optInt("ir_jpeg_quality", model.irJpegQuality);
            } else if (pr != null && synth == null && Bridge.isIr((Integer) pr[0]) && ((UsbDesc.Profile) pr[1]).fourcc.equals("GREY")) {
                org.json.JSONObject m = bridge.streamMap();      // re-read at each session start
                // "?jpeg=1" in the URL is a test hook; the app never sends it
                irJpeg = m.optBoolean("ir_jpeg_over_y8", false) || path.contains("jpeg=1");
                jpegQuality = m.optInt("ir_jpeg_quality", 95);
            }
            return pr;
        }

        void streamLoop() {
            if (profile == null || role == null) {
                BLog.log("stream_error", "port", port, "err", "no profile (scanner not attached?)");
                streaming = false;
                return;
            }
            final Bridge.Role role = this.role;
            int stype = (Integer) profile[0];
            UsbDesc.Profile asked = (UsbDesc.Profile) profile[1], p = asked;
            if (stype == Bridge.ST_COLOR) {
                // Creality Scan picks colour once at connect, maybe in another work mode; over the current mode's
                // limit (Raptor Laser Lines: 10) the colour camera sends nothing, so run it at the limit instead
                UsbDesc.Vs cvs = bridge.vsFor(role.name);
                int ccap = bridge.colorFpsCap(asked.width, asked.height);
                if (cvs != null) p = UsbDesc.capFps(cvs.profiles, asked, ccap);
                if (p != asked) BLog.log("color_fps_capped", "port", port, "asked", asked.fps, "using", p.fps, "cap", ccap);
            }
            int pt = 96 + stype;
            boolean isIr = Bridge.isIr(stype);
            Semaphore lock = bridge.nodeLock(role.nodeKey());
            try {
                if (!lock.tryAcquire(300, TimeUnit.MILLISECONDS)) {
                    // Node busy. A holder from the same PC on the same port is a stale session the PC has
                    // already replaced; a holder that has not finished a write in 2 s is stuck on a dead
                    // peer. Kick either and take the node; refuse only a live holder from another peer.
                    Session h = bridge.nodeHolders.get(role.nodeKey());
                    String why = null;
                    long idle = h == null ? 0 : System.currentTimeMillis() - h.guard.lastWriteDoneMs;
                    if (h != null && h != this) {
                        if (h.ip.equals(ip) && h.port() == port) why = "same_peer";
                        else if (idle > 2000) why = "stalled";
                    }
                    if (why != null) {
                        BLog.log("rtsp_takeover", "port", port, "node", "VC" + role.vc, "old", h.peer, "old_port", h.port(),
                                "new", peer, "why", why, "old_idle_ms", idle, "old_write_ms", h.guard.stalledFor());
                        SessionStats.cur.takeovers.incrementAndGet();
                        h.kill();
                    }
                    if (!lock.tryAcquire(why != null ? 4000 : 2700, TimeUnit.MILLISECONDS)) {
                        BLog.log("rtsp_busy", "port", port, "node", "VC" + role.vc, "holder", h != null ? h.peer : null,
                                "holder_idle_ms", h != null ? (Object) idle : null);
                        streaming = false;
                        return;
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
            bridge.nodeHolders.put(role.nodeKey(), this);
            guard.lastWriteDoneMs = System.currentTimeMillis();     // grace for camera start-up
            FrameSource cap = null;
            try {
                if (isIr) for (int[] pp : model.irPreStreamProps) bridge.scannerSetBool(pp[0], pp[1]);
                Otter o = bridge.otter;
                UsbDesc.Vs vs = bridge.vsFor(role.name);
                if (vs == null) throw new java.io.IOException("scanner not attached");
                if (o == null) {
                    if (!bridge.testPattern) throw new java.io.IOException("scanner not attached");
                    cap = new TestPattern(p);
                } else {
                    // stream_map.json "capture_queue": frames of slack per stream (default UvcCapture.queueDepth)
                    cap = new UvcCapture(o, vs, p, bridge.streamMap().optInt("capture_queue", 0));
                }
                cap.start();
                String prof = p.fourcc + p.width + "x" + p.height + "@" + p.fps;
                RleCodec rle = synth != null && synth.equals("RLE ")
                        ? new RleCodec(p.width, p.height, bridge.rleThreshold(), model.rlePadTo) : null;
                IrEncoder enc = irJpeg ? new IrJpeg(p.width, p.height, jpegQuality) : rle;
                // paired IR decimation (IrGate): on for raw, RLE and the Raptor's MJPG (its speckle depth needs
                // left/right pairs); off only in the ir_jpeg_over_y8 test mode
                boolean gate = isIr && (!irJpeg || synth != null);
                BLog.log("stream_start", "port", port, "node", "VS" + vs.iface, "type", stype, "profile", prof, "mirror", false,
                        "ir_jpeg", irJpeg, "quality", irJpeg ? (Object) jpegQuality : null, "synth", synth,
                        "rle_threshold", rle != null ? (Object) rle.threshold : null);
                if (rle != null) prof = prof + "->RLE";
                else if (irJpeg) prof = prof + "->JPEG";
                statusText = "[" + stype + " " + prof.trim() + "]";
                stat.active++;
                stat.peer = conn.getInetAddress().getHostAddress();
                stat.profile = prof.trim();
                stat.fps = p.fps;
                // what Creality Scan asks for unboosted, at this size
                if (isIr && bridge.fpsFloor(p.width, p.height) == 0) bridge.noteStockFps(p.width, p.height, p.fps);
                long capSeen = 0, dropSeen = 0;

                byte[] hdr = new byte[34];
                int hdrLen = isIr ? 34 : 26;
                // Raptor: each IR frame's light type (laser lines vs infrared) goes to the PC in the header, and
                // frames of another type than the PC asked for (props 0xC5/0xC6) are left out, as cameraServer does
                boolean lightTagged = isIr && !model.irLightTypes.isEmpty();
                int lightSide = stype == 7 ? 1 : 0;              // IR_RIGHT: 0xC6, IR / IR_LEFT: 0xC5
                long[] lightCount = new long[6];                 // types 0..4, then untagged
                long lightFiltered = 0;
                byte[] pkt = new byte[4 + 12 + RTP_CHUNK];
                long n = 0, sent = 0, t0 = System.nanoTime();
                double idle = 0;
                long lastReport = System.currentTimeMillis(), skipped = 0;
                // per stream_stats window: time inside socket writes (high = the PC or link is slow to take data)
                // and the longest wait between frames (long with fast writes = this thread didn't get to run)
                long wrNs = 0, wrMaxNs = 0, gapMaxNs = 0, winT0 = System.nanoTime(), lastTake = 0;
                while (streaming) {
                    UvcCapture.Frame f = cap.take(500);
                    if (f == null) {
                        if (o != null && o.isClosed()) throw new java.io.IOException("scanner detached");
                        idle += 0.5;
                        if (idle >= 5.0) throw new java.io.IOException("no frame within 5 s");
                        continue;
                    }
                    idle = 0;
                    long tTake = System.nanoTime();
                    if (lastTake != 0) gapMaxNs = Math.max(gapMaxNs, tTake - lastTake);
                    lastTake = tTake;
                    long cf = cap.framesCaptured(), cd = cap.framesDroppedQueue();
                    stat.captured.addAndGet(cf - capSeen);
                    stat.drops.addAndGet(cd - dropSeen);
                    capSeen = cf;
                    dropSeen = cd;
                    stat.k = gate ? bridge.irGate.k() : 1;
                    long nowMs = System.currentTimeMillis();
                    if (gate && nowMs - lastReport > 2000) {
                        lastReport = nowMs;
                        bridge.irGate.report(port, cap.framesCaptured(), cap.framesDroppedQueue());
                    }
                    if (gate && !bridge.irGate.admit(f.tsUs, p.fps)) {   // paired decimation on a slow link
                        cap.release(f);
                        skipped++;
                        stat.skipped.incrementAndGet();
                        continue;
                    }
                    int light = 0;
                    if (lightTagged) {
                        Integer t = f.meta >= 0 ? model.irLightTypes.get(f.meta) : null;
                        lightCount[t == null ? 5 : Math.max(0, Math.min(4, t))]++;
                        if (t != null) light = t;
                        int want = bridge.irLightRequest(lightSide);
                        if (t != null && !Bridge.allLightTypes(want) && t != want) {
                            cap.release(f);
                            lightFiltered++;
                            continue;
                        }
                    }
                    byte[] data = f.data;
                    int dlen = f.len;
                    long tsUs = f.tsUs;
                    if (enc != null) {                 // encode, then hand the capture buffer back early
                        try {
                            if (rle != null) rle.threshold = bridge.rleThreshold();   // 0x42A set mid-session
                            dlen = enc.encode(f.data);
                            data = enc.buf();
                        } finally {
                            cap.release(f);
                            f = null;
                        }
                    }
                    try {
                        // <Q counter, H extLen, Q ts_ms, I w, I h> [+ <I size, I lightType>]
                        Hp.putU64(hdr, 0, n);
                        Hp.putU16(hdr, 8, isIr ? 8 : 0);
                        Hp.putU64(hdr, 10, tsUs / 1000);
                        Hp.putU32(hdr, 18, p.width);
                        Hp.putU32(hdr, 22, p.height);
                        if (isIr) {
                            Hp.putU32(hdr, 26, dlen);
                            Hp.putU32(hdr, 30, light);
                        }
                        int total = hdrLen + dlen;
                        int rtpTs = (int) (tsUs * 9 / 100);
                        long w0 = System.nanoTime();
                        for (int off = 0; off < total; off += RTP_CHUNK) {
                            int chunk = Math.min(RTP_CHUNK, total - off);
                            boolean last = off + RTP_CHUNK >= total;
                            int rtpLen = 12 + chunk;
                            pkt[0] = '$';
                            pkt[1] = (byte) rtpCh;
                            pkt[2] = (byte) (rtpLen >> 8);
                            pkt[3] = (byte) rtpLen;
                            pkt[4] = (byte) 0x80;
                            pkt[5] = (byte) ((last ? 0x80 : 0) | pt);
                            pkt[6] = (byte) (seq >> 8);
                            pkt[7] = (byte) seq;
                            pkt[8] = (byte) (rtpTs >> 24); pkt[9] = (byte) (rtpTs >> 16); pkt[10] = (byte) (rtpTs >> 8); pkt[11] = (byte) rtpTs;
                            pkt[12] = (byte) (ssrc >> 24); pkt[13] = (byte) (ssrc >> 16); pkt[14] = (byte) (ssrc >> 8); pkt[15] = (byte) ssrc;
                            seq = (seq + 1) & 0xFFFF;
                            // copy [off, off+chunk) of (hdr ++ frame) into the packet
                            int dst = 16, pos = off, left = chunk;
                            if (pos < hdrLen) {
                                int k = Math.min(hdrLen - pos, left);
                                System.arraycopy(hdr, pos, pkt, dst, k);
                                dst += k; pos += k; left -= k;
                            }
                            if (left > 0) System.arraycopy(data, pos - hdrLen, pkt, dst, left);
                            send(pkt, 0, 16 + chunk);
                        }
                        long wd = System.nanoTime() - w0;
                        wrNs += wd;
                        wrMaxNs = Math.max(wrMaxNs, wd);
                        n++;
                        sent += total;
                        stat.sentFrames.incrementAndGet();
                        stat.sentBytes.addAndGet(total);
                        SessionStats.frameAge(System.nanoTime() / 1000 - tsUs);   // capture (first payload) -> written to the socket
                    } finally {
                        if (f != null) cap.release(f);
                    }
                    if (n % (Math.max(p.fps, 1) * 5L) == 0) {
                        double dt = (System.nanoTime() - t0) / 1e9;
                        long[] es = enc != null ? enc.stats() : null;
                        BLog.log("stream_stats", "port", port, "type", stype, "frames", n,
                                "fps", Math.round(n / dt * 10) / 10.0, "mbps", Math.round(sent * 8 / dt / 1e5) / 10.0,
                                "captured", cap.framesCaptured(), "queue_drops", cap.framesDroppedQueue(),
                                "skipped", skipped, "ir_k", isIr ? (gate ? bridge.irGate.k() : 1) : 0,
                                "enc_kb", es != null && es[0] > 0 ? (Object) (es[1] / es[0] / 1024) : null,
                                "enc_ms", es != null && es[0] > 0 ? (Object) (Math.round(es[2] / 1e5 / es[0]) / 10.0) : null,
                                "write_pct", Math.round(wrNs * 100.0 / Math.max(1, System.nanoTime() - winT0)),
                                "write_max_ms", wrMaxNs / 1_000_000, "gap_max_ms", gapMaxNs / 1_000_000,
                                "light_types", lightTagged ? java.util.Arrays.toString(lightCount) : null,
                                "light_filtered", lightTagged ? (Object) lightFiltered : null,
                                "light_wanted", lightTagged ? (Object) bridge.irLightRequest(lightSide) : null);
                        wrNs = wrMaxNs = gapMaxNs = 0;
                        winT0 = System.nanoTime();
                        statusText = "[" + stype + " " + prof.trim() + " " + Math.round(n / dt * 10) / 10.0 + "fps]";
                    }
                }
            } catch (Exception e) {
                BLog.log("stream_error", "port", port, "err", String.valueOf(e));
                SessionStats.cur.rtspErrors.incrementAndGet();
            } finally {
                streaming = false;
                if (cap != null) {
                    stat.active = Math.max(0, stat.active - 1);
                    SessionStats.cur.uvcErrors.addAndGet(cap.uvcErrors());
                    cap.close();
                }
                bridge.nodeHolders.remove(role.nodeKey(), this);
                lock.release();
                statusText = "";
                BLog.log("stream_stop", "port", port);
            }
        }
    }
}

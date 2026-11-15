package com.crbyob.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parser for UsbDeviceConnection.getRawDescriptors(): device descriptor + configuration
 * descriptor(s). Extracts the UVC VideoControl entities (camera terminal, processing unit,
 * extension units), VideoStreaming formats/frames, endpoints and the HID interface.
 */
public final class UsbDesc {

    public static final class Xu {
        public int vcIface, unitId, numControls;
        public byte[] guid = new byte[16];
        public String guidStr() {
            return String.format(Locale.US, "%08X-%04X-%04X-%s-%s", Hp.u32(guid, 0), Hp.u16(guid, 4), Hp.u16(guid, 6),
                    Hp.hex(guid, 8, 2).toUpperCase(Locale.US), Hp.hex(guid, 10, 6).toUpperCase(Locale.US));
        }
    }

    /** One VideoControl interface (one UVC function). */
    public static final class Vc {
        public int iface;
        public int bcdUvc;
        public final List<Integer> vsIfaces = new ArrayList<>();
        public int ctId = -1, ctControls;      // camera terminal id + bmControls
        public int puId = -1, puControls;      // processing unit id + bmControls
        public final List<Xu> xus = new ArrayList<>();
    }

    /** One discrete mode of a VideoStreaming interface. fourcc uses the V4L2 names (GREY, MJPG, Y10 ...). */
    public static final class Profile {
        public String fourcc;
        public int width, height, fps;
        public int formatIndex, frameIndex, interval; // interval in 100 ns
        public int maxFrameBuf;
        public String key() { return fourcc + width + "x" + height + "@" + fps; }
        @Override public String toString() {
            return fourcc.trim() + " " + width + "x" + height + "@" + fps + " (fmt " + formatIndex + " frm " + frameIndex + ")";
        }
    }

    public static final class Vs {
        public int iface;
        public int endpoint = -1;          // from the input header (bEndpointAddress)
        public int maxPacket;
        public final List<Profile> profiles = new ArrayList<>();   // sorted by (fourcc, w, h, fps)
        public final List<String> unknownFormats = new ArrayList<>();
    }

    /**
     * True if p should be left out of the 0xFC1 list under an fps floor (0 = off).
     * Creality Scan takes the fps from its own table (15 for an Otter behind a bridge). For each stream it then
     * keeps the smallest advertised fps that is at least that value, among modes of the same size and format
     * (lib_orbbec_scan.dll 0x1808E5DA0). Hiding the slower modes therefore raises the rate it asks for.
     * If a size/format has no mode at or above the floor, its fastest mode stays, so the stream still matches.
     */
    public static boolean belowFpsFloor(List<Profile> all, Profile p, int floor) {
        if (floor <= 0 || p.fps >= floor) return false;
        int max = 0;
        for (Profile q : all)
            if (q.fourcc.equals(p.fourcc) && q.width == p.width && q.height == p.height) max = Math.max(max, q.fps);
        return p.fps < Math.min(floor, max);
    }

    /** belowFpsFloor, except for the rates in keep. */
    public static boolean belowFpsFloor(List<Profile> all, Profile p, int floor, java.util.Collection<Integer> keep) {
        return !keep.contains(p.fps) && belowFpsFloor(all, p, floor);
    }

    /** p, or if it is faster than cap (> 0) the fastest mode of the same format and size at or under cap; else p. */
    public static Profile capFps(List<Profile> all, Profile p, int cap) {
        if (cap <= 0 || p.fps <= cap) return p;
        Profile best = null;
        for (Profile q : all)
            if (q.fourcc.equals(p.fourcc) && q.width == p.width && q.height == p.height && q.fps <= cap
                    && (best == null || q.fps > best.fps)) best = q;
        return best != null ? best : p;
    }

    public static final class Hid {
        public int iface;
        public int endpoint = -1;
        public int maxPacket;
    }

    public int vid, pid, bcdUsb;
    public final Map<Integer, Vc> vcs = new LinkedHashMap<>();
    public final Map<Integer, Vs> vss = new LinkedHashMap<>();
    public final List<Hid> hids = new ArrayList<>();
    public final Map<Integer, int[]> endpoints = new LinkedHashMap<>();  // ep addr -> {iface, attr, mps}

    public static synchronized UsbDesc parse(byte[] d) {
        UsbDesc u = new UsbDesc();
        if (d.length >= 18 && d[1] == 1) {
            u.bcdUsb = Hp.u16(d, 2);
            u.vid = Hp.u16(d, 8);
            u.pid = Hp.u16(d, 10);
        }
        int i = 0, cur = -1, alt = 0, cls = -1, sub = -1, configs = 0;
        Vs curVs = null;
        Vc curVc = null;
        Hid curHid = null;
        // format index -> fourcc for the current VS interface
        Map<Integer, String> fmtFourcc = new LinkedHashMap<>();
        while (i + 2 <= d.length) {
            int len = d[i] & 0xFF, type = d[i + 1] & 0xFF;
            if (len < 2 || i + len > d.length) break;
            if (type == 0x02) {               // CONFIGURATION
                if (++configs > 1) break;     // only the first configuration
            } else if (type == 0x04 && len >= 9) {   // INTERFACE
                cur = d[i + 2] & 0xFF;
                alt = d[i + 3] & 0xFF;
                cls = d[i + 5] & 0xFF;
                sub = d[i + 6] & 0xFF;
                curVc = null; curVs = null; curHid = null;
                if (cls == 0x0E && sub == 0x01) {
                    curVc = u.vcs.get(cur);
                    if (curVc == null) { curVc = new Vc(); curVc.iface = cur; u.vcs.put(cur, curVc); }
                } else if (cls == 0x0E && sub == 0x02) {
                    curVs = u.vss.get(cur);
                    if (curVs == null) { curVs = new Vs(); curVs.iface = cur; u.vss.put(cur, curVs); fmtFourcc.clear(); lastFmt[0] = -1; }
                } else if (cls == 0x03) {
                    curHid = new Hid(); curHid.iface = cur; u.hids.add(curHid);
                }
            } else if (type == 0x05 && len >= 7) {   // ENDPOINT
                int addr = d[i + 2] & 0xFF, attr = d[i + 3] & 0xFF, mps = Hp.u16(d, i + 4) & 0x7FF;
                u.endpoints.put(addr, new int[]{cur, attr, mps});
                if (curVs != null && alt == 0 && (attr & 3) == 2 && (addr & 0x80) != 0) {
                    if (curVs.endpoint < 0) curVs.endpoint = addr;
                    curVs.maxPacket = mps;
                }
                if (curHid != null && (attr & 3) == 3 && (addr & 0x80) != 0 && curHid.endpoint < 0) {
                    curHid.endpoint = addr;
                    curHid.maxPacket = mps;
                }
            } else if (type == 0x24 && len >= 3) {  // CS_INTERFACE
                int st = d[i + 2] & 0xFF;
                if (curVc != null) {
                    parseVc(curVc, d, i, len, st);
                } else if (curVs != null) {
                    parseVs(curVs, d, i, len, st, fmtFourcc);
                }
            }
            i += len;
        }
        for (Vs vs : u.vss.values()) sortProfiles(vs.profiles);
        return u;
    }

    private static void parseVc(Vc vc, byte[] d, int i, int len, int st) {
        if (st == 0x01 && len >= 12) {                       // VC_HEADER
            vc.bcdUvc = Hp.u16(d, i + 3);
            int n = d[i + 11] & 0xFF;
            for (int k = 0; k < n && 12 + k < len; k++) vc.vsIfaces.add(d[i + 12 + k] & 0xFF);
        } else if (st == 0x02 && len >= 8) {                 // INPUT_TERMINAL
            int ttype = Hp.u16(d, i + 4);
            if (ttype == 0x0201 && vc.ctId < 0) {             // ITT_CAMERA
                vc.ctId = d[i + 3] & 0xFF;
                if (len >= 15) {
                    int n = d[i + 14] & 0xFF;
                    vc.ctControls = bits(d, i + 15, Math.min(n, len - 15));
                }
            }
        } else if (st == 0x05 && len >= 8) {                 // PROCESSING_UNIT
            if (vc.puId < 0) {
                vc.puId = d[i + 3] & 0xFF;
                int n = d[i + 7] & 0xFF;
                vc.puControls = bits(d, i + 8, Math.min(n, len - 8));
            }
        } else if (st == 0x06 && len >= 22) {                // EXTENSION_UNIT
            Xu x = new Xu();
            x.vcIface = vc.iface;
            x.unitId = d[i + 3] & 0xFF;
            System.arraycopy(d, i + 4, x.guid, 0, 16);
            x.numControls = d[i + 20] & 0xFF;
            vc.xus.add(x);
        }
    }

    private static int bits(byte[] d, int off, int n) {
        int v = 0;
        for (int k = 0; k < n && k < 4; k++) v |= (d[off + k] & 0xFF) << (8 * k);
        return v;
    }

    /** UVC format GUID -> V4L2 fourcc string (as uvcvideo's format table does). */
    static String guidToFourcc(byte[] d, int off) {
        String g = new String(new char[]{(char) (d[off] & 0xFF), (char) (d[off + 1] & 0xFF),
                (char) (d[off + 2] & 0xFF), (char) (d[off + 3] & 0xFF)});
        int first = Hp.u32(d, off);
        if (g.equals("Y800") || g.equals("Y8  ") || g.equals("GREY") || first == 0x32) return "GREY"; // 0x32 = D3DFMT_L8
        if (g.equals("Y10 ")) return "Y10 ";
        if (g.equals("Y14 ")) return "Y14 ";   // the Raptor's depth (MI_00 format 1, issue #15)
        if (g.equals("Y16 ")) return "Y16 ";
        if (g.equals("MJPG")) return "MJPG";
        if (g.equals("YUY2")) return "YUYV";
        return null;
    }

    private static final int[] lastFmt = {-1};

    private static void parseVs(Vs vs, byte[] d, int i, int len, int st, Map<Integer, String> fmt) {
        if (st == 0x01 && len >= 7) {                         // VS_INPUT_HEADER
            vs.endpoint = d[i + 6] & 0xFF;
        } else if ((st == 0x04 || st == 0x10) && len >= 21) {  // FORMAT_UNCOMPRESSED / FORMAT_FRAME_BASED
            int idx = d[i + 3] & 0xFF;
            String f = guidToFourcc(d, i + 5);
            fmt.put(idx, f);
            lastFmt[0] = idx;
            if (f == null) vs.unknownFormats.add("fmt" + idx + ":" + Hp.hex(d, i + 5, 4));
        } else if (st == 0x06 && len >= 4) {                   // FORMAT_MJPEG
            fmt.put(d[i + 3] & 0xFF, "MJPG");
            lastFmt[0] = d[i + 3] & 0xFF;
        } else if ((st == 0x05 || st == 0x07 || st == 0x11) && len >= 26) {   // FRAME_*
            // the frame belongs to the most recent format descriptor
            Integer fmtIdx = lastFmt[0];
            if (fmtIdx < 0) return;
            String f = fmt.get(fmtIdx);
            if (f == null) return;
            int frameIdx = d[i + 3] & 0xFF;
            int w = Hp.u16(d, i + 5), h = Hp.u16(d, i + 7);
            int nInt, ivOff, maxBuf = 0;
            if (st == 0x11) {          // frame-based: dwDefaultFrameInterval @17, bFrameIntervalType @21, dwBytesPerLine @22
                nInt = d[i + 21] & 0xFF;
                ivOff = i + 26;
            } else {                   // uncompressed/mjpeg: dwMaxVideoFrameBufferSize @17, default @21, type @25
                maxBuf = Hp.u32(d, i + 17);
                nInt = d[i + 25] & 0xFF;
                ivOff = i + 26;
            }
            if (nInt == 0) return;     // continuous intervals: V4L2 list_profiles only yields discrete ones
            Set<Integer> seen = new HashSet<>();
            for (Profile p : vs.profiles) if (p.fourcc.equals(f) && p.width == w && p.height == h) seen.add(p.fps);
            for (int k = 0; k < nInt && ivOff + 4 * k + 4 <= i + len; k++) {
                int iv = Hp.u32(d, ivOff + 4 * k);
                if (iv <= 0) continue;
                int fps = (int) Math.round(1e7 / iv);
                if (!seen.add(fps)) continue;       // dedup on (fourcc, w, h, round(fps))
                Profile p = new Profile();
                p.fourcc = f; p.width = w; p.height = h; p.fps = fps;
                p.formatIndex = fmtIdx; p.frameIndex = frameIdx; p.interval = iv; p.maxFrameBuf = maxBuf;
                vs.profiles.add(p);
            }
        }
    }

    static void sortProfiles(List<Profile> l) {
        Collections.sort(l, (a, b) -> {
            int c = a.fourcc.compareTo(b.fourcc);
            if (c != 0) return c;
            if (a.width != b.width) return Integer.compare(a.width, b.width);
            if (a.height != b.height) return Integer.compare(a.height, b.height);
            return Integer.compare(a.fps, b.fps);
        });
    }

    public Vc vcForVs(int vsIface) {
        for (Vc vc : vcs.values()) if (vc.vsIfaces.contains(vsIface)) return vc;
        return null;
    }

    public Vs vsForVc(int vcIface) {
        Vc vc = vcs.get(vcIface);
        if (vc != null) for (int n : vc.vsIfaces) if (vss.containsKey(n)) return vss.get(n);
        return vss.get(vcIface + 1);
    }

    /** The index-th VideoStreaming interface of a VC (scanners.json [vc, index]; Sermoon IR_L/IR_R share VC 0 [I]). */
    public Vs vsForVc(int vcIface, int index) {
        if (index == 0) return vsForVc(vcIface);
        Vc vc = vcs.get(vcIface);
        if (vc == null) return null;
        int i = 0;
        for (int n : vc.vsIfaces) if (vss.containsKey(n) && i++ == index) return vss.get(n);
        return null;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%04x:%04x", vid, pid));
        for (Vc vc : vcs.values()) {
            sb.append(String.format(Locale.US, " | VC%d uvc=%x vs=%s ct=%d(%x) pu=%d(%x)", vc.iface, vc.bcdUvc,
                    vc.vsIfaces, vc.ctId, vc.ctControls, vc.puId, vc.puControls));
            for (Xu x : vc.xus) sb.append(" xu").append(x.unitId).append('{').append(x.guidStr()).append('}');
        }
        for (Vs vs : vss.values()) {
            sb.append(String.format(Locale.US, " | VS%d ep=%02x mps=%d modes=%d", vs.iface, vs.endpoint, vs.maxPacket, vs.profiles.size()));
            if (!vs.unknownFormats.isEmpty()) sb.append(" unknown=").append(vs.unknownFormats);
        }
        for (Hid h : hids) sb.append(String.format(Locale.US, " | HID%d ep=%02x mps=%d", h.iface, h.endpoint, h.maxPacket));
        return sb.toString();
    }
}

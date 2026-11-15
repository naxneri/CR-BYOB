package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Colour rate against the scanner's 0xFC3 limit (issue #15): the Raptor allows colour at 10 fps in Laser Lines Scan,
 * Creality Scan asks for 10, and Boost's 1280x800 IR floor must not push colour to 30 (the colour camera then sends
 * nothing at 50 fps IR and 38 fps of unusable frames at 40).
 */
public class ColorFpsCapTest {

    // the Raptor's 0xFC3 replies (fw 1.3.8, issue #15 logs)
    private static final String RAPTOR_LASER_LINES =
            "070000000900000080070000b0040000320000000700000009000000f0050000b00400003c000000070000000900000028050000"
            + "100400003c000000060000000900000080070000b004000032000000060000000500000080070000b00400003200000006000000"
            + "09000000f0050000b00400003c0000000600000005000000f0050000b00400003c00000006000000090000002805000010040000"
            + "3c000000060000000500000028050000100400003c000000020000000500000000050000200300000a000000";
    private static final String RAPTOR_CHIP_SCALE_SPECKLE =
            "070000000900000000050000200300001e000000060000000900000000050000200300001e000000"
            + "020000000500000000050000200300001e000000";

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static UsbDesc.Profile p(String fcc, int w, int h, int fps) {
        UsbDesc.Profile p = new UsbDesc.Profile();
        p.fourcc = fcc;
        p.width = w;
        p.height = h;
        p.fps = fps;
        return p;
    }

    // the Raptor's colour MJPG modes at 1280x800 (VS5, fw 1.3.8)
    private static final List<UsbDesc.Profile> RAPTOR_COLOR = new ArrayList<>();
    static {
        for (int f : new int[]{5, 10, 15, 20, 30, 40, 50, 60}) RAPTOR_COLOR.add(p("MJPG", 1280, 800, f));
    }

    @Test
    public void colorCapPerWorkMode() {
        assertEquals(10, Bridge.colorCap(Bridge.parseEffectiveProfiles(hex(RAPTOR_LASER_LINES)), 1280, 800));
        assertEquals(30, Bridge.colorCap(Bridge.parseEffectiveProfiles(hex(RAPTOR_CHIP_SCALE_SPECKLE)), 1280, 800));
        assertEquals(0, Bridge.colorCap(Bridge.parseEffectiveProfiles(hex(RAPTOR_LASER_LINES)), 640, 400));
        assertEquals(0, Bridge.colorCap(null, 1280, 800));
    }

    /** What 0xFC1 keeps of the colour modes under an fps floor (keeping the rates in keep), then Creality Scan's pick. */
    private static int pickedColorFps(int floor, Set<Integer> keep, int wanted) {
        int best = -1;
        for (UsbDesc.Profile q : RAPTOR_COLOR) {
            if (UsbDesc.belowFpsFloor(RAPTOR_COLOR, q, floor, keep)) continue;
            if (q.fps == wanted) return wanted;
            if (q.fps > wanted && (best < 0 || q.fps < best)) best = q.fps;   // smallest advertised above, as the app
        }
        return best;
    }

    @Test
    public void boostFloorKeepsColourLimitsPerMode() {
        // Boost's 1280x800 floor is 30; Creality Scan over a bridge asks colour 10 in Laser Lines, 20 in Infrared
        assertEquals(30, pickedColorFps(30, Set.of(), 10));        // before: the IR floor hid colour's 10
        assertEquals(10, pickedColorFps(30, Set.of(10), 10));      // Laser Lines: 10, as native USB
        assertEquals(30, pickedColorFps(30, Set.of(10), 20));      // Infrared: still 30, as native USB
        assertEquals(10, pickedColorFps(30, Set.of(10, 30), 10));  // both modes remembered
        assertEquals(30, pickedColorFps(30, Set.of(30), 15));      // Otter (limit 30): boosted, as native USB
        assertEquals(10, pickedColorFps(0, Set.of(10), 10));       // Stock: no floor, unchanged
    }

    @Test
    public void streamStartCapsColourToTheCurrentMode() {
        UsbDesc.Profile asked = RAPTOR_COLOR.get(4);     // 30
        assertEquals(10, UsbDesc.capFps(RAPTOR_COLOR, asked, 10).fps);
        assertEquals(30, UsbDesc.capFps(RAPTOR_COLOR, asked, 30).fps);
        assertSame(asked, UsbDesc.capFps(RAPTOR_COLOR, asked, 0));                       // limit unknown
        assertSame(asked, UsbDesc.capFps(List.of(asked), asked, 10));                    // nothing slower
        assertEquals(20, UsbDesc.capFps(RAPTOR_COLOR, RAPTOR_COLOR.get(7), 25).fps);     // 60 under 25: 20
    }
}

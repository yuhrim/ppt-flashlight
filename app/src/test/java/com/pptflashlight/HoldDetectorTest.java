package com.pptflashlight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Off-device tests for the hold state machine (no Android runtime needed). */
public class HoldDetectorTest {

    private static final int SEND = 231;
    private static final int VOLUME_DOWN = 25;
    private static final long THRESHOLD = 500L;
    private static final long BASE = 1_000_000L;    // arbitrary monotonic origin

    private static HoldDetector detector() {
        return new HoldDetector(THRESHOLD);
    }

    @Test
    public void shortPressIsIgnored() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.SHORT_PRESS, d.onKeyUp(SEND, BASE + 139, BASE + 139));
    }

    @Test
    public void fourHundredMillisIsStillShort() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.SHORT_PRESS, d.onKeyUp(SEND, BASE + 400, BASE + 400));
    }

    @Test
    public void thresholdItselfToggles() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 500, BASE + 500));
    }

    @Test
    public void oneMillisecondBelowThresholdDoesNot() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.SHORT_PRESS, d.onKeyUp(SEND, BASE + 499, BASE + 499));
    }

    @Test
    public void oneSecondHoldTogglesOnce() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 1000, BASE + 1000));
        // A repeated UP for the same press must not toggle a second time.
        assertEquals(HoldDetector.Result.NONE, d.onKeyUp(SEND, BASE + 1000, BASE + 1000));
    }

    @Test
    public void threeSecondHoldTogglesOnce() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 3210, BASE + 3210));
        assertEquals(HoldDetector.Result.NONE, d.onKeyUp(SEND, BASE + 3211, BASE + 3211));
    }

    @Test
    public void autoRepeatDuringHoldDoesNotToggle() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        for (int i = 1; i <= 5; i++) {
            assertEquals(HoldDetector.Result.NONE, d.onKeyDown(SEND, i, BASE + i * 50L, BASE + i * 50L));
        }
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 1200, BASE + 1200));
    }

    @Test
    public void secondDownWithoutUpKeepsFirstTimestamp() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.NONE, d.onKeyDown(SEND, 0, BASE + 450, BASE + 450));
        // 600 ms after the first DOWN, 150 ms after the second: the real hold wins.
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 600, BASE + 600));
    }

    @Test
    public void upWithoutDownIsIgnored() {
        HoldDetector d = detector();
        assertEquals(HoldDetector.Result.NONE, d.onKeyUp(SEND, BASE + 1000, BASE + 1000));
        assertFalse(d.isPressed());
    }

    @Test
    public void releaseOfAnotherKeyEndsNothing() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.NONE, d.onKeyUp(VOLUME_DOWN, BASE + 700, BASE + 700));
        assertTrue(d.isPressed());
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 800, BASE + 800));
    }

    @Test
    public void lostUpEventIsDetectedAsStale() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.STALE_RESET,
                d.onKeyUp(SEND, BASE + HoldDetector.MAX_HOLD_MS + 1, BASE + HoldDetector.MAX_HOLD_MS + 1));
    }

    @Test
    public void fallsBackToUptimeWhenEventTimeIsMissing() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, 0L, BASE);
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, 0L, BASE + 750));
    }

    @Test
    public void backToBackPressesEachToggle() {
        HoldDetector d = detector();
        d.onKeyDown(SEND, 0, BASE, BASE);
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 900, BASE + 900));
        d.onKeyDown(SEND, 0, BASE + 2000, BASE + 2000);
        assertEquals(HoldDetector.Result.LONG_PRESS_TOGGLE, d.onKeyUp(SEND, BASE + 2600, BASE + 2600));
        d.onKeyDown(SEND, 0, BASE + 3000, BASE + 3000);
        assertEquals(HoldDetector.Result.SHORT_PRESS, d.onKeyUp(SEND, BASE + 3100, BASE + 3100));
    }

    @Test
    public void keyMatchingPrefersKeycodeAndFallsBackToScanCode() {
        ModuleConfig.Snapshot config =
                new ModuleConfig.Snapshot(true, 231, 231, 500, true, false, "/dev/null");
        assertTrue(config.matchesKey(231, 231));
        assertTrue("keycode match alone", config.matchesKey(231, 0));
        assertTrue("scan code match covers KEYCODE_UNKNOWN", config.matchesKey(0, 231));
        assertFalse(config.matchesKey(25, 115));
        assertFalse("no zero/zero match", config.matchesKey(0, 0));
    }
}

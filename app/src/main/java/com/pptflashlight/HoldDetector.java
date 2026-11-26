package com.pptflashlight;

/**
 * Decides, for one physical key, whether a press held the button long enough to toggle the
 * flashlight. Exactly one {@link Result#LONG_PRESS_TOGGLE} is produced per hold.
 *
 * <p>Pure Java on purpose: it runs on system_server's input dispatch thread and is exercised by
 * the off-device tests in {@code src/test}.
 *
 * <p>The duration is measured from the kernel event timestamps that the framework puts into
 * {@link android.view.KeyEvent#getEventTime()} (CLOCK_MONOTONIC, so it is unaffected by wall-clock
 * changes). {@code uptime} is passed in as a fallback for events that carry no usable timestamp.
 */
public final class HoldDetector {

    public enum Result {
        /** Nothing to do. */
        NONE,
        /** Released too early: ignore. */
        SHORT_PRESS,
        /** Held long enough: toggle the flashlight once. */
        LONG_PRESS_TOGGLE,
        /** Press lasted implausibly long (lost UP event): state reset, nothing to toggle. */
        STALE_RESET
    }

    /** A hold longer than this is treated as a stuck/lost UP instead of a deliberate press. */
    static final long MAX_HOLD_MS = 10_000L;

    private final long thresholdMs;

    private boolean pressed;
    private int pressedKeyCode;
    private long downEventTime;
    private long downUptime;

    public HoldDetector(long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("thresholdMs must be > 0, was " + thresholdMs);
        }
        this.thresholdMs = thresholdMs;
    }

    public long thresholdMs() {
        return thresholdMs;
    }

    public boolean isPressed() {
        return pressed;
    }

    public Result onKeyDown(int keyCode, int repeatCount, long eventTime, long uptime) {
        if (repeatCount > 0) {
            // Kernel auto-repeat (KEY_SEND does not emit these on the E6810, but stay safe).
            return Result.NONE;
        }
        if (pressed) {
            // A second DOWN without an UP: keep the first timestamp so the hold is measured once.
            return Result.NONE;
        }
        pressed = true;
        pressedKeyCode = keyCode;
        downEventTime = eventTime;
        downUptime = uptime;
        return Result.NONE;
    }

    public Result onKeyUp(int keyCode, long eventTime, long uptime) {
        if (!pressed) {
            // Stray UP (state lost across a reboot, or a key the framework released for us).
            return Result.NONE;
        }
        if (keyCode != pressedKeyCode) {
            return Result.NONE;
        }
        pressed = false;

        long durationMs = heldMillis(eventTime, uptime);
        if (durationMs > MAX_HOLD_MS) {
            return Result.STALE_RESET;
        }
        return durationMs >= thresholdMs ? Result.LONG_PRESS_TOGGLE : Result.SHORT_PRESS;
    }

    /** Drops any half-finished press, e.g. when the module is reconfigured. */
    public void reset() {
        pressed = false;
    }

    private long heldMillis(long eventTime, long uptime) {
        if (downEventTime > 0 && eventTime > 0) {
            long delta = eventTime - downEventTime;
            if (delta >= 0 && delta <= MAX_HOLD_MS * 2) {
                return delta;
            }
        }
        long delta = uptime - downUptime;
        return delta < 0 ? 0 : delta;
    }
}

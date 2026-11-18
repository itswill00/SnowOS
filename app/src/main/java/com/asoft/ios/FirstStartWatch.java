package com.asoft.ios;

import java.util.Locale;

/**
 * Decides how long the iOS 16 first start is waited for (PhoneSetup.firstStart). It keeps waiting while the
 * console shows something new, gives up when it has shown nothing new for SetupLimits.FIRST_START_QUIET_MS (or
 * nothing at all for FIRST_START_NO_CONSOLE_MS), and never waits past FIRST_START_CEILING_MS. No Android here.
 *
 * Signs of life: a console line not seen before (ConsoleLife), and a milestone the caller names (the display
 * going off and on). The phone's iOS 16 has no readable screen, so the screen is not a sign here.
 */
final class FirstStartWatch {
    enum Verdict { WAIT, NO_CONSOLE, QUIET, CEILING }

    private final long start;
    private final ConsoleLife life = new ConsoleLife();
    private long lastSign;
    private boolean anyConsole;

    FirstStartWatch(long nowMs) {
        start = nowMs;
        lastSign = nowMs;
    }

    /** New console text since the last call ("" when none) and whether a milestone was just seen. */
    void observe(long nowMs, String newText, boolean milestone) {
        int novel = life.feed(newText);
        if (!newText.isEmpty()) {
            anyConsole = true;
        }
        if (novel > 0 || milestone) {
            lastSign = nowMs;
        }
    }

    Verdict verdict(long nowMs) {
        long since = nowMs - start;
        if (since >= SetupLimits.FIRST_START_CEILING_MS) {
            return Verdict.CEILING;
        }
        if (!anyConsole && since >= SetupLimits.FIRST_START_NO_CONSOLE_MS) {
            return Verdict.NO_CONSOLE;
        }
        if (anyConsole && nowMs - lastSign >= SetupLimits.FIRST_START_QUIET_MS) {
            return Verdict.QUIET;
        }
        return Verdict.WAIT;
    }

    long sinceSignMin(long nowMs) {
        return (nowMs - lastSign) / SetupLimits.MIN;
    }

    /**
     * The boot's stage from the console's own words, or "" before the kernel says anything. The
     * lines are the guest's, so the stages read the same on every phone; a stage stays until a
     * later one replaces it. Ordered as a healthy boot prints them.
     */
    private static final String[][] STAGES = {
            // console fragment (case-insensitive contains), what the user reads
            {"bsd/init", "the kernel is starting"},
            {" attracting objects", "the kernel is starting"},
            {"launchd", "the system's first process (launchd) is starting"},
            {"apfs_module_start", "mounting the disk"},
            {"hfs:", "mounting the disk"},
            {"IOGraphics", "the display driver is coming up"},
            {"backboardd", "the display server (backboardd) is starting"},
            {"SpringBoard", "the home screen app (SpringBoard) is starting"},
            {"assetsd", "loading assets (wallpapers, icons)"},
            {"mediaserverd", "the media server is starting"},
            {"dataaccessd", "account and data services are starting"},
            {"lockbot", "the lock screen is coming up"},
            {"SWEET FB: display off", "the screen dimmed after boot"},
    };

    /** What the boot is doing now, in plain words, from the last console lines; "" before the kernel talks. */
    String stage() {
        return stage(life);
    }

    /** Same as stage(), on a caller's ConsoleLife (tests feed lines without a boot). */
    static String stage(ConsoleLife l) {
        for (String[] s : STAGES) {
            if (l.saw(s[0])) {
                return s[1];
            }
        }
        return l.lines() > 0 ? "the kernel is starting" : "";
    }

    /** The line the setup screen shows while it waits. */
    String status(long nowMs) {
        long min = (nowMs - start) / SetupLimits.MIN;
        String stage = stage();
        if (!anyConsole) {
            return String.format(Locale.ROOT, "Booting iOS (%d min): waiting for its first words on the console.", min);
        }
        long ago = sinceSignMin(nowMs);
        String doing = stage.isEmpty() ? "starting" : stage;
        String pace = min <= 5 ? "" : min <= 25 ? "" : " Slower than a fast phone (about 25 min), but still working.";
        return String.format(Locale.ROOT, "First start: %d min in, %s. Last sign of life %s.%s",
                min, doing, ago < 1 ? "just now" : ago + " min ago", pace);
    }

    /** Why it gave up, in plain words, for the error the user reads. */
    String giveUpText(Verdict v, long nowMs) {
        long min = (nowMs - start) / SetupLimits.MIN;
        switch (v) {
            case NO_CONSOLE:
                return "iOS printed nothing in " + min + " min, so it is not starting. Tap Resume to try again; if it happens again, send the log.";
            case QUIET:
                return "iOS has done nothing new for " + sinceSignMin(nowMs) + " min (" + min
                        + " min into its first start). Tap Resume to carry on from where it was.";
            case CEILING:
                return "iOS has been setting itself up for " + min + " min and is still not done. Tap Resume to carry on from where it was.";
            default:
                return "";
        }
    }
}

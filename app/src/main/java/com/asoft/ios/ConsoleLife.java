package com.asoft.ios;

import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a boot's console says about the guest being alive, from its text alone: lines it has not printed before
 * (digits ignored, so `hfs: set hardlink bit ... cnid=450710` repeating is one line, and so is a respawned bash's
 * launch line), and the highest pid it printed. The app's own command echoes (`@@G...@@`) and bare prompts are
 * the app talking, not the guest, and do not count.
 */
final class ConsoleLife {
    private static final Pattern PID = Pattern.compile("pid:\\s?(\\d+)");
    private static final int KEY_LEN = 120;
    private static final int KEYS_MAX = 20_000;

    private final HashSet<String> seen = new HashSet<>();
    private final StringBuilder carry = new StringBuilder();
    private int maxPid;
    private long totalLines;

    /** A line's identity: its first KEY_LEN characters with every digit run made one "#". */
    static String key(String line) {
        String s = line.length() > KEY_LEN ? line.substring(0, KEY_LEN) : line;
        return s.replaceAll("\\d+", "#").trim();
    }

    /** True for what the app or the shell itself wrote: command echoes, tags, an empty prompt. */
    static boolean ours(String line) {
        String t = line.trim();
        return t.isEmpty() || t.contains("@@") || t.matches("(bash-[0-9.]+)?[#$]");
    }

    /** @return how many lines of @text (completed ones; a partial last line waits for the next call) are new. */
    int feed(String text) {
        carry.append(text);
        int novel = 0;
        int nl;
        while ((nl = carry.indexOf("\n")) >= 0) {
            String line = carry.substring(0, nl).replace("\r", "");
            carry.delete(0, nl + 1);
            novel += line(line) ? 1 : 0;
        }
        if (carry.length() > 16_384) {      // a "line" that never ends is no line
            String line = carry.toString();
            carry.setLength(0);
            novel += line(line) ? 1 : 0;
        }
        Matcher m = PID.matcher(text);
        while (m.find()) {
            try {
                maxPid = Math.max(maxPid, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
                // a pid that long is no pid
            }
        }
        return novel;
    }

    private boolean line(String line) {
        // a prompt glued to the front of the guest's own text ("bash-5.3# hfs: ...") is still the guest's text
        String l = line.replaceFirst("^bash-[0-9.]+# ", "");
        if (ours(l)) {
            return false;
        }
        totalLines++;
        if (seen.size() >= KEYS_MAX) {
            return false;
        }
        String k = key(l);
        if (!seen.add(k)) {
            return false;
        }
        seenLower.add(k.toLowerCase(java.util.Locale.ROOT));
        return true;
    }

    /**
     * The text of @f after @at[0] (the newest @cap bytes when more came); @at[0] becomes the file's length.
     * "" when the file is missing or unreadable.
     */
    static String readNew(java.io.File f, long[] at, int cap) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long len = r.length();
            long from = at[0] > len ? 0 : at[0];
            if (len - from > cap) {
                from = len - cap;
            }
            byte[] b = new byte[(int) (len - from)];
            r.seek(from);
            r.readFully(b);
            at[0] = len;
            return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    int maxPid() {
        return maxPid;
    }

    long lines() {
        return totalLines;
    }

    int distinct() {
        return seen.size();
    }

    /**
     * Whether a console line matched @text (as a lowercase substring) since the boot began. The
     * keys digits are flattened, so this matches only text with no digits of its own; the stage
     * words (FirstStartWatch.STAGES) are chosen so they are. Case-insensitive over the keys, which
     * are lower-cased here once.
     */
    boolean saw(String text) {
        String want = text.toLowerCase(java.util.Locale.ROOT);
        for (String k : seenLower) {
            if (k.contains(want)) {
                return true;
            }
        }
        return false;
    }

    private final java.util.List<String> seenLower = new java.util.ArrayList<>();
}

package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An .ipa into iOS 26: the PC installer's Install-Ipa.ps1 on the phone. The live install (installd) needs an
 * APFS Data volume and iOS 26's disk is HFS+, so the app goes in the way the system apps do: the helper VM puts
 * the .app into /Applications on the user disk and adds every Mach-O's CDHash to a trust cache (setup/phone/
 * jobs/ios26-ipa.sh). A developer-signed app (get-task-allow, which AMFI refuses while Developer Mode is off)
 * has that entitlement switched off first (noget.py, the PC's too), and every Mach-O slice without a SHA-256 code
 * directory that matches its pages (unsigned, SHA-1 only, a decrypted binary's changed pages) is signed again ad hoc
 * (setup/phone/adhoc.py); a file that cannot be signed fails the install. Every main executable (the app's, its app extensions)
 * also gets com.apple.private.set-exception-port, which the iOS 26 kernel demands of a task that sets exception ports
 * (Sentry and other crash reporters: without it the app is killed with EXC_GUARD six seconds after the launch). The new app is made beside the old one and
 * takes its place only when everything is ready, so a failure leaves the disk as it was. Done by hand once on 2026-11-12 (Super Mario
 * 64), what this does.
 *
 * What it changes, and what follows from it:
 *  - the trust cache is the disk's own (files/ios26-2x-user.apps.tc, Guest.tc26): the shared one in files/ios26
 *    stays as the setup made it, and an erased or replaced user disk takes its trust cache with it;
 *  - the disk's quick save is marked stale: loaded, it would bring the disk back without the app. It stays as the
 *    way back (Go back), which removes the app again; the next start boots, the first one rebuilds LaunchServices
 *    (about 7 minutes to the lock screen, the icon a few minutes after);
 *  - only the user disk of the screen scale in use gets the app (Guest.userDisk).
 * iOS must not run: the disk is written offline.
 */
final class Ipa26 {
    private Ipa26() {}

    interface Progress {
        /** @pct 0-100, or -1 when it has no number (copying a file of unknown size). */
        void at(String what, int pct);
    }

    static final class Result {
        String app = "", bundleId = "";
        boolean isNew = true;
        /** "AngryBirds2.app (was com.rovio.baba 25.0)" when the job replaced an app that was there already, else "". */
        String replaced = "";
        int noget;                                  // files that had get-task-allow switched off
        /** "PATH (reason)": every Mach-O signed ad hoc (setup/phone/adhoc.py signs all of them; the names go to the log). */
        final List<String> signed = new ArrayList<>();
        /** "PATH N": libraries whose N entitlements were left out of their new signature. */
        final List<String> entsDropped = new ArrayList<>();
        /** "PATH KEY": the entitlement ipa-hashes.py gave a main executable (the app's own, app extensions); for the log. */
        final List<String> entsAdded = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        long seconds;
    }

    private static volatile boolean busy;

    /** An install runs (the setup screen's buttons wait for it). */
    static boolean busy() {
        return busy;
    }

    static File jobDir(Context c) {
        return new File(c.getFilesDir(), "helper-jobs/ios26-ipa");
    }

    /** The .ipa files in the app's import folder (what adb push puts there, next to the .asoft bundles). */
    static File[] waiting(Context c) {
        File[] f = Installer.importDir(c).listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".ipa"));
        return f == null ? new File[0] : f;
    }

    /** Null when an .ipa can go in now; else why not, in plain words. */
    static String refusal(Context c) {
        if (busy) {
            return "An .ipa is being installed already.";
        }
        if (Guest.running() || Guest.saving()) {
            return "Close iOS first: side bar > Saves > Save and close. The app is put on the disk while iOS is off.";
        }
        if (SetupService.state.running) {
            return "A setup is running. Wait until it is done.";
        }
        if (!Guest.available26(c)) {
            return "iOS 26 is not set up on this phone.";
        }
        if (!Guest.userDisk(c, Guest.IOS26).isFile()) {
            return "Start iOS 26 once first, so it has its own disk. Then close it (Save and close) and try again.";
        }
        return null;
    }

    private static String asset(Context c, String name) throws IOException {
        try (InputStream s = c.getAssets().open(name)) {
            return new String(Io.readAll(s), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.FileNotFoundException e) {
            throw new IOException("this build has no " + name + " (pull-setup.sh)");
        }
    }

    /** Where @disk's apps are kept in the "guest" preferences: the names the user added from an .ipa. */
    private static String appsKey(String disk) {
        return "ipa-apps-" + disk;
    }

    /** The apps installed from an .ipa on the iOS 26 disk in use, "A, B", or "" (for the Go back text). */
    static String apps(Context c) {
        String disk = Guest.userDisk(c, Guest.IOS26).getName();
        return c.getSharedPreferences("guest", Context.MODE_PRIVATE).getString(appsKey(disk), "");
    }

    private static void remember(Context c, String app) {
        String disk = Guest.userDisk(c, Guest.IOS26).getName();
        android.content.SharedPreferences p = c.getSharedPreferences("guest", Context.MODE_PRIVATE);
        String have = p.getString(appsKey(disk), "");
        List<String> list = new ArrayList<>();
        for (String a : have.split(", ")) {
            if (!a.isEmpty()) {
                list.add(a);
            }
        }
        if (!list.contains(app)) {
            list.add(app);
        }
        p.edit().putString(appsKey(disk), String.join(", ", list)).commit();
    }

    /** The user disk @diskFile (its name, "ios26-2x-user.qcow2") was set aside or replaced: its trust cache goes too. */
    static void dropTc(Context c, String diskFile) {
        String base = diskFile.endsWith(".qcow2") ? diskFile.substring(0, diskFile.length() - 6) : diskFile;
        new File(c.getFilesDir(), base + ".apps.tc").delete();
        forget(c, diskFile);
    }

    /** The user disk is new or gone: no app of an .ipa is on it. */
    static void forget(Context c, String disk) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().remove(appsKey(disk)).commit();
    }

    /**
     * Installs the .ipa @in (@total bytes, or -1; @name only for the log) into iOS 26's user disk. About a minute
     * for a small app, plus the time the file takes to copy. Not on the UI thread; iOS must be off (refusal).
     * Throws an IOException with a plain message when it did not work; the disk is then as it was (a staging
     * folder a cut job left is taken away by the next try).
     */
    static Result install(Context c, InputStream in, long total, String name, Progress p, BooleanSupplier cancelled)
            throws IOException {
        String why = refusal(c);
        if (why != null) {
            throw new IOException(why);
        }
        busy = true;
        long t0 = System.currentTimeMillis();
        File jd = jobDir(c);
        File disk = Guest.userDisk(c, Guest.IOS26);
        try {
            Helper.endLeftover(c, jd);
            Installer.deleteTree(jd);
            jd.mkdirs();
            if (!jd.isDirectory()) {
                throw new IOException("cannot make " + jd);
            }
            // the app is copied here whole (the helper VM reads it as a disk), unpacked on the disk: about four
            // times its size, kept free
            long need = total > 0 ? total * 4 + 1_000_000_000L : 2_000_000_000L;
            if (c.getFilesDir().getUsableSpace() < need) {
                throw new IOException(String.format(java.util.Locale.ROOT, "Not enough space: this .ipa needs about %.1f GB free, "
                        + "the phone has %.1f GB.", need / 1e9, c.getFilesDir().getUsableSpace() / 1e9));
            }
            File ipa = new File(jd, "app.ipa");
            copy(in, ipa, total, p, cancelled);
            Result res = new Result();
            checkIpa(ipa, res);
            // the helper VM reads a raw disk in sectors: the zip may be followed by zeros (its end record is found
            // from the back)
            try (RandomAccessFile r = new RandomAccessFile(ipa, "rw")) {
                r.setLength((ipa.length() + 511) / 512 * 512);
            }
            p.at("Starting the helper VM", 0);
            File base = Guest.tc26(c);
            Map<String, Object> files = new LinkedHashMap<>();
            files.put(".job/run", "run.sh\n");
            files.put(".job/disks", "d0 user.qcow2\nd1 app.ipa\n");
            files.put(".job/out", "ipa.tc\n");
            files.put(".job/scratch", "");
            files.put("run.sh", "export ASOFT_S=scripts\n" + asset(c, "setup/phone/prelude.sh") + "\n"
                    + asset(c, "setup/phone/jobs/ios26-ipa.sh"));
            files.put("scripts/root26-do.sh", asset(c, "setup/scripts/ios26/root26-do.sh"));
            for (String n : new String[] {"noget.py", "cdhash.py", "mktc.py"}) {
                files.put("scripts/" + n, asset(c, "setup/scripts/ios26/" + n));
            }
            files.put("phone/ipa-place.py", asset(c, "setup/phone/ipa-place.py"));
            files.put("phone/ipa-hashes.py", asset(c, "setup/phone/ipa-hashes.py"));
            files.put("phone/adhoc.py", asset(c, "setup/phone/adhoc.py"));
            // ipa-hashes.py gives every main executable com.apple.private.set-exception-port; debug builds: ios26.txt lines
            // "ipa-ent=KEY" add more, "ipa-ent-default=off" leaves that one out (a test of the exception-port rule)
            String ents = Guest.ipaEntitlements(c);
            if (!ents.isEmpty()) {
                files.put("ipa.ents", ents);
            }
            files.put("base.tc", base);
            File inTar = new File(jd, "in.tar"), outRaw = new File(jd, "out.raw");
            TarIo.write(inTar, files);
            try (RandomAccessFile r = new RandomAccessFile(outRaw, "rw")) {
                // the trust cache goes back as a tar: its size, the app's hashes and the tar's own blocks
                r.setLength(base.length() + (4 << 20));
            }
            List<Helper.Disk> disks = new ArrayList<>();
            disks.add(new Helper.Disk("in", inTar, "raw", true));
            disks.add(new Helper.Disk("out", outRaw, "raw", false));
            disks.add(new Helper.Disk("d0", disk, "qcow2", false, true));
            disks.add(new Helper.Disk("d1", ipa, "raw", true));
            String[] fail = {null};
            Helper.Listener l = new Helper.Listener() {
                @Override
                public void progress(int pct, String text) {
                    p.at(text, pct);
                }

                @Override
                public void line(String text) {
                    line26(text, res, fail);
                }
            };
            int cpus = Math.min(8, Runtime.getRuntime().availableProcessors());
            // the setup's thermal hold is its own: this runs as a start does
            BooleanSupplier hold = Helper.hold;
            Helper.hold = null;
            Helper.Result r;
            try {
                r = Helper.run(c, "run", disks, cpus, jd, l, cancelled, true, Helper.ramMiB(c, Helper.FLOOR_WORK_IN_RAM), Helper.FLOOR_WORK_IN_RAM);
                if (r.rc != 0 && fail[0] == null && Helper.worthRetry(r, Helper.FLOOR_WORK_IN_RAM) && !cancelled.getAsBoolean()) {
                    int ram = Helper.retryRam(r, Helper.FLOOR_WORK_IN_RAM);
                    AppLog.note(c, "ipa26: the helper VM failed (" + r.failure() + ", " + r.how() + "); again under TCG with " + ram + " MiB");
                    res.signed.clear();
                    res.entsDropped.clear();
                    res.warnings.clear();
                    r = Helper.run(c, "run", disks, cpus, jd, l, cancelled, false, ram, Helper.FLOOR_WORK_IN_RAM);
                }
            } finally {
                Helper.hold = hold;
            }
            if (r.rc != 0) {
                // (a refusal of the app itself says so; else the VM's own reason)
                throw new IOException(fail[0] != null ? fail[0] : "the helper VM failed: " + r.failure());
            }
            if (r.outBytes <= 0) {
                throw new IOException("the helper VM handed no trust cache back");
            }
            File out = new File(jd, "out");
            out.mkdirs();
            TarIo.read(outRaw, r.outBytes, out);
            File tc = new File(out, "ipa.tc");
            if (!tc.isFile() || tc.length() < 48 || tc.length() % 24 != 0) {   // version 2: 24 bytes of header, 24 an entry
                throw new IOException("the trust cache the helper VM made is not whole");
            }
            // renamed into place: a cut copy never passes for the trust cache
            File own = Guest.appsTc26(c), part = new File(own.getPath() + ".part");
            java.nio.file.Files.copy(tc.toPath(), part.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!part.renameTo(own)) {
                throw new IOException("cannot keep the trust cache " + own);
            }
            // the disk is not where its quick save was: loaded by itself, that save would take the app away
            Guest.markQuickStale(c, Guest.IOS26);
            remember(c, res.app);
            res.seconds = (System.currentTimeMillis() - t0) / 1000;
            AppLog.note(c, "ipa26: " + (name == null ? "?" : name) + " -> " + res.app + " (" + res.bundleId + ") on " + disk.getName()
                    + (res.replaced.isEmpty() ? res.isNew ? ", new" : ", replaced" : ", replaced " + res.replaced + (res.isNew ? ", other id" : "")) + ", get-task-allow off in " + res.noget + " file(s), signed ad hoc: "
                    + res.signed + (res.entsDropped.isEmpty() ? "" : ", library entitlements left out: " + res.entsDropped)
                    + (res.entsAdded.isEmpty() ? "" : ", entitlement added (file key): " + res.entsAdded) + ", " + res.seconds + " s" + (r.nat ? " (native)" : " (TCG)"));
            Installer.deleteTree(jd);
            return res;
        } finally {
            busy = false;
        }
    }

    /** The job's ipa.ents file from ios26.txt lines (see Guest.ipaEntitlements). */
    static String entLines(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            l = l.trim();
            if (l.equals("ipa-ent-default=off")) {
                b.append("-default").append((char) 10);
            } else if (l.startsWith("ipa-ent=") && l.length() > 8) {
                b.append(l.substring(8)).append((char) 10);
            }
        }
        return b.toString();
    }

    static void line26(String text, Result res, String[] fail) {
        String t = text.trim();
        if (t.startsWith("ipa-app ")) {
            String[] a = t.substring(8).split(" ", 2);
            res.app = a[0];
            res.bundleId = a.length > 1 && !"?".equals(a[1]) ? a[1] : "";
        } else if (t.startsWith("ipa-new ")) {
            res.isNew = t.endsWith("1");
        } else if (t.startsWith("ipa-replaced ")) {
            String[] a = t.substring(13).split(" ", 3);
            if (!res.replaced.isEmpty()) {
                return;     // (a retry sees the first try's app as the old one)
            }
            res.replaced = a[0] + " (was " + (a.length > 1 ? a[1] : "?") + " " + (a.length > 2 ? a[2] : "?") + ")";
        } else if (t.startsWith("ipa-noget ")) {
            try {
                res.noget = Integer.parseInt(t.substring(10).trim());
            } catch (NumberFormatException ignored) {
                // not a number: none counted
            }
        } else if (t.startsWith("ipa-ent ")) {
            res.entsAdded.add(t.substring(8));      // "PATH KEY"
        } else if (t.startsWith("ipa-ents-dropped ")) {
            res.entsDropped.add(t.substring(17));
        } else if (t.startsWith("ipa-signed ")) {
            // "PATH REASON" (REASON: unsigned, SHA-1_only, its_code_pages_do_not_match_its_signature)
            String[] a = t.substring(11).split(" ", 2);
            res.signed.add(a[0] + (a.length > 1 ? " (" + a[1].replace('_', ' ') + ")" : ""));
        } else if (t.startsWith("ipa-warn ")) {
            res.warnings.add(t.substring(9));
        } else if (t.startsWith("ipa-fail ")) {
            fail[0] = t.substring(9);
        }
    }

    /** Copies @in to @to, with progress; an .ipa can be a gigabyte. */
    private static void copy(InputStream in, File to, long total, Progress p, BooleanSupplier cancelled) throws IOException {
        byte[] buf = new byte[1 << 20];
        long done = 0;
        long shown = 0;
        try (OutputStream o = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Stopped.");
                }
                o.write(buf, 0, n);
                done += n;
                if (done - shown >= (8 << 20)) {
                    shown = done;
                    p.at(String.format(java.util.Locale.ROOT, "Copying the .ipa: %.0f MB", done / 1e6),
                            total > 0 ? (int) (done * 100 / total) / 10 : -1);   // the copy is the first tenth of the bar
                }
            }
        }
    }

    /** Looks into the copied .ipa: a zip with Payload/NAME.app, else an IOException saying so. Fills app and bundle id. */
    private static void checkIpa(File ipa, Result res) throws IOException {
        try (ZipFile z = new ZipFile(ipa)) {
            String app = null;
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements() && app == null; ) {
                String[] parts = e.nextElement().getName().split("/");
                if (parts.length >= 3 && parts[0].equals("Payload") && parts[1].endsWith(".app")) {
                    app = parts[1];
                }
            }
            if (app == null) {
                throw new IOException("This file has no app inside (Payload/NAME.app): it is not an .ipa.");
            }
            res.app = app;
        } catch (java.util.zip.ZipException e) {
            throw new IOException("This file is not a readable .ipa (" + e.getMessage() + ").");
        }
    }

    /** The text under the result: what the job saw, in plain words. */
    static String summary(Result r) {
        StringBuilder s = new StringBuilder();
        // (the job says when an app of that name was there: "replaced AngryBirds2.app (was <old id> <old version>)")
        s.append(!r.replaced.isEmpty() ? "Replaced " + r.replaced + " on iOS 26's disk." : r.isNew ? r.app + " is on iOS 26's disk."
                : r.app + " was replaced on iOS 26's disk.");
        s.append(" Start iOS 26 now: it boots (the first start after this takes about 7 minutes to the lock screen, "
                + "and the icon shows a few minutes later).");
        if (r.noget > 0) {
            s.append("\nThis app was signed for development: that was switched off so iOS 26 lets it run.");
        }
        if (!r.signed.isEmpty()) {
            // (one line: an app has dozens of frameworks; the names are in the log)
            s.append("\n").append(r.signed.size()).append(r.signed.size() == 1 ? " file" : " files")
                    .append(" of this app ").append(r.signed.size() == 1 ? "was" : "were").append(" signed again on this phone so that iOS 26 accepts ")
                    .append(r.signed.size() == 1 ? "it" : "them").append(".");
            if (!r.entsDropped.isEmpty()) {
                s.append(" The entitlements of ").append(r.entsDropped.size()).append(r.entsDropped.size() == 1 ? " library were" : " libraries were")
                        .append(" left out: an app only uses its main file's.");
            }
        }
        s.append("\nGo back to an older save would take the app away again.");
        return s.toString();
    }
}

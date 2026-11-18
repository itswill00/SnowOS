package com.asoft.ios;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Some phone makers' battery managers close an app that runs for a long time in the background (a OnePlus
 * did it 22 minutes into the setup). Android's own exemption ("Unrestricted" battery use) is asked for once
 * per install, before a setup or a start of iOS, with a dialog that says why.
 */
final class Battery {
    private static final String ASKED = "asked-battery";

    private Battery() {
    }

    /** True when the app is already exempt (or the system cannot say). */
    static boolean exempt(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /**
     * Runs @then, first asking (@why begins the question: what runs for long) once per install for the exemption when the app does not have it. After "Yes" the
     * system's own dialog opens and @then runs only if @goOnAfterYes (a caller that opens a screen of its own
     * next would cover that dialog, so it lets the user start again instead; a caller that starts a service does
     * not cover it).
     */
    static void askOnce(Activity a, String why, boolean goOnAfterYes, Runnable then) {
        android.content.SharedPreferences p = a.getSharedPreferences("setup", Context.MODE_PRIVATE);
        if (exempt(a) || p.getBoolean(ASKED, false)) {
            then.run();
            return;
        }
        p.edit().putBoolean(ASKED, true).apply();
        new android.app.AlertDialog.Builder(a)
                .setTitle("Keep running in the background?")
                .setMessage(why + ", and some phones close apps that run that long. "
                        + "Allow SnowOS to keep running?")
                .setPositiveButton("Yes", (d, w) -> {
                    request(a);
                    if (goOnAfterYes) {
                        then.run();
                    }
                })
                .setNegativeButton("No", (d, w) -> then.run())
                .setCancelable(false)
                .show();
    }

    private static void request(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException e2) {
                AppLog.note(a, "battery: no screen for the exemption: " + e2);
            }
        }
    }
}

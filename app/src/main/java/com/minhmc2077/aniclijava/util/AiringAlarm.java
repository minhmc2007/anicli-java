package com.minhmc2077.aniclijava.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.data.AiringChecker;
import com.minhmc2077.aniclijava.ui.AiringReceiver;

/**
 * Inexact repeating alarm. Android may batch or delay it in Doze, but it needs
 * no exact-alarm permission and works without a foreground service.
 */
public final class AiringAlarm {
    private static final int REQUEST_CODE = 4102;

    private AiringAlarm() {
    }

    /**
     * Keeps the alarm only while there is something to check: favourites for the
     * "new episode" badge, or anime with airing alerts enabled.
     */
    public static void sync(Context context) {
        boolean tracked = App.get().airing().hasSubscriptions()
                || !App.get().favourites().getAll().isEmpty();
        if (tracked) {
            schedule(context);
        } else {
            cancel(context);
        }
    }

    public static void schedule(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + AiringChecker.CHECK_INTERVAL_MS,
                    AiringChecker.CHECK_INTERVAL_MS,
                    pendingIntent(context));
        } catch (SecurityException ignored) {
            // Some OEM builds restrict repeating alarms; the app start check
            // still refreshes the cache when it runs.
        }
    }

    public static void cancel(Context context) {
        PendingIntent pending = pendingIntent(context);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            manager.cancel(pending);
        }
        pending.cancel();
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, AiringReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

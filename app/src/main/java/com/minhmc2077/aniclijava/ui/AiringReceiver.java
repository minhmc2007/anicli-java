package com.minhmc2077.aniclijava.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.data.AiringChecker;
import com.minhmc2077.aniclijava.util.AiringAlarm;

public final class AiringReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AiringAlarm.sync(context);
            AiringChecker.refreshAsync(context, null);
            return;
        }

        // The check performs network requests, so keep the receiver alive until
        // it finishes instead of relying on the process staying around.
        final PendingResult pending = goAsync();
        App.get().io().execute(() -> {
            try {
                AiringChecker.refresh(context);
            } catch (Exception ignored) {
                // Retry on the next alarm.
            } finally {
                AiringAlarm.sync(context);
                pending.finish();
            }
        });
    }
}

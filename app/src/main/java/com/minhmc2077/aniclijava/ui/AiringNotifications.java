package com.minhmc2077.aniclijava.ui;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.Anime;

/**
 * Local notification for a newly aired episode. No server or push service is
 * involved.
 */
public final class AiringNotifications {
    private static final String CHANNEL_ID = "airing";

    private AiringNotifications() {
    }

    public static void show(Context context, Anime anime, String episode) {
        if (anime == null || !canNotify(context)) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        ensureChannel(context, manager);

        String text = context.getString(R.string.airing_notification_text, episode);
        Intent intent = AnimeDetailActivity.intent(context, anime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId(anime.id),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(anime.title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL)
                .setShowWhen(true)
                .build();
        manager.notify(notificationId(anime.id), notification);
    }

    public static void cancel(Context context, String animeId) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(notificationId(animeId));
        }
    }

    private static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.airing_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.airing_channel_description));
        manager.createNotificationChannel(channel);
    }

    private static int notificationId(String animeId) {
        return animeId == null ? 0 : animeId.hashCode();
    }
}

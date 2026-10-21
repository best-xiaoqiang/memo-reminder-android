package com.example.memoreminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

public final class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "schedule_reminders_v3";

    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "日程提醒", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("日程到点提醒");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 180, 500});
            channel.setSound(Uri.parse("content://settings/system/notification_sound"), new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
        String title = intent.getStringExtra("title");
        String detail = intent.getStringExtra("detail");
        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.app.PendingIntent contentIntent = android.app.PendingIntent.getActivity(
                context, (int) System.currentTimeMillis(), openApp,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, CHANNEL_ID)
                : new android.app.Notification.Builder(context);
        builder.setPriority(android.app.Notification.PRIORITY_HIGH)
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        manager.notify((int) System.currentTimeMillis(), builder
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title == null ? "日程提醒" : title)
                .setContentText(detail == null ? "" : detail)
                .setContentIntent(contentIntent)
                .setShowWhen(true)
                .setAutoCancel(true)
                .build());
    }
}

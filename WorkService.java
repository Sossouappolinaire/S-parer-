package com.stemnotes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Service de premier plan : tant qu'une séparation ou une analyse est en cours, une notification
 * « en cours » est affichée et Android n'arrête plus l'application quand on la quitte.
 */
public class WorkService extends Service {

    private static final String CHANNEL = "work";
    private static final int NOTIF_ID = 7;
    private static volatile boolean running = false;
    private static volatile long lastUpdate = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra("text");
        if (text == null || text.isEmpty()) text = "Traitement en cours…";
        Notification n = build(this, text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            // refusé par le système : le travail continue simplement sans protection
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private static Notification build(Context c, String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Traitement des morceaux",
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setContentTitle("Constant séparateur")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true);
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentIntent(PendingIntent.getActivity(c, 0, open, fl));
        return b.build();
    }

    /** Met à jour le texte de la notification (au plus une fois par seconde et demie). */
    static void update(Context c, String text) {
        if (!running || text == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 1500) return;
        lastUpdate = now;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, build(c, text));
        } catch (Throwable ignored) {
        }
    }
}

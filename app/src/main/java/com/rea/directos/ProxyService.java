package com.rea.directos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Servicio en primer plano que mantiene el proxy HLS local (resuelve API, salt y referer). */
public class ProxyService extends Service {

    public static final String ACTION_STOP = "com.rea.directos.action.STOP";

    private static final String CHANNEL_ID = "proxy";
    private static final int NOTIF_ID = 1;
    private static final int BASE_PORT = 8899;
    private static final int MAX_PORT_TRIES = 30;

    private static final Object LOCK = new Object();
    private static final List<Runnable> READY_LISTENERS = new ArrayList<>();
    private static final List<Consumer<String>> ERROR_LISTENERS = new ArrayList<>();
    private static final ConcurrentHashMap<Long, StreamInfo> STREAMS = new ConcurrentHashMap<>();

    private static volatile ProxyService instance;
    private static volatile boolean ready;
    private static volatile boolean initializing;
    private static volatile int port;

    public static volatile String dataApi;
    public static volatile String salt = RbApi.FALLBACK_SALT;
    public static volatile String referer;

    private ProxyServer server;

    private final ProxyServer.Provider provider = new ProxyServer.Provider() {
        @Override public StreamInfo stream(long sid) { return STREAMS.get(sid); }
        @Override public String salt() { return salt; }
        @Override public String referer() { return referer; }
    };

    // ------------------------------------------------------------------
    // API estatica (misma app, mismo proceso)
    // ------------------------------------------------------------------
    public static void ensureStarted(Context ctx) {
        if (ready && instance != null) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, ProxyService.class));
    }

    public static boolean isReady() {
        return ready;
    }

    public static int port() {
        return port;
    }

    /** Ejecuta onReady cuando el proxy esta listo; onError si el arranque falla. */
    public static void whenReady(Runnable onReady, Consumer<String> onError) {
        synchronized (LOCK) {
            if (ready) {
                new Handler(Looper.getMainLooper()).post(onReady);
                return;
            }
            READY_LISTENERS.add(onReady);
            if (onError != null) ERROR_LISTENERS.add(onError);
        }
    }

    public static void addStream(StreamInfo s) {
        STREAMS.put(s.sid, s);
    }

    public static StreamInfo stream(long sid) {
        return STREAMS.get(sid);
    }

    public static String proxyUrl(long sid) {
        return "http://127.0.0.1:" + port + "/go/" + sid + "/index.m3u8";
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForegroundCompat("Iniciando…");
        startInit();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInit();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
            server = null;
        }
        ready = false;
        instance = null;
        super.onDestroy();
    }

    private void startInit() {
        boolean go;
        synchronized (LOCK) {
            go = !ready && !initializing;
            if (go) initializing = true;
        }
        if (go) new Thread(this::init, "proxy-init").start();
    }

    private void init() {
        try {
            String api = RbApi.resolveDataApi();
            String[] params = RbApi.fetchParams(api);
            dataApi = api;
            salt = params[0];
            referer = params[1];

            ProxyServer s = null;
            int chosen = 0;
            for (int candidate = BASE_PORT; candidate < BASE_PORT + MAX_PORT_TRIES && s == null; candidate++) {
                try {
                    s = new ProxyServer(candidate, provider);
                    s.start(ProxyServer.SOCKET_READ_TIMEOUT, false);
                    chosen = candidate;
                } catch (Exception e) {
                    s = null;
                }
            }
            if (s == null) throw new RuntimeException("no hay puerto libre para el proxy");
            server = s;
            port = chosen;
            ready = true;
            updateNotification("Proxy activo (puerto " + port + ")");
            flushReady();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            flushError("No se pudo iniciar el proxy: " + msg);
            stopForegroundCompat();
            stopSelf();
        } finally {
            initializing = false;
        }
    }

    private void flushReady() {
        Handler h = new Handler(Looper.getMainLooper());
        List<Runnable> list;
        synchronized (LOCK) {
            list = new ArrayList<>(READY_LISTENERS);
            READY_LISTENERS.clear();
            ERROR_LISTENERS.clear();
        }
        for (Runnable r : list) h.post(r);
    }

    private void flushError(String msg) {
        Handler h = new Handler(Looper.getMainLooper());
        List<Consumer<String>> list;
        synchronized (LOCK) {
            list = new ArrayList<>(ERROR_LISTENERS);
            READY_LISTENERS.clear();
            ERROR_LISTENERS.clear();
        }
        for (Consumer<String> c : list) h.post(() -> c.accept(msg));
    }

    // ------------------------------------------------------------------
    // Notificacion
    // ------------------------------------------------------------------
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Proxy", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ProxyService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_play)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "Detener", stop)
                .build();
    }

    private void startForegroundCompat(String text) {
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(text));
    }

    private void stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }
}

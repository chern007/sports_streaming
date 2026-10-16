package com.rea.directos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    private ConnectivityManager.NetworkCallback netCallback;

    private final ProxyServer.Provider provider = new ProxyServer.Provider() {
        @Override public StreamInfo stream(long sid) { return STREAMS.get(sid); }
        @Override public String salt() { return salt; }
        @Override public String referer() { return referer; }
        @Override public List<StreamInfo> streamList() { return new ArrayList<>(STREAMS.values()); }
        @Override public String phoneIp() { return wifiIpv4(ProxyService.this); }
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

    /** URL del proxy vista desde la red local (la usa el Chromecast); null si no hay WiFi. */
    public static String lanUrl(Context ctx, long sid) {
        String ip = wifiIpv4(ctx);
        if (ip == null) return null;
        return "http://" + ip + ":" + port + "/go/" + sid + "/index.m3u8";
    }

    /** IPv4 de la red WiFi (aunque no sea la red activa), o null si no hay WiFi. */
    private static String wifiIpv4(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            String best = null;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (a.isSiteLocalAddress()) return ip;   // 10/8, 172.16/12, 192.168/16
                    if (best == null) best = ip;
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Comprueba desde el propio móvil que el proxy responde por la IP de la red local.
     * Usa un socket crudo (OkHttp lo bloquearía: la app solo permite texto plano a 127.0.0.1).
     * Devuelve el código HTTP (200 esperado) o -1 si no hubo respuesta.
     */
    public static int probeLan(String lanUrl) {
        try {
            URI u = new URI(lanUrl);
            String host = u.getHost();
            if (host == null) return -1;
            int prt = u.getPort() > 0 ? u.getPort() : 80;
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, prt), 4000);
                s.setSoTimeout(8000);
                OutputStream out = s.getOutputStream();
                out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + prt
                        + "\r\nUser-Agent: SportStreaming-probe\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                String line = in.readLine();
                if (line != null && line.startsWith("HTTP/")) {
                    String[] parts = line.split(" ");
                    if (parts.length >= 2) return Integer.parseInt(parts[1]);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
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
        startForegroundCompat(getString(R.string.proxy_starting));
        registerNetCallback();
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
        if (netCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {
            }
            netCallback = null;
        }
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
            updateProxyNotification();
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
    /** Refresca la notificación con la URL de diagnóstico cuando cambia la red. */
    private void registerNetCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { updateProxyNotification(); }
                @Override public void onLost(Network network) { updateProxyNotification(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    updateProxyNotification();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
    }

    private void updateProxyNotification() {
        if (!ready || instance == null) return;
        String ip = wifiIpv4(this);
        String text = ip == null
                ? getString(R.string.proxy_notif, port)
                : getString(R.string.proxy_notif_diag, "http://" + ip + ":" + port);
        try {
            updateNotification(text);
        } catch (Exception ignored) {
        }
    }

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

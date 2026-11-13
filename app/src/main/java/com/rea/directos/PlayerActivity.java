package com.rea.directos;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.media3.cast.CastPlayer;
import androidx.media3.cast.SessionAvailabilityListener;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

import com.google.android.gms.cast.MediaError;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

/**
 * Reproductor in-app (ExoPlayer) con pantalla completa, boton para abrir el canal en VLC
 * y envio a Chromecast (el televisor lee el stream a traves del proxy, via red local).
 */
public class PlayerActivity extends AppCompatActivity {

    private static final String EXTRA_SID = "sid";
    private static final String EXTRA_LABEL = "label";
    private static final String VLC_PACKAGE = "org.videolan.vlc";
    private static final String STATE_FULLSCREEN = "fullscreen";
    private static final int MAX_RECONNECTS = 8;
    private static final long TARGET_LIVE_OFFSET_MS = 20000;
    private static final long BUFFER_CHECK_INTERVAL_MS = 1000;
    private static final long BUFFER_NO_PROGRESS_MS = 18000;
    private static final long MAX_BUFFERING_MS = 40000;
    private static final long CAST_START_TIMEOUT_MS = 25000;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final Runnable resetReconnects = () -> {
        reconnects = 0;
        gaveUp = false;
    };
    private final Runnable bufferingCheck = this::checkBuffering;
    private Runnable pendingRetry;
    private int reconnects;
    private boolean gaveUp;
    private boolean started;
    private boolean resumeLocalPlayback = true;
    private long bufferingSince = -1;
    private long lastBufferProgress;
    private long bufferedMs;

    public static void start(Context ctx, long sid, String label) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(EXTRA_SID, sid);
        i.putExtra(EXTRA_LABEL, label);
        ctx.startActivity(i);
    }

    private ExoPlayer player;
    private CastPlayer castPlayer;
    private CastContext castContext;
    private CastPlayback castPlayback;
    private PlayerView view;
    private ImageButton fullscreenBtn;
    private View bottomBar;

    private long sid;
    private String label;
    private boolean fullscreen;
    private boolean casting;
    private MediaItem localItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        sid = getIntent().getLongExtra(EXTRA_SID, 0);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null || label.isEmpty()) label = "Directo";
        setTitle(label);

        view = findViewById(R.id.player);
        fullscreenBtn = findViewById(R.id.fullscreen);
        bottomBar = findViewById(R.id.bottom_bar);

        localItem = new MediaItem.Builder()
                .setUri(ProxyService.proxyUrl(sid))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(label).build())
                .build();

        player = buildPlayer();
        player.setMediaItem(localItem);
        view.setPlayer(player);
        view.setKeepScreenOn(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                scheduleReconnect(R.string.player_reconnecting, error.getErrorCodeName());
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    scheduleReconnect(R.string.player_reconnecting_end, "fin del directo");
                }
            }

            @Override public void onEvents(Player source, Player.Events events) {
                if (isLocalPlaybackRequested() && source.getPlaybackState() == Player.STATE_BUFFERING) {
                    if (bufferingSince < 0) {
                        bufferingSince = lastBufferProgress = SystemClock.elapsedRealtime();
                        bufferedMs = source.getTotalBufferedDuration();
                        retryHandler.postDelayed(bufferingCheck, BUFFER_CHECK_INTERVAL_MS);
                    }
                } else {
                    stopBufferingCheck();
                    if (!isLocalPlaybackRequested()) cancelRetry();
                }
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                // Solo damos por buena la conexion tras 30 s seguidos reproduciendo.
                retryHandler.removeCallbacks(resetReconnects);
                if (isPlaying && isLocalPlaybackRequested()) {
                    cancelRetry();
                    retryHandler.postDelayed(resetReconnects, 30000);
                }
            }
        });

        findViewById(R.id.vlc).setOnClickListener(v -> openInVlc());
        fullscreenBtn.setOnClickListener(v -> setFullscreen(!fullscreen));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreen) {
                    setFullscreen(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        initCast();

        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_FULLSCREEN, false)) {
            setFullscreen(true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FULLSCREEN, fullscreen);
    }

    // ------------------------------------------------------------------
    // Reproductor y reconexion
    // ------------------------------------------------------------------
    private ExoPlayer buildPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(2000)
                .setReadTimeoutMs(24000)
                .setUserAgent("SportStreaming");
        DefaultMediaSourceFactory media = new DefaultMediaSourceFactory(http)
                .setLiveTargetOffsetMs(TARGET_LIVE_OFFSET_MS)
                .setLiveMinSpeed(0.97f)
                .setLiveMaxSpeed(1.02f)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3));
        return new ExoPlayer.Builder(this)
                .setMediaSourceFactory(media)
                .setLivePlaybackSpeedControl(new DefaultLivePlaybackSpeedControl.Builder()
                        .setTargetLiveOffsetIncrementOnRebufferMs(2000)
                        .build())
                .build();
    }

    private boolean isLocalPlaybackRequested() {
        return started && !casting && player != null && player.getPlayWhenReady()
                && !isFinishing() && !isDestroyed();
    }

    private void checkBuffering() {
        if (!isLocalPlaybackRequested() || player.getPlaybackState() != Player.STATE_BUFFERING) {
            stopBufferingCheck();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long currentBuffer = player.getTotalBufferedDuration();
        if (currentBuffer - bufferedMs >= 250) {
            bufferedMs = currentBuffer;
            lastBufferProgress = now;
        }
        if (now - lastBufferProgress >= BUFFER_NO_PROGRESS_MS || now - bufferingSince >= MAX_BUFFERING_MS) {
            scheduleReconnect(R.string.player_reconnecting, "búfer detenido (" + (now - bufferingSince) + " ms)");
            return;
        }
        retryHandler.postDelayed(bufferingCheck, BUFFER_CHECK_INTERVAL_MS);
    }

    private void stopBufferingCheck() {
        retryHandler.removeCallbacks(bufferingCheck);
        bufferingSince = -1;
    }

    /** Un fallo de red/CDN no debe dejar el partido parado: reintenta con espera creciente. */
    private void scheduleReconnect(int messageRes, String reason) {
        if (pendingRetry != null || !isLocalPlaybackRequested()) return;
        if (reconnects >= MAX_RECONNECTS) {
            if (!gaveUp) {
                gaveUp = true;
                Toast.makeText(this, R.string.player_give_up, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (reconnects == 0) {
            Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
        }
        pendingRetry = () -> {
            pendingRetry = null;
            if (!isLocalPlaybackRequested() || player.isPlaying()) return;
            reconnects++;
            RequestLog.add("móvil", "Reconexión /go/" + sid, 0, reason + " · intento " + reconnects);
            stopBufferingCheck();
            player.setMediaItem(localItem);
            player.prepare();
        };
        retryHandler.postDelayed(pendingRetry, Math.min(8000L, 250L << reconnects));
    }

    // ------------------------------------------------------------------
    // Pantalla completa
    // ------------------------------------------------------------------
    private void setFullscreen(boolean on) {
        fullscreen = on;
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (getSupportActionBar() != null) {
            if (on) getSupportActionBar().hide();
            else getSupportActionBar().show();
        }
        bottomBar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            c.show(WindowInsetsCompat.Type.systemBars());
        }
        fullscreenBtn.setImageResource(on ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    // ------------------------------------------------------------------
    // Chromecast
    // ------------------------------------------------------------------
    private void initCast() {
        MediaRouteButton button = findViewById(R.id.cast);
        try {
            castContext = CastContext.getSharedInstance(getApplicationContext());
            CastButtonFactory.setUpMediaRouteButton(this, button);
            castPlayer = new CastPlayer(castContext, new LiveCastMedia());
            castPlayer.setSessionAvailabilityListener(new SessionAvailabilityListener() {
                @Override public void onCastSessionAvailable() { switchToCast(); }
                @Override public void onCastSessionUnavailable() { switchToLocal(); }
            });
            if (castPlayer.isCastSessionAvailable()) switchToCast();
        } catch (RuntimeException e) {
            logCast("No se pudo configurar Cast: " + e.getClass().getSimpleName());
            button.setVisibility(View.GONE);
        }
    }

    private void switchToCast() {
        CastSession session = castContext.getSessionManager().getCurrentCastSession();
        if (session == null || !session.isConnected() || isFinishing() || isDestroyed()) return;
        RemoteMediaClient remote = session.getRemoteMediaClient();
        if (remote == null || castPlayback != null) return;
        String lanUrl = ProxyService.lanUrl(this, sid);
        if (lanUrl == null) {
            Toast.makeText(this, R.string.cast_need_wifi, Toast.LENGTH_LONG).show();
            castContext.getSessionManager().endCurrentSession(false);
            return;
        }
        castPlayback = new CastPlayback(remote, lanUrl);
        castPlayback.start();
    }

    private void showCastPlayer() {
        casting = true;
        cancelRetry();
        stopBufferingCheck();
        player.pause();
        view.setPlayer(castPlayer);
    }

    private void logCast(String detail) {
        RequestLog.add("Cast", "Chromecast /go/" + sid, 0, detail);
    }

    private void switchToLocal() {
        if (castPlayback != null) {
            castPlayback.close();
            castPlayback = null;
        }
        if (player == null || !casting) return;
        casting = false;
        reconnects = 0;
        gaveUp = false;
        resumeLocalPlayback = true;
        player.setMediaItem(localItem);
        if (started) {
            player.prepare();
            player.play();
        }
        view.setPlayer(player);
    }

    private final class CastPlayback extends RemoteMediaClient.Callback {
        private final RemoteMediaClient remote;
        private final String url;
        private final Runnable timeout = () -> fail(getString(R.string.cast_start_timeout));
        private boolean loadAccepted;

        CastPlayback(RemoteMediaClient remote, String url) {
            this.remote = remote;
            this.url = url;
        }

        void start() {
            remote.registerCallback(this);
            if (isPlayingChannel()) {
                showCastPlayer();
                return;
            }
            logCast("Enviando HLS en directo a la TV");
            Toast.makeText(PlayerActivity.this, R.string.cast_sending, Toast.LENGTH_SHORT).show();
            retryHandler.postDelayed(timeout, CAST_START_TIMEOUT_MS);
            remote.load(LiveCastMedia.loadRequest(url, label)).setResultCallback(result -> {
                if (castPlayback != this) return;
                MediaError error = result.getMediaError();
                if (error != null) {
                    onMediaError(error);
                    return;
                }
                if (!result.getStatus().isSuccess()) {
                    fail("Cast " + result.getStatus().getStatusCode());
                    return;
                }
                loadAccepted = true;
                onStatusUpdated();
            });
        }

        private boolean isPlayingChannel() {
            MediaInfo media = remote.getMediaInfo();
            return remote.isPlaying() && media != null
                    && (url.equals(media.getContentUrl()) || url.equals(media.getContentId()));
        }

        @Override public void onStatusUpdated() {
            if (castPlayback != this) return;
            if (!casting && loadAccepted && isPlayingChannel()) {
                retryHandler.removeCallbacks(timeout);
                logCast("La TV está reproduciendo el directo");
                showCastPlayer();
            } else if (casting && remote.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE
                    && remote.getIdleReason() == MediaStatus.IDLE_REASON_ERROR) {
                fail("reproducción interrumpida");
            }
        }

        @Override public void onMediaError(MediaError error) {
            Integer code = error.getDetailedErrorCode();
            fail(error.getType() + (code == null ? "" : " " + code));
        }

        private void fail(String reason) {
            if (castPlayback != this) return;
            logCast(reason);
            Toast.makeText(PlayerActivity.this, getString(R.string.cast_playback_error, reason),
                    Toast.LENGTH_LONG).show();
            switchToLocal();
            castContext.getSessionManager().endCurrentSession(true);
        }

        void close() {
            retryHandler.removeCallbacks(timeout);
            remote.unregisterCallback(this);
        }
    }

    // ------------------------------------------------------------------
    // VLC
    // ------------------------------------------------------------------
    private void openInVlc() {
        String url = ProxyService.proxyUrl(sid);
        Intent vlc = new Intent(Intent.ACTION_VIEW);
        vlc.setDataAndType(Uri.parse(url), "video/*");
        vlc.setPackage(VLC_PACKAGE);
        try {
            startActivity(vlc);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Intent any = new Intent(Intent.ACTION_VIEW);
        any.setDataAndType(Uri.parse(url), "video/*");
        try {
            startActivity(Intent.createChooser(any, getString(R.string.open_vlc)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_vlc, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (casting || !resumeLocalPlayback) return;
        if (gaveUp) resetReconnects.run();
        player.play();
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            player.prepare();
        } else if (player.getPlaybackState() == Player.STATE_ENDED) {
            scheduleReconnect(R.string.player_reconnecting_end, "fin del directo");
        }
    }

    @Override
    protected void onStop() {
        started = false;
        if (!casting) resumeLocalPlayback = player.getPlayWhenReady();
        cancelRetry();
        stopBufferingCheck();
        player.pause();
        super.onStop();
    }

    private void cancelRetry() {
        if (pendingRetry != null) {
            retryHandler.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
        retryHandler.removeCallbacks(resetReconnects);
    }

    @Override
    protected void onDestroy() {
        if (castPlayback != null) {
            castPlayback.close();
            castPlayback = null;
        }
        cancelRetry();
        retryHandler.removeCallbacksAndMessages(null);
        view.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (castPlayer != null) {
            castPlayer.setSessionAvailabilityListener(null);
            castPlayer.release();
            castPlayer = null;
        }
        super.onDestroy();
    }
}

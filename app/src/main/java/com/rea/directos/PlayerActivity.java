package com.rea.directos;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.cast.CastPlayer;
import androidx.media3.cast.SessionAvailabilityListener;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.android.gms.cast.framework.CastContext;

/**
 * Reproductor in-app (ExoPlayer) con pantalla completa, boton para abrir el canal en VLC
 * y envio a Chromecast (el televisor lee el stream a traves del proxy, via red local).
 */
public class PlayerActivity extends AppCompatActivity {

    private static final String EXTRA_SID = "sid";
    private static final String EXTRA_LABEL = "label";
    private static final String VLC_PACKAGE = "org.videolan.vlc";
    private static final String STATE_FULLSCREEN = "fullscreen";

    public static void start(Context ctx, long sid, String label) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(EXTRA_SID, sid);
        i.putExtra(EXTRA_LABEL, label);
        ctx.startActivity(i);
    }

    private ExoPlayer player;
    private CastPlayer castPlayer;
    private CastContext castContext;
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

        player = new ExoPlayer.Builder(this).build();
        player.setMediaItem(localItem);
        player.prepare();
        player.setPlayWhenReady(true);
        view.setPlayer(player);

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
            view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            c.show(WindowInsetsCompat.Type.systemBars());
        }
        fullscreenBtn.setImageResource(on ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    // ------------------------------------------------------------------
    // Chromecast
    // ------------------------------------------------------------------
    private void initCast() {
        try {
            castContext = CastContext.getSharedInstance(getApplicationContext());
            castPlayer = new CastPlayer(castContext);
            castPlayer.setSessionAvailabilityListener(new SessionAvailabilityListener() {
                @Override public void onCastSessionAvailable() { switchToCast(); }
                @Override public void onCastSessionUnavailable() { switchToLocal(); }
            });
            castPlayer.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    if (!casting) return;
                    Toast.makeText(PlayerActivity.this,
                            getString(R.string.cast_playback_error, error.getErrorCodeName()),
                            Toast.LENGTH_LONG).show();
                }
            });
            // Si ya hay una sesion de Cast activa (p. ej. al reabrir el reproductor), envia el canal.
            if (castContext.getSessionManager().getCurrentCastSession() != null) {
                switchToCast();
            }
        } catch (Throwable t) {
            View b = findViewById(R.id.cast);
            if (b != null) b.setVisibility(View.GONE);
        }
    }

    /** Sesion de Cast conectada: comprueba el proxy en la red local y envia el canal al televisor. */
    private void switchToCast() {
        if (castPlayer == null) return;
        final String lanUrl = ProxyService.lanUrl(this, sid);
        if (lanUrl == null) {
            Toast.makeText(this, R.string.cast_need_wifi, Toast.LENGTH_LONG).show();
            endCastSession();
            return;
        }
        new Thread(() -> {
            final int code = ProxyService.probeLan(lanUrl);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (code == 200) {
                    startCastPlayback(lanUrl);
                } else {
                    Toast.makeText(PlayerActivity.this,
                            getString(R.string.cast_probe_failed, lanUrl,
                                    code < 0 ? getString(R.string.no_response) : String.valueOf(code)),
                            Toast.LENGTH_LONG).show();
                    endCastSession();
                }
            });
        }, "cast-probe").start();
    }

    /** Envia la playlist al receptor (la TV la lee del proxy via red local). */
    private void startCastPlayback(String lanUrl) {
        if (castPlayer == null || player == null) return;
        player.pause();
        MediaItem item = new MediaItem.Builder()
                .setUri(lanUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(label).build())
                .build();
        casting = true;
        castPlayer.setMediaItem(item);
        castPlayer.prepare();
        castPlayer.play();
        view.setPlayer(castPlayer);
        Toast.makeText(this, R.string.cast_sending, Toast.LENGTH_SHORT).show();
    }

    private void endCastSession() {
        try {
            if (castContext != null) castContext.getSessionManager().endCurrentSession(false);
        } catch (Exception ignored) {
        }
    }

    /** Sesion de Cast cerrada: vuelve al reproductor local. */
    private void switchToLocal() {
        if (player == null || !casting) return;
        casting = false;
        player.setMediaItem(localItem);
        player.prepare();
        player.play();
        view.setPlayer(player);
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
    protected void onStop() {
        if (player != null) player.pause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (castPlayer != null) {
            castPlayer.release();
            castPlayer = null;
        }
        super.onDestroy();
    }
}

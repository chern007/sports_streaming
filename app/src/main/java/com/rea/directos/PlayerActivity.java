package com.rea.directos;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/** Reproductor in-app (ExoPlayer) con boton para abrir el mismo canal en VLC. */
public class PlayerActivity extends AppCompatActivity {

    private static final String EXTRA_SID = "sid";
    private static final String EXTRA_LABEL = "label";
    private static final String VLC_PACKAGE = "org.videolan.vlc";

    public static void start(Context ctx, long sid, String label) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(EXTRA_SID, sid);
        i.putExtra(EXTRA_LABEL, label);
        ctx.startActivity(i);
    }

    private ExoPlayer player;
    private long sid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        sid = getIntent().getLongExtra(EXTRA_SID, 0);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        setTitle(label == null || label.isEmpty() ? "Directo" : label);

        PlayerView view = findViewById(R.id.player);
        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);

        String url = ProxyService.proxyUrl(sid);
        player.setMediaItem(new MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build());
        player.prepare();
        player.setPlayWhenReady(true);

        findViewById(R.id.vlc).setOnClickListener(v -> openInVlc());
    }

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
        super.onDestroy();
    }
}

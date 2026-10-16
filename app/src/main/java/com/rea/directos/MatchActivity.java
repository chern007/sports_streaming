package com.rea.directos;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Canales de un partido. Al pulsar un canal se resuelve y se abre el reproductor. */
public class MatchActivity extends AppCompatActivity {

    private static final String EXTRA_MATCH_ID = "match_id";
    private static final String EXTRA_NAME = "name";

    public static void start(Context ctx, long matchId, String name) {
        Intent i = new Intent(ctx, MatchActivity.class);
        i.putExtra(EXTRA_MATCH_ID, matchId);
        i.putExtra(EXTRA_NAME, name);
        ctx.startActivity(i);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long matchId;
    private TextView status;
    private RowsAdapter adapter;
    private boolean resolving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_match);

        matchId = getIntent().getLongExtra(EXTRA_MATCH_ID, 0);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        setTitle(name == null || name.isEmpty() ? "Partido " + matchId : name);

        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowsAdapter(pos -> {
            StreamInfo ch = (StreamInfo) adapter.get(pos).tag;
            resolveAndPlay(ch);
        });
        list.setAdapter(adapter);

        ProxyService.ensureStarted(this);
        ProxyService.whenReady(this::loadChannels,
                err -> ui.post(() -> status.setText(getString(R.string.error_prefix) + ": " + err)));
    }

    private void loadChannels() {
        status.setText(R.string.loading_channels);
        io.execute(() -> {
            try {
                List<StreamInfo> channels = RbApi.matchDetailStreams(ProxyService.dataApi, matchId);
                ui.post(() -> {
                    List<RowsAdapter.Row> rows = new ArrayList<>();
                    for (StreamInfo c : channels) {
                        if (c.sid <= 0) continue;
                        String label = c.name == null || c.name.isEmpty() ? "Canal " + c.sid : c.name;
                        rows.add(new RowsAdapter.Row(label,
                                "streamId=" + c.sid + " · siteType=" + c.siteType, c));
                    }
                    adapter.set(rows);
                    status.setText(rows.isEmpty() ? getString(R.string.no_channels)
                            : rows.size() + " canal(es) — pulsa para ver");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText(getString(R.string.error_prefix) + ": " + e.getMessage()));
            }
        });
    }

    private void resolveAndPlay(StreamInfo ch) {
        if (resolving) return;
        if (!ProxyService.isReady()) {
            ProxyService.ensureStarted(this);
            ProxyService.whenReady(() -> resolveAndPlay(ch),
                    err -> ui.post(() -> Toast.makeText(this, getString(R.string.error_prefix) + ": " + err,
                            Toast.LENGTH_LONG).show()));
            return;
        }
        resolving = true;
        status.setText(R.string.resolving_channel);
        io.execute(() -> {
            String error = null;
            StreamInfo info = null;
            try {
                info = RbApi.streamDetail(ProxyService.dataApi, matchId, ch.sid, ch.siteType);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final StreamInfo result = info;
            final String err = error;
            ui.post(() -> {
                resolving = false;
                status.setText(null);
                if (result == null || result.cdn == null) {
                    Toast.makeText(this, err == null ? getString(R.string.channel_error) : err,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                ProxyService.addStream(result);
                String label = result.name == null || result.name.isEmpty()
                        ? "Canal " + result.sid : result.name;
                PlayerActivity.start(this, result.sid, label);
            });
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}

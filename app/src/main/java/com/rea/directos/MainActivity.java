package com.rea.directos;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lista de partidos con directo (equivalente a `rb_live.py list`). */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private Button refresh;
    private RowsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        status = findViewById(R.id.status);
        refresh = findViewById(R.id.refresh);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowsAdapter(pos -> {
            RbApi.MatchInfo m = (RbApi.MatchInfo) adapter.get(pos).tag;
            MatchActivity.start(this, m.id, m.name);
        });
        list.setAdapter(adapter);

        refresh.setOnClickListener(v -> load());
        requestNotifPermission();
        ProxyService.ensureStarted(this);
        load();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void load() {
        status.setText(R.string.loading_matches);
        refresh.setEnabled(false);
        adapter.clear();
        ProxyService.ensureStarted(this);
        ProxyService.whenReady(
                () -> io.execute(() -> {
                    try {
                        RbApi.LiveResult lr = RbApi.fetchLiveMatches(ProxyService.dataApi);
                        List<RbApi.MatchInfo> ms = lr.liveMatches();
                        ui.post(() -> {
                            List<RowsAdapter.Row> rows = new ArrayList<>();
                            for (RbApi.MatchInfo m : ms) {
                                rows.add(new RowsAdapter.Row(
                                        m.name.isEmpty() ? ("Partido " + m.id) : m.name,
                                        "matchId=" + m.id + " · " + m.channels + " canal(es)",
                                        m));
                            }
                            adapter.set(rows);
                            status.setText(ms.isEmpty()
                                    ? getString(R.string.no_streams)
                                    : ms.size() + " partido(s) con directo");
                            refresh.setEnabled(true);
                        });
                    } catch (Exception e) {
                        ui.post(() -> {
                            status.setText(getString(R.string.error_prefix) + ": " + e.getMessage());
                            refresh.setEnabled(true);
                        });
                    }
                }),
                err -> ui.post(() -> {
                    status.setText(getString(R.string.error_prefix) + ": " + err);
                    refresh.setEnabled(true);
                }));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}

package com.rea.directos;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lista de partidos con directo + filtro por nombre (equivalente a `rb_live.py list`). */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private Button refresh;
    private EditText search;
    private RowsAdapter adapter;

    private final List<RowsAdapter.Row> allRows = new ArrayList<>();
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        status = findViewById(R.id.status);
        refresh = findViewById(R.id.refresh);
        search = findViewById(R.id.search);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowsAdapter(pos -> {
            RbApi.MatchInfo m = (RbApi.MatchInfo) adapter.get(pos).tag;
            MatchActivity.start(this, m.id, m.name);
        });
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });

        refresh.setOnClickListener(v -> load());
        requestNotifPermission();
        ProxyService.ensureStarted(this);
        load();
    }

    /** Aplica el texto del buscador como filtro sobre los partidos cargados. */
    private void applyFilter() {
        String key = Text.fold(search.getText().toString());
        List<RowsAdapter.Row> visible = new ArrayList<>();
        for (RowsAdapter.Row r : allRows) {
            if (key.isEmpty() || Text.fold(r.title).contains(key)) visible.add(r);
        }
        adapter.set(visible);
        if (!loaded) return;
        if (allRows.isEmpty()) {
            status.setText(R.string.no_streams);
        } else if (key.isEmpty()) {
            status.setText(getString(R.string.matches_count, allRows.size()));
        } else {
            status.setText(getString(R.string.matches_filtered, visible.size(), allRows.size()));
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void load() {
        loaded = false;
        allRows.clear();
        applyFilter();
        status.setText(R.string.loading_matches);
        refresh.setEnabled(false);
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
                            allRows.clear();
                            allRows.addAll(rows);
                            loaded = true;
                            applyFilter();
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

package com.rea.directos;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Adaptador simple (titulo + subtitulo) para partidos y canales. */
public class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.VH> {

    public interface OnRowClick {
        void onClick(int position);
    }

    public static class Row {
        public final String title;
        public final String subtitle;
        public final Object tag;

        public Row(String title, String subtitle, Object tag) {
            this.title = title;
            this.subtitle = subtitle;
            this.tag = tag;
        }
    }

    public static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnRowClick click;

    public RowsAdapter(OnRowClick click) {
        this.click = click;
    }

    public void set(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    public void clear() {
        rows.clear();
        notifyDataSetChanged();
    }

    public Row get(int pos) {
        return rows.get(pos);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_simple, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Row r = rows.get(position);
        holder.title.setText(r.title);
        if (r.subtitle == null || r.subtitle.isEmpty()) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.subtitle.setText(r.subtitle);
        }
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) click.onClick(pos);
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }
}

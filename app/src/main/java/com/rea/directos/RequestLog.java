package com.rea.directos;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Registro circular de las últimas peticiones atendidas por el proxy (página de diagnóstico). */
public final class RequestLog {

    public static final int MAX = 60;

    public static final class Entry {
        public final String time;
        public final String remote;
        public final String request;
        public final int status;
        public final String note;

        Entry(String time, String remote, String request, int status, String note) {
            this.time = time;
            this.remote = remote;
            this.request = request;
            this.status = status;
            this.note = note;
        }
    }

    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private RequestLog() {}

    public static synchronized void add(String remote, String request, int status, String note) {
        ENTRIES.addFirst(new Entry(FMT.format(new Date()), clip(remote, 40), clip(request, 70),
                status, clip(note, 70)));
        while (ENTRIES.size() > MAX) {
            ENTRIES.removeLast();
        }
    }

    /** Copia de las entradas, la más reciente primero. */
    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Escapa texto para meterlo en HTML. */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default:
                    b.append(c < 32 ? ' ' : c);
            }
        }
        return b.toString();
    }
}

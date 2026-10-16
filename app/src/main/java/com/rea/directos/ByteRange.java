package com.rea.directos;

/** Cabecera HTTP {@code Range: bytes=a-b} (solo un rango; se ignoran los múltiples). */
public final class ByteRange {

    public final long start;   // inclusive
    public final long end;     // inclusive

    private ByteRange(long start, long end) {
        this.start = start;
        this.end = end;
    }

    /** Devuelve el rango aplicable o null si no hay rango válido (responder 200 completo). */
    public static ByteRange parse(String header, long length) {
        if (header == null || length <= 0) return null;
        String v = header.trim();
        if (!v.startsWith("bytes=")) return null;
        String spec = v.substring(6).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) return null;
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            long s;
            long e;
            if (a.isEmpty()) {
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                s = Math.max(0, length - n);
                e = length - 1;
            } else {
                s = Long.parseLong(a);
                e = b.isEmpty() ? length - 1 : Long.parseLong(b);
            }
            if (s < 0 || s >= length) return null;
            if (e >= length) e = length - 1;
            if (e < s) return null;
            return new ByteRange(s, e);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public long size() {
        return end - start + 1;
    }

    public String contentRange(long total) {
        return "bytes " + start + "-" + end + "/" + total;
    }
}

package com.rea.directos;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

/**
 * Proxy HLS local (equivalente al de rb_live.py): expone
 *   /go/&lt;sid&gt;/index.m3u8  -> playlist con segmentos reescritos al proxy
 *   /go/&lt;sid&gt;/seg?u=...      -> segmento real resuelto (CSL) y descargado del CDN
 */
public class ProxyServer extends NanoHTTPD {

    public interface Provider {
        StreamInfo stream(long sid);
        String salt();
        String referer();
    }

    private static final Pattern PLAYLIST = Pattern.compile("^/go/(\\d+)/index\\.m3u8$");
    private static final Pattern SEGMENT = Pattern.compile("^/go/(\\d+)/seg$");

    private final Provider provider;

    public ProxyServer(int port, Provider provider) {
        super(port);
        this.provider = provider;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (session.getMethod() == Method.OPTIONS) {
                return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""));
            }
            String uri = session.getUri();
            if (uri == null || uri.isEmpty() || uri.equals("/")) {
                return cors(text(Response.Status.OK, "SportStreaming proxy activo"));
            }
            String host = session.getHeaders().get("host");
            Matcher m = PLAYLIST.matcher(uri);
            if (m.matches()) return cors(playlist(Long.parseLong(m.group(1)), host));
            m = SEGMENT.matcher(uri);
            if (m.matches()) return cors(segment(Long.parseLong(m.group(1)), param(session, "u")));
            return cors(text(Response.Status.NOT_FOUND, "not found"));
        } catch (Exception e) {
            return cors(text(Response.Status.INTERNAL_ERROR, "proxy error: " + e));
        }
    }

    /** Cabeceras CORS (el receptor de Chromecast tambien lee este HLS). */
    private static Response cors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "*");
        return r;
    }

    private static String param(IHTTPSession session, String name) {
        String qs = session.getQueryParameterString();
        if (qs == null) return null;
        for (String part : qs.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (!k.equals(name)) continue;
            String v = eq < 0 ? "" : part.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(v.replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                return v;
            }
        }
        return null;
    }

    /** 502 Bad Gateway (el enum de NanoHTTPD 2.3.1 no lo incluye). */
    private static final Response.IStatus BAD_GATEWAY = new Response.IStatus() {
        @Override public String getDescription() { return "502 Bad Gateway"; }
        @Override public int getRequestStatus() { return 502; }
    };

    private static Response text(Response.IStatus status, String msg) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", msg);
    }

    private Response playlist(long sid, String host) throws Exception {
        StreamInfo info = provider.stream(sid);
        if (info == null || info.cdn == null) {
            return text(Response.Status.NOT_FOUND, "stream desconocido (resuelve el canal en la app)");
        }
        String base = "http://" + (host != null && !host.isEmpty()
                ? host
                : "127.0.0.1:" + getListeningPort());
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", RbApi.UA);
        h.put("accept", "*/*");
        Http.Resp r = Http.get(info.cdn, h);
        if (r.status != 200) {
            return text(BAD_GATEWAY, "playlist CDN status=" + r.status);
        }
        String body = new String(r.body, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String ln : body.split("\\r?\\n", -1)) {
            if (ln.isEmpty() || ln.startsWith("#")) {
                out.append(ln).append('\n');
                continue;
            }
            String segUrl;
            try {
                segUrl = new URI(info.cdn).resolve(ln.trim()).toString();
            } catch (Exception e) {
                segUrl = ln.trim();
            }
            String u = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(segUrl.getBytes(StandardCharsets.UTF_8));
            out.append(base).append("/go/").append(sid).append("/seg?u=").append(u).append('\n');
        }
        return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", out.toString());
    }

    private Response segment(long sid, String u) throws Exception {
        StreamInfo info = provider.stream(sid);
        if (info == null) {
            return text(Response.Status.NOT_FOUND, "stream desconocido (resuelve el canal en la app)");
        }
        if (u == null || u.isEmpty()) {
            return text(Response.Status.BAD_REQUEST, "falta ?u=");
        }
        String segFull;
        try {
            int need = (4 - u.length() % 4) % 4;
            StringBuilder ub = new StringBuilder(u);
            for (int i = 0; i < need; i++) ub.append('=');
            segFull = new String(Base64.getUrlDecoder().decode(ub.toString()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return text(Response.Status.BAD_REQUEST, "u invalido");
        }
        String[] res = RbApi.resolveSegmentUrl(segFull, provider.salt());
        if (res == null) {
            return text(BAD_GATEWAY, "segmento sin CSL");
        }
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", RbApi.UA);
        String ref = provider.referer();
        if (ref != null && !ref.isEmpty()) {
            h.put("referer", ref.endsWith("/") ? ref : ref + "/");
            h.put("origin", ref.endsWith("/") ? ref.substring(0, ref.length() - 1) : ref);
        }
        h.put("accept", "*/*");
        Http.Resp r = Http.get(res[0], h);
        if (r.status != 200 || r.body.length == 0) {
            return text(BAD_GATEWAY, "CDN " + r.status + " len=" + r.body.length);
        }
        if ((r.body[0] & 0xFF) == 0x47) {
            return newFixedLengthResponse(Response.Status.OK, "video/mp2t",
                    new ByteArrayInputStream(r.body), r.body.length);
        }
        String ct = r.contentType == null ? "application/octet-stream" : r.contentType;
        if (ct.toLowerCase(Locale.ROOT).contains("text/html")) {
            return text(BAD_GATEWAY, "contenido inesperado del CDN");
        }
        return newFixedLengthResponse(Response.Status.OK, ct,
                new ByteArrayInputStream(r.body), r.body.length);
    }
}

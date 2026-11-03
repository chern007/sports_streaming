package com.rea.directos;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

/**
 * Proxy HLS local (equivalente al de rb_live.py): expone
 *   /go/&lt;sid&gt;/index.m3u8  -> playlist con segmentos reescritos al proxy
 *   /go/&lt;sid&gt;/seg?u=...      -> segmento real resuelto (CSL) y descargado del CDN
 *   / o /diag                 -> página de diagnóstico (registro de peticiones)
 */
public class ProxyServer extends NanoHTTPD {

    public interface Provider {
        StreamInfo stream(long sid);
        /** Vuelve a pedir stream/detail y actualiza la URL CDN (por si el token caduco). */
        StreamInfo refreshStream(long sid);
        /** Vuelve a leer salt/referer de /api/common/params. */
        void refreshParams();
        String salt();
        String referer();
        List<StreamInfo> streamList();
        String phoneIp();
    }

    private static final Pattern PLAYLIST = Pattern.compile("^/go/(\\d+)/index\\.m3u8$");
    private static final Pattern SEGMENT = Pattern.compile("^/go/(\\d+)/seg$");
    private static final Pattern KEY_URI = Pattern.compile("URI=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final int MAX_SEG_ATTEMPTS = 3;

    private final Provider provider;
    /** Ultimo host/tipo que fallo (igual que la app original: se prueba otro antes). */
    private volatile String lastFailedType;
    /** Evita pedir stream/detail o params en cada reintento del reproductor. */
    private final Map<Long, Long> lastStreamRefresh = new ConcurrentHashMap<>();
    private volatile long lastParamsRefresh;

    public ProxyServer(int port, Provider provider) {
        super(port);
        this.provider = provider;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod() == null ? "?" : session.getMethod().name();
        String uri = session.getUri() == null ? "/" : session.getUri();
        String path = logPath(uri, session);
        String agent = shortAgent(session.getHeaders().get("user-agent"));
        try {
            Response r = handle(session, method, uri);
            RequestLog.add(session.getRemoteIpAddress(), method + " " + path, status(r), agent);
            return cors(r);
        } catch (Exception e) {
            RequestLog.add(session.getRemoteIpAddress(), method + " " + path, 500, "error: " + e);
            return cors(text(Response.Status.INTERNAL_ERROR, "proxy error: " + e));
        }
    }

    private Response handle(IHTTPSession session, String method, String uri) throws Exception {
        if ("OPTIONS".equals(method)) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
        }
        // El receptor puede sondear con HEAD antes de descargar; NanoHTTPD 2.3.1
        // adjunta el cuerpo entero en los HEAD y corrompe la conexión: aquí no.
        if ("HEAD".equals(method)) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "");
        }
        if (uri.isEmpty() || "/".equals(uri) || "/diag".equals(uri)) {
            return diagnostics(session);
        }
        Matcher m = PLAYLIST.matcher(uri);
        if (m.matches()) return playlist(Long.parseLong(m.group(1)), session);
        m = SEGMENT.matcher(uri);
        if (m.matches()) return segment(Long.parseLong(m.group(1)), session);
        return text(Response.Status.NOT_FOUND, "not found");
    }

    /** Cabeceras CORS (el receptor de Chromecast tambien lee este HLS desde otro origen). */
    private static Response cors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "*");
        r.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range");
        r.addHeader("Access-Control-Allow-Private-Network", "true");
        return r;
    }

    private static int status(Response r) {
        Response.IStatus s = r.getStatus();
        return s == null ? 0 : s.getRequestStatus();
    }

    /** Ruta para el registro de diagnóstico (recorta el base64 de los segmentos). */
    private static String logPath(String uri, IHTTPSession session) {
        String qs = session.getQueryParameterString();
        if (qs == null || qs.isEmpty()) return uri;
        return uri + "?" + RequestLog.clip(qs, 26);
    }

    /** Cliente HTTP en corto, para distinguir televisor/móvil en el registro. */
    private static String shortAgent(String ua) {
        if (ua == null || ua.isEmpty()) return "-";
        String low = ua.toLowerCase(Locale.ROOT);
        if (low.contains("crkey")) return "Chromecast (CrKey)";
        if (low.contains("exoplayer")) return "ExoPlayer (móvil)";
        if (low.contains("vlc")) return "VLC";
        if (low.contains("dalvik")) return "Android";
        return RequestLog.clip(ua, 26);
    }

    /** Base http://host que ve el cliente, para reescribir la playlist. */
    private String base(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        if (host != null && !host.isEmpty() && !host.startsWith("[")) {
            return "http://" + host;
        }
        String ip = provider.phoneIp();
        if (ip != null && !ip.isEmpty()) {
            return "http://" + ip + ":" + getListeningPort();
        }
        return "http://127.0.0.1:" + getListeningPort();
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

    // ------------------------------------------------------------------
    // Playlist y segmentos
    // ------------------------------------------------------------------
    private Response playlist(long sid, IHTTPSession session) throws Exception {
        StreamInfo info = provider.stream(sid);
        if (info == null || info.cdn == null) {
            return text(Response.Status.NOT_FOUND, "stream desconocido (resuelve el canal en la app)");
        }
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", RbApi.UA);
        h.put("accept", "*/*");
        Http.Resp r = Http.get(info.cdn, h);
        if (r.status != 200 || !looksLikePlaylist(r.body)) {
            // La URL del CDN lleva token y caduca: se vuelve a resolver una vez.
            StreamInfo fresh = refreshStreamThrottled(sid, info);
            if (fresh != null && fresh.cdn != null && !fresh.cdn.equals(info.cdn)) {
                info = fresh;
                r = Http.get(info.cdn, h);
            }
        }
        if (r.status != 200) {
            return text(BAD_GATEWAY, "playlist CDN status=" + r.status);
        }
        if (!looksLikePlaylist(r.body)) {
            return text(BAD_GATEWAY, "playlist CDN no valida");
        }
        String body = new String(r.body, StandardCharsets.UTF_8);
        byte[] data = rewritePlaylist(body, info.cdn, sid, base(session)).getBytes(StandardCharsets.UTF_8);
        Response resp = ranged(session, data, "application/vnd.apple.mpegurl");
        resp.addHeader("Cache-Control", "no-cache, no-store");
        return resp;
    }

    private static boolean looksLikePlaylist(byte[] body) {
        if (body == null || body.length == 0) return false;
        String s = new String(body, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        return s.startsWith("#EXTM3U");
    }

    private Response segment(long sid, IHTTPSession session) throws Exception {
        StreamInfo info = provider.stream(sid);
        if (info == null) {
            return text(Response.Status.NOT_FOUND, "stream desconocido (resuelve el canal en la app)");
        }
        String u = param(session, "u");
        if (u == null || u.isEmpty()) {
            return text(Response.Status.BAD_REQUEST, "falta ?u=");
        }
        String segFull = decodeU(u);
        if (segFull == null) {
            return text(Response.Status.BAD_REQUEST, "u invalido");
        }
        List<String[]> candidates = RbApi.resolveSegmentUrls(segFull, provider.salt());
        if (candidates.isEmpty()) {
            return text(BAD_GATEWAY, "segmento sin CSL");
        }
        candidates = preferOtherThanLastFailed(candidates);

        boolean refreshedParams = false;
        String lastStatus = "?";
        int attempts = Math.min(MAX_SEG_ATTEMPTS, Math.max(2, candidates.size()));
        for (int i = 0; i < attempts; i++) {
            // Se recalcula por si refreshParams() ha cambiado el salt.
            candidates = preferOtherThanLastFailed(
                    RbApi.resolveSegmentUrls(segFull, provider.salt()));
            if (candidates.isEmpty()) break;
            String[] cand = candidates.get(i % candidates.size());
            Http.Stream stream = null;
            try {
                stream = Http.open(cand[0], segmentHeaders());
                lastStatus = String.valueOf(stream.status);
                if (stream.status != 200 && stream.status != 206) {
                    int code = stream.status;
                    lastFailedType = cand[1];
                    closeQuietly(stream);
                    if ((code == 401 || code == 403) && !refreshedParams) {
                        // El salt o el referer pueden haber rotado.
                        refreshParamsThrottled();
                        refreshedParams = true;
                    }
                    continue;
                }
                BufferedInputStream in = new BufferedInputStream(stream.body, 8192);
                in.mark(1);
                int firstByte = in.read();
                in.reset();
                if (firstByte < 0) {
                    lastFailedType = cand[1];
                    closeQuietly(stream);
                    lastStatus = "vacio";
                    continue;
                }
                String mime;
                if (firstByte == 0x47) {
                    mime = "video/mp2t";
                } else {
                    mime = stream.contentType == null ? "application/octet-stream" : stream.contentType;
                    if (mime.toLowerCase(Locale.ROOT).contains("text/html")) {
                        lastFailedType = cand[1];
                        closeQuietly(stream);
                        lastStatus = "html";
                        continue;
                    }
                }
                // Se reenvia el cuerpo segun llega: asi el reproductor no agota su
                // timeout aunque el CDN tarde 20 s en completar el segmento.
                Response resp = streamResponse(stream, mime, in);
                resp.addHeader("Cache-Control", "no-cache, no-store");
                return resp;
            } catch (Exception e) {
                closeQuietly(stream);
                lastStatus = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        return text(BAD_GATEWAY, "CDN no disponible (" + lastStatus + ")");
    }

    private Map<String, String> segmentHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", RbApi.UA);
        String ref = provider.referer();
        if (ref != null && !ref.isEmpty()) {
            h.put("referer", ref.endsWith("/") ? ref : ref + "/");
            h.put("origin", ref.endsWith("/") ? ref.substring(0, ref.length() - 1) : ref);
        }
        h.put("accept", "*/*");
        return h;
    }

    /** stream/detail como maximo una vez cada 5 s por canal. */
    private StreamInfo refreshStreamThrottled(long sid, StreamInfo current) {
        long now = System.currentTimeMillis();
        Long last = lastStreamRefresh.get(sid);
        if (last != null && now - last < 5000) return current;
        lastStreamRefresh.put(sid, now);
        return provider.refreshStream(sid);
    }

    /** /api/common/params como maximo una vez cada 5 s. */
    private void refreshParamsThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastParamsRefresh < 5000) return;
        lastParamsRefresh = now;
        provider.refreshParams();
    }

    private List<String[]> preferOtherThanLastFailed(List<String[]> candidates) {
        String failed = lastFailedType;
        if (failed == null || failed.isEmpty() || candidates.size() < 2) return candidates;
        List<String[]> first = new ArrayList<>();
        List<String[]> last = new ArrayList<>();
        for (String[] c : candidates) {
            if (failed.equals(c[1])) last.add(c);
            else first.add(c);
        }
        first.addAll(last);
        return first;
    }

    /** Respuesta con el cuerpo en streaming (Content-Length si el CDN lo da). */
    private static Response streamResponse(Http.Stream stream, String mime, InputStream in) {
        InputStream body = new FilterInputStream(in) {
            @Override public int read() throws IOException {
                int r = super.read();
                if (r < 0) close();
                return r;
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                int r = super.read(b, off, len);
                if (r < 0) close();
                return r;
            }

            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    stream.close();
                }
            }
        };
        if (stream.contentLength >= 0) {
            return newFixedLengthResponse(Response.Status.OK, mime, body, stream.contentLength);
        }
        return newChunkedResponse(Response.Status.OK, mime, body);
    }

    private static void closeQuietly(Http.Stream stream) {
        if (stream != null) stream.close();
    }

    /** Responde 200 completo o 206 parcial segun la cabecera Range del cliente. */
    private static Response ranged(IHTTPSession session, byte[] data, String mime) {
        ByteRange br = ByteRange.parse(session.getHeaders().get("range"), data.length);
        Response r;
        if (br == null) {
            r = newFixedLengthResponse(Response.Status.OK, mime, new ByteArrayInputStream(data), data.length);
        } else {
            r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime,
                    new ByteArrayInputStream(data, (int) br.start, (int) br.size()), br.size());
            r.addHeader("Content-Range", br.contentRange(data.length));
        }
        r.addHeader("Accept-Ranges", "bytes");
        return r;
    }

    private static String decodeU(String u) {
        try {
            int need = (4 - u.length() % 4) % 4;
            StringBuilder ub = new StringBuilder(u);
            for (int i = 0; i < need; i++) ub.append('=');
            return new String(Base64.getUrlDecoder().decode(ub.toString()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Reescritura de la playlist (pura y comprobable en tests)
    // ------------------------------------------------------------------

    /** Reescribe cada URL de la playlist al proxy ({@code base + /go/<sid>/seg?u=...}). */
    static String rewritePlaylist(String body, String cdn, long sid, String base) {
        StringBuilder out = new StringBuilder();
        for (String ln : body.split("\\r?\\n", -1)) {
            if (ln.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (ln.startsWith("#")) {
                out.append(startsWithKeyTag(ln) ? keyLine(ln, cdn, sid, base) : ln).append('\n');
                continue;
            }
            out.append(segProxyUrl(base, sid, resolve(cdn, ln.trim()))).append('\n');
        }
        return out.toString();
    }

    private static boolean startsWithKeyTag(String ln) {
        return ln.startsWith("#EXT-X-KEY") || ln.startsWith("#EXT-X-MAP")
                || ln.startsWith("#EXT-X-SESSION-KEY");
    }

    /** Reescribe el atributo URI="..." de #EXT-X-KEY/#EXT-X-MAP para que el proxy tambien sirva las claves. */
    static String keyLine(String line, String cdn, long sid, String base) {
        Matcher m = KEY_URI.matcher(line);
        if (!m.find()) return line;
        String abs = resolve(cdn, m.group(1));
        return line.substring(0, m.start(1)) + segProxyUrl(base, sid, abs) + line.substring(m.end(1));
    }

    private static String resolve(String cdn, String ref) {
        try {
            return new URI(cdn).resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static String segProxyUrl(String base, long sid, String abs) {
        String u = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(abs.getBytes(StandardCharsets.UTF_8));
        return base + "/go/" + sid + "/seg?u=" + u;
    }

    // ------------------------------------------------------------------
    // Página de diagnóstico
    // ------------------------------------------------------------------
    private Response diagnostics(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        String page = host != null && !host.isEmpty() ? "http://" + host + "/" : "este dispositivo";
        StringBuilder h = new StringBuilder(4096);
        h.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        h.append("<meta http-equiv=\"refresh\" content=\"4\">");
        h.append("<title>SportStreaming · diagnóstico</title></head>");
        h.append("<body style=\"font-family:sans-serif;margin:16px\">");
        h.append("<h3>SportStreaming · proxy de diagnóstico</h3>");
        h.append("<p>Puerto ").append(getListeningPort()).append(" · página abierta en ")
                .append(RequestLog.escapeHtml(page)).append("</p>");
        String ip = provider.phoneIp();
        if (ip != null) {
            h.append("<p>IP WiFi del móvil: <b>").append(RequestLog.escapeHtml(ip)).append("</b></p>");
        }
        h.append("<p>Canales resueltos en esta sesión:</p><ul>");
        List<StreamInfo> streams = provider.streamList();
        if (streams == null || streams.isEmpty()) {
            h.append("<li>(ninguno — abre un canal en la app)</li>");
        } else {
            for (StreamInfo s : streams) {
                h.append("<li>").append(s.sid).append(" — ").append(RequestLog.escapeHtml(s.name))
                        .append(" — ").append(RequestLog.escapeHtml(cdnHost(s.cdn))).append("</li>");
            }
        }
        h.append("</ul>");
        h.append("<p>Peticiones recibidas (más recientes primero):</p>");
        h.append("<table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse;font-size:13px\">");
        h.append("<tr><th>hora</th><th>cliente</th><th>petición</th><th>estado</th><th>cliente HTTP</th></tr>");
        List<RequestLog.Entry> entries = RequestLog.snapshot();
        if (entries.isEmpty()) {
            h.append("<tr><td colspan=\"5\">(sin peticiones todavía)</td></tr>");
        } else {
            for (RequestLog.Entry e : entries) {
                h.append("<tr><td>").append(RequestLog.escapeHtml(e.time)).append("</td><td>")
                        .append(RequestLog.escapeHtml(e.remote)).append("</td><td>")
                        .append(RequestLog.escapeHtml(e.request)).append("</td><td>")
                        .append(e.status).append("</td><td>")
                        .append(RequestLog.escapeHtml(e.note)).append("</td></tr>");
            }
        }
        h.append("</table>");
        h.append("<p style=\"color:#666;font-size:13px;max-width:780px\">Guía rápida: cuando reproduce el móvil, "
                + "las peticiones vienen de 127.0.0.1. Al enviar al Chromecast deben aparecer peticiones desde la IP "
                + "del televisor: si no aparece ninguna, el TV no está llegando al móvil (misma red WiFi y "
                + "«aislamiento de clientes» del router desactivado). Estado 502 = el CDN rechazó: vuelve a abrir el "
                + "canal en la app. Estado 404 = canal no resuelto en el proxy.</p>");
        h.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", h.toString());
    }

    private static String cdnHost(String cdn) {
        if (cdn == null) return "?";
        try {
            String h = new URI(cdn).getHost();
            return h == null ? "?" : h;
        } catch (Exception e) {
            return "?";
        }
    }
}

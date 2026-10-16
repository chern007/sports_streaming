package com.rea.directos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port de tools/rb_live.py: descubrimiento de configuracion (Firestore + /api/common/params),
 * API (protobuf + firmas md5) y decodificacion CSL de segmentos.
 */
public final class RbApi {

    private RbApi() {}

    // ------------------------------------------------------------------
    // Configuracion base (se auto-actualiza desde Firestore)
    // ------------------------------------------------------------------
    public static final String FALLBACK_DATA_API = "https://apis-data10.tcpwe138stya.ru";
    public static final String[] FALLBACK_FOTH_DOMAINS = {
            "https://jack01eo.mpanrc4imind.my/",
            "https://nadia73bc.mp77g69ainei3gx2voxygen.ru/",
            "https://morgan67cg.006hndchurch05g7ifbreathing.sbs/",
    };
    public static final String FALLBACK_SALT = "00";

    public static final String FIREBASE_PROJECT = "rbapp-672ea";
    public static final String FIREBASE_API_KEY = "AIzaSyC4pdqs2LpHOvA3S6IpbX9YnyGpve69amI";
    public static final String FIRESTORE_DOC =
            "https://firestore.googleapis.com/v1/projects/" + FIREBASE_PROJECT
                    + "/databases/(default)/documents/foth_APP_KVS/params_production?key=" + FIREBASE_API_KEY;

    public static final String UA = "rbapp/--dg-foth-app-320/3.0.320/12/13/UTC/en";

    private static final String API_ORIGIN = "https://hksc4ctu.buzz";

    public static Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", UA);
        h.put("origin", API_ORIGIN);
        h.put("referer", API_ORIGIN + "/");
        h.put("accept", "*/*");
        return h;
    }

    // ------------------------------------------------------------------
    // Modelos
    // ------------------------------------------------------------------
    public static final class MatchInfo {
        public final long id;
        public final String name;
        public final int channels;

        public MatchInfo(long id, String name, int channels) {
            this.id = id;
            this.name = name;
            this.channels = channels;
        }
    }

    public static final class LiveResult {
        public final List<MatchInfo> matches = new ArrayList<>();
        public final List<StreamInfo> markers = new ArrayList<>();

        /** Partidos (en orden de aparicion de los marcadores) que tienen algun stream en vivo. */
        public List<MatchInfo> liveMatches() {
            Map<Long, Integer> count = new LinkedHashMap<>();
            for (StreamInfo s : markers) {
                if (s.matchId > 0) {
                    count.put(s.matchId, count.containsKey(s.matchId) ? count.get(s.matchId) + 1 : 1);
                }
            }
            Map<Long, String> names = new HashMap<>();
            for (MatchInfo m : matches) names.put(m.id, m.name);
            List<MatchInfo> out = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : count.entrySet()) {
                String nm = names.get(e.getKey());
                out.add(new MatchInfo(e.getKey(), nm == null ? "" : nm, e.getValue()));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Utilidades (puras — usadas tambien por los tests)
    // ------------------------------------------------------------------
    public static String rot47(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 33 && c <= 126) sb.append((char) (((c - 33 + 47) % 94) + 33));
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String rot13(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') sb.append((char) ('a' + (c - 'a' + 13) % 26));
            else if (c >= 'A' && c <= 'Z') sb.append((char) ('A' + (c - 'A' + 13) % 26));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** bruDecode: quitar 8 chars -> URLDecode -> ROT13 -> Base64. */
    public static String bruDecode(String s) {
        if (s == null || s.length() <= 8) return null;
        try {
            String body = s.substring(8);
            String dec = URLDecoder.decode(body.replace("+", "%2B"), "UTF-8");
            String rot = rot13(dec);
            int pad = (4 - (rot.length() % 4)) % 4;
            StringBuilder p = new StringBuilder(rot);
            for (int i = 0; i < pad; i++) p.append('=');
            byte[] raw = Base64.getMimeDecoder().decode(p.toString());
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Query de una URL -> primer valor por clave (como parse_qs(...)[0]). */
    public static Map<String, String> parseQuery(String url) {
        Map<String, String> out = new HashMap<>();
        if (url == null) return out;
        int q = url.indexOf('?');
        if (q < 0) return out;
        for (String part : url.substring(q + 1).split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k, v;
            if (eq < 0) {
                k = part;
                v = "";
            } else {
                k = part.substring(0, eq);
                v = part.substring(eq + 1);
            }
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v.replace("+", "%2B"), "UTF-8");
            } catch (Exception ignored) {
                // se usa tal cual
            }
            if (!out.containsKey(k)) out.put(k, v);
        }
        return out;
    }

    /** _ctump decodificado: 'REGION:tipo@host' o lista separada por comas. Prefiere EU-ES/ES/EU/WW. */
    public static String cslHostFromCtump(String ctump) {
        List<String[]> entries = new ArrayList<>();
        for (String part : ctump.split(",")) {
            String p = part.trim();
            int colon = p.indexOf(':');
            int at = p.indexOf('@');
            if (colon < 0 || at < 0 || at < colon) continue;
            String reg = p.substring(0, colon).trim().toUpperCase(java.util.Locale.ROOT);
            int at2 = p.indexOf('@', colon + 1);
            if (at2 < 0) continue;
            String host = p.substring(at2 + 1).trim();
            if (!host.isEmpty()) entries.add(new String[]{reg, host});
        }
        for (String want : new String[]{"EU-ES", "ES", "EU", "WW"}) {
            for (String[] e : entries) {
                if (e[0].equals(want)) return e[1];
            }
        }
        return entries.isEmpty() ? null : entries.get(0)[1];
    }

    /** Construye la URL CSL final de un segmento. Devuelve [urlConS2, host] o null. */
    public static String[] resolveSegmentUrl(String segFullUrl, String salt) {
        Map<String, String> q = parseQuery(segFullUrl);
        String ctump = bruDecode(q.get("_ctump"));
        String ctuph = bruDecode(q.get("_ctuph"));
        if (ctump == null || ctuph == null) return null;
        String host = cslHostFromCtump(ctump);
        if (host == null) return null;
        String path = ctuph.startsWith("/") ? ctuph : "/" + ctuph;
        String url = "https://" + host + path;
        String last = path;
        int slash = last.lastIndexOf('/');
        if (slash >= 0) last = last.substring(slash + 1);
        int qm = last.indexOf('?');
        if (qm >= 0) last = last.substring(0, qm);
        String ver = parseQuery(url).get("_ver");
        if (ver == null) ver = "";
        String s2 = md5Hex(last + "," + ver + "," + salt);
        String joined = url + (url.indexOf('?') >= 0 ? "&" : "?") + "_s2=" + s2;
        return new String[]{joined, host};
    }

    // ------------------------------------------------------------------
    // Protobuf: respuestas de la API
    // ------------------------------------------------------------------
    public static final class PbResponse {
        public Long code;
        public String message;
        public byte[] data;
    }

    static PbResponse parsePbResponse(byte[] body) {
        PbResponse r = new PbResponse();
        for (Pb.Field f : Pb.parse(body)) {
            if (f.no == 2 && f.wire == 0) r.code = f.varint;
            else if (f.no == 3 && f.wire == 2) r.message = Pb.utf8(f.bytes);
            else if (f.no == 10 && f.wire == 2) r.data = f.bytes;
        }
        return r;
    }

    static Map<Long, String> parseKvMap(byte[] buf) {
        Map<Long, String> out = new HashMap<>();
        for (Pb.Field f : Pb.parse(buf)) {
            if (f.no == 1 && f.wire == 2) {
                Long k = null;
                String v = null;
                for (Pb.Field in : Pb.parse(f.bytes)) {
                    if (in.no == 1 && in.wire == 0) k = in.varint;
                    else if (in.no == 2 && in.wire == 2) v = Pb.utf8(in.bytes);
                }
                if (k != null) out.put(k, v);
            }
        }
        return out;
    }

    public static StreamInfo parseStream(byte[] buf) {
        StreamInfo s = new StreamInfo();
        for (Pb.Field f : Pb.parse(buf)) {
            if (f.no == 1 && f.wire == 0) s.sid = f.varint;
            else if (f.no == 3 && f.wire == 2) s.name = Pb.utf8(f.bytes);
            else if (f.no == 4 && f.wire == 2) s.url = Pb.utf8(f.bytes);
            else if (f.no == 9 && f.wire == 0) s.siteType = f.varint;
            else if (f.no == 50 && f.wire == 0) s.matchId = f.varint;
        }
        return s;
    }

    static MatchInfo parseMatch(byte[] buf) {
        long id = 0;
        String name = "";
        for (Pb.Field f : Pb.parse(buf)) {
            if (f.no == 1 && f.wire == 0) {
                id = f.varint;
            } else if (f.no == 30 && f.wire == 2) {
                for (Pb.Field in : Pb.parse(f.bytes)) {
                    if (in.no == 2 && in.wire == 2) {
                        String s = Pb.utf8(in.bytes);
                        if (s != null && !s.trim().isEmpty()) name = s.trim();
                    }
                }
            }
        }
        return new MatchInfo(id, name, 0);
    }

    // ------------------------------------------------------------------
    // Descubrimiento de configuracion
    // ------------------------------------------------------------------
    public static String resolveDataApi() {
        try {
            Http.Resp r = Http.get(FIRESTORE_DOC, null);
            if (r.status == 200) {
                JSONObject doc = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
                String v = doc.getJSONObject("fields").getJSONObject("DATA_API").getString("stringValue");
                if (v != null && !v.isEmpty()) {
                    while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return FALLBACK_DATA_API;
    }

    /** Devuelve [salt, referer] desde /api/common/params (rot47-json). */
    public static String[] fetchParams(String dataApi) {
        String salt = FALLBACK_SALT;
        String referer = FALLBACK_FOTH_DOMAINS[0];
        try {
            Map<String, String> h = new HashMap<>();
            h.put("user-agent", UA);
            h.put("accept", "*/*");
            Http.Resp r = Http.get(dataApi + "/api/common/params", h);
            if (r.status == 200) {
                JSONObject params = new JSONObject(rot47(new String(r.body, StandardCharsets.UTF_8)));
                String csl = optString(params, "common:cdnSmartLink:app");
                if (csl != null) {
                    JSONObject auth = new JSONObject(csl).optJSONObject("auth");
                    if (auth != null) salt = auth.optString("salt", FALLBACK_SALT);
                }
                String gpd = optString(params, "g_player_domains");
                if (gpd != null) {
                    JSONArray foth = new JSONObject(gpd).optJSONArray("foth");
                    if (foth != null) {
                        for (int i = 0; i < foth.length(); i++) {
                            String d = foth.optString(i, "");
                            if (d.startsWith("http")) {
                                referer = d.endsWith("/") ? d : d + "/";
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{salt, referer};
    }

    private static String optString(JSONObject o, String key) {
        try {
            String s = o.optString(key, null);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // API: bs -> match/live -> match/detail -> stream/detail
    // ------------------------------------------------------------------
    public static String getBsSignature(String dataApi, int code) throws Exception {
        Http.Resp r = Http.get(dataApi + "/api/common/bs?code=" + code + "&sportType=1&stream=true", apiHeaders());
        if (r.status != 200) return null;
        PbResponse resp = parsePbResponse(r.body);
        Map<Long, String> kv = resp.data == null ? new HashMap<Long, String>() : parseKvMap(resp.data);
        return kv.get((long) code);
    }

    public static LiveResult fetchLiveMatches(String dataApi) throws Exception {
        String sig = getBsSignature(dataApi, 100);
        if (sig == null) throw new IOException("No se pudo obtener la firma BS(100)");
        String p = md5Hex("language=0&sportType=1&stream=true").substring(0, 6);
        String url = dataApi + "/sfver" + p + sig + "/api/match/live?language=0&sportType=1&stream=true";
        Http.Resp r = Http.get(url, apiHeaders());
        PbResponse resp = parsePbResponse(r.body);
        LiveResult out = new LiveResult();
        if (resp.data != null) {
            for (Pb.Field f : Pb.parse(resp.data)) {
                if (f.no == 1 && f.wire == 2) out.matches.add(parseMatch(f.bytes));
                else if (f.no == 2 && f.wire == 2) out.markers.add(parseStream(f.bytes));
            }
        }
        return out;
    }

    public static List<StreamInfo> matchDetailStreams(String dataApi, long matchId) throws Exception {
        String sig = getBsSignature(dataApi, 102);
        if (sig == null) throw new IOException("No se pudo obtener la firma BS(102)");
        String p = md5Hex("matchId=" + matchId + "&language=0&sportType=1&stream=true").substring(0, 6);
        String url = dataApi + "/sfver" + p + sig + "/api/match/detail?matchId=" + matchId
                + "&language=0&sportType=1&stream=true";
        Http.Resp r = Http.get(url, apiHeaders());
        PbResponse resp = parsePbResponse(r.body);
        List<StreamInfo> out = new ArrayList<>();
        if (resp.data != null) {
            for (Pb.Field f : Pb.parse(resp.data)) {
                if (f.no == 2 && f.wire == 2) out.add(parseStream(f.bytes));
            }
        }
        return out;
    }

    /** Resuelve un canal: incluye la URL CDN desofuscada (rot47 + quitar 8 chars). Null si no hay datos. */
    public static StreamInfo streamDetail(String dataApi, long matchId, long streamId, long siteType) throws Exception {
        String url = dataApi + "/api/stream/detail?matchId=" + matchId + "&sportType=1&streamId=" + streamId
                + "&siteType=" + siteType + "&continent=EU&country=ES&digit=foth&withOriginal=true";
        Http.Resp r = Http.get(url, apiHeaders());
        PbResponse resp = parsePbResponse(r.body);
        if (resp.data != null) {
            for (Pb.Field f : Pb.parse(resp.data)) {
                if (f.no == 2 && f.wire == 2) {
                    StreamInfo s = parseStream(f.bytes);
                    if (s.url != null && s.url.length() > 8) {
                        s.cdn = rot47(s.url).substring(8);
                        s.matchId = matchId;
                        return s;
                    }
                }
            }
        }
        return null;
    }
}

package com.rea.directos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.Test;

import fi.iki.elonen.NanoHTTPD;

/** Reescritura de la playlist HLS hacia el proxy local. */
public class ProxyServerTest {

    private static final String BASE = "http://192.168.1.10:8899";
    private static final String CDN = "https://cdn.example.com/hls/abc/index.m3u8";

    private static String u(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void reescribeSegmentosRelativosYAbsolutos() {
        String body = "#EXTM3U\n#EXT-X-VERSION:3\nseg-1.ts\nhttps://cdn.example.com/other/seg-2.ts?sig=1\n";
        String out = ProxyServer.rewritePlaylist(body, CDN, 7, BASE);
        String[] ln = out.split("\n", -1);
        assertEquals("#EXTM3U", ln[0]);
        assertEquals("#EXT-X-VERSION:3", ln[1]);
        assertEquals(BASE + "/go/7/seg?u=" + u("https://cdn.example.com/hls/abc/seg-1.ts"), ln[2]);
        assertEquals(BASE + "/go/7/seg?u=" + u("https://cdn.example.com/other/seg-2.ts?sig=1"), ln[3]);
    }

    @Test
    public void reescribeUriDeClaves() {
        String line = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1";
        String out = ProxyServer.keyLine(line, CDN, 3, BASE);
        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"" + BASE + "/go/3/seg?u="
                + u("https://cdn.example.com/hls/abc/key.bin") + "\",IV=0x1", out);
    }

    @Test
    public void limpiaCrYConservaComentarios() {
        String body = "#EXTM3U\r\n#EXTINF:6.0,\r\nseg.ts\r\n";
        String out = ProxyServer.rewritePlaylist(body, CDN, 1, "http://127.0.0.1:8899");
        assertFalse(out.contains("\r"));
        assertTrue(out.startsWith("#EXTM3U\n#EXTINF:6.0,\n"));
        assertTrue(out.contains("/go/1/seg?u="));
    }

    @Test
    public void lineaSinUriNoSeToca() {
        assertEquals("#EXT-X-DISCONTINUITY",
                ProxyServer.keyLine("#EXT-X-DISCONTINUITY", CDN, 1, BASE));
    }

    @Test
    public void pruebaOtroHostAunqueTengaElMismoTipoCsl() {
        String segment = CDN
                + "?_ctump=ABCDEFGHI1p6L2MNLF5yrTSgpTkyYzAioFkSIF1SHmcwMxOvYzI4LJ1joTHhL29g"
                + "&_ctuph=ABCDEFGHY3LiLF9mMJphqUZ%2FK3Mypw0k";
        List<String[]> urls = ProxyServer.segmentCandidates(segment, "00", "b.example.com");
        assertEquals(2, urls.size());
        assertTrue(urls.get(0)[0].startsWith("https://a.example.com/"));
        assertTrue(urls.get(1)[0].startsWith("https://b.example.com/"));
    }

    @Test
    public void recuperaPlaylistCuandoLaUrlAnteriorNoConecta() throws Exception {
        NanoHTTPD cdn = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                return newFixedLengthResponse("#EXTM3U\n#EXTINF:6,\nsegment.ts\n");
            }
        };
        cdn.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        StreamInfo expired = new StreamInfo();
        StreamInfo fresh = new StreamInfo();
        fresh.cdn = "http://127.0.0.1:" + cdn.getListeningPort() + "/new/index.m3u8";
        ProxyServer proxy = new ProxyServer(0, new ProxyServer.Provider() {
            @Override public StreamInfo stream(long sid) { return expired; }
            @Override public StreamInfo refreshStream(long sid) { return fresh; }
            @Override public void refreshParams() {}
            @Override public String salt() { return "00"; }
            @Override public String referer() { return null; }
            @Override public List<StreamInfo> streamList() { return List.of(); }
            @Override public String phoneIp() { return null; }
        });
        try {
            proxy.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                expired.cdn = "http://127.0.0.1:" + closed.getLocalPort() + "/old/index.m3u8";
            }
            String base = "http://127.0.0.1:" + proxy.getListeningPort();
            Http.Resp result = Http.getPlaybackMetadata(base + "/go/7/index.m3u8", null);
            assertEquals(200, result.status);
            assertTrue(new String(result.body, StandardCharsets.UTF_8).contains(
                    base + "/go/7/seg?u=" + u(fresh.cdn.replace("index.m3u8", "segment.ts"))));
        } finally {
            proxy.stop();
            cdn.stop();
        }
    }
}

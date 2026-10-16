package com.rea.directos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

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
}

package com.rea.directos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Vectores calculados con el propio rb_live.py para validar el port Python -> Java. */
public class RbApiTest {

    @Test
    public void rot47RoundTrip() {
        String s = "https://ejemplo.com/url?x=1";
        assertEquals(s, RbApi.rot47(RbApi.rot47(s)));
    }

    @Test
    public void bruDecodeKnownVector() {
        assertEquals("Hello bru!", RbApi.bruDecode("ABCDEFGHFTIfoT8tLaW1VD%3D%3D"));
        assertNull(RbApi.bruDecode(null));
        assertNull(RbApi.bruDecode("12345678"));
    }

    @Test
    public void cslHostFromCtump() {
        String multi = RbApi.bruDecode("ABCDEFGHI1p6L2MNLF5yrTSgpTkyYzAioFkSIF1SHmcwMxOvYzI4LJ1joTHhL29g");
        assertEquals("WW:cf@a.example.com,EU-ES:cf@b.example.com", multi);
        assertEquals("b.example.com", RbApi.cslHostFromCtump(multi));

        String solo = RbApi.bruDecode("ABCDEFGHEIHgEIZ6L2MNL2EhYzI4LJ1joTHhL29g");
        assertEquals("EU-ES:cf@cdn.example.com", solo);
        assertEquals("cdn.example.com", RbApi.cslHostFromCtump(solo));
    }

    @Test
    public void resolveSegmentUrlBuildsSignedUrl() {
        String seg = "https://fhlsport954.example/cfall/s2001/v3b/x/index.m3u8"
                + "?_ctump=ABCDEFGHEIHgEIZ6L2MNL2EhYzI4LJ1joTHhL29g"
                + "&_ctuph=ABCDEFGHY3LiLF9mMJphqUZ%2FK3Mypw0k";
        String[] res = RbApi.resolveSegmentUrl(seg, "00");
        assertNotNull(res);
        assertEquals("https://cdn.example.com/v/a/seg.ts?_ver=1&_s2=5ed6048e02d7b9204aec33a0de822260", res[0]);
        assertEquals("cdn.example.com", res[1]);
    }

    @Test
    public void resolveSegmentUrlFailsWithoutCslParams() {
        assertNull(RbApi.resolveSegmentUrl("https://x.example/a/b.ts", "00"));
    }
}

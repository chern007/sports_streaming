package com.rea.directos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/** Registro circular de peticiones para la página de diagnóstico. */
public class RequestLogTest {

    @Before
    public void limpia() {
        RequestLog.clear();
    }

    @Test
    public void guardaMasRecientePrimeroYLimita() {
        for (int i = 0; i < RequestLog.MAX + 10; i++) {
            RequestLog.add("192.168.1." + i, "GET /go/1/seg", 200, "ExoPlayer (móvil)");
        }
        List<RequestLog.Entry> list = RequestLog.snapshot();
        assertEquals(RequestLog.MAX, list.size());
        assertEquals("192.168.1." + (RequestLog.MAX + 9), list.get(0).remote);
        assertEquals("192.168.1.10", list.get(RequestLog.MAX - 1).remote);
    }

    @Test
    public void recortaCamposLargos() {
        RequestLog.add("a".repeat(200), "GET", 200, "b".repeat(200));
        RequestLog.Entry e = RequestLog.snapshot().get(0);
        assertTrue(e.remote.length() <= 40);
        assertTrue(e.request.length() <= 70);
        assertTrue(e.note.length() <= 70);
    }

    @Test
    public void escapaHtml() {
        assertEquals("&lt;b&gt;x&amp;&quot;y&quot;&#39;", RequestLog.escapeHtml("<b>x&\"y\"'"));
        assertEquals("", RequestLog.escapeHtml(null));
    }
}

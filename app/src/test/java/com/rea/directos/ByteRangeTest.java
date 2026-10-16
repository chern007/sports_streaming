package com.rea.directos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Vectores de la cabecera HTTP Range. */
public class ByteRangeTest {

    @Test
    public void rangoSimple() {
        ByteRange r = ByteRange.parse("bytes=0-99", 500);
        assertEquals(0, r.start);
        assertEquals(99, r.end);
        assertEquals(100, r.size());
        assertEquals("bytes 0-99/500", r.contentRange(500));
    }

    @Test
    public void desdeHastaElFinal() {
        ByteRange r = ByteRange.parse("bytes=100-", 500);
        assertEquals(100, r.start);
        assertEquals(499, r.end);
    }

    @Test
    public void sufijoFinal() {
        ByteRange r = ByteRange.parse("bytes=-50", 500);
        assertEquals(450, r.start);
        assertEquals(499, r.end);
    }

    @Test
    public void recortaFinSiExcede() {
        ByteRange r = ByteRange.parse("bytes=499-600", 500);
        assertEquals(499, r.start);
        assertEquals(499, r.end);
    }

    @Test
    public void completoDesdeCero() {
        ByteRange r = ByteRange.parse("bytes=0-", 43);
        assertEquals(0, r.start);
        assertEquals(42, r.end);
        assertEquals(43, r.size());
    }

    @Test
    public void invalidos() {
        assertNull(ByteRange.parse(null, 500));
        assertNull(ByteRange.parse("bytes=500-", 500));
        assertNull(ByteRange.parse("bytes=0-1,5-6", 500));
        assertNull(ByteRange.parse("no-es-rango", 500));
        assertNull(ByteRange.parse("bytes=", 500));
        assertNull(ByteRange.parse("bytes=abc-def", 500));
        assertNull(ByteRange.parse("bytes=0-99", 0));
        assertNull(ByteRange.parse("bytes=-0", 500));
    }
}

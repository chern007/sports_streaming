package com.rea.directos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Vectores del filtro de búsqueda: sin acentos y sin distinguir mayúsculas. */
public class TextTest {

    @Test
    public void foldQuitaAcentosYMayusculas() {
        assertEquals("atletico madrid", Text.fold("Atlético Madrid"));
        assertEquals("espana - francia", Text.fold("España - Francia"));
        assertEquals("barca", Text.fold("Barça"));
    }

    @Test
    public void foldRecortaEspaciosYNull() {
        assertEquals("", Text.fold(null));
        assertEquals("", Text.fold("   "));
        assertEquals("real madrid", Text.fold("  Real Madrid  "));
    }

    @Test
    public void busquedaSinAcentosEncuentraConAcentos() {
        assertTrue(Text.fold("Selección Española").contains(Text.fold("seleccion")));
        assertTrue(Text.fold("Múnich vs Köln").contains(Text.fold("KOLN")));
    }
}

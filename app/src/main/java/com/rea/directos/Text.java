package com.rea.directos;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de texto (normalización para el filtro de búsqueda). */
public final class Text {

    private Text() {
    }

    /** Pasa a minúsculas y quita acentos/diacríticos para comparar de forma cómoda. */
    public static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT);
    }
}

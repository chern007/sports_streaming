package com.rea.directos;

/** Datos de un partido o de un canal/stream. */
public class StreamInfo {

    public long sid;        // streamId
    public long matchId;
    public long siteType;
    public String name = "";
    public String url;      // campo url ofuscado (respuesta de la API)
    public String cdn;      // URL CDN desofuscada (playlist)
}

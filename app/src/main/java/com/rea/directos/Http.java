package com.rea.directos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** Cliente HTTP unico. TLS sin verificacion, igual que rb_live.py (CERT_NONE). */
public final class Http {

    public static final class Resp {
        public int status;
        public byte[] body = new byte[0];
        public String contentType;
    }

    /**
     * Respuesta abierta para reenviar el cuerpo sin esperar a descargarlo entero.
     * Es clave para los segmentos lentos del CDN: si el proxy espera a tener el
     * segmento completo, el reproductor puede agotar su timeout de lectura.
     */
    public static final class Stream {
        public int status;
        public String contentType;
        public long contentLength = -1;
        public InputStream body;
        private Response response;

        public void close() {
            try {
                if (body != null) body.close();
            } catch (IOException ignored) {
            }
            try {
                if (response != null) response.close();
            } catch (Exception ignored) {
            }
            body = null;
            response = null;
        }
    }

    private static final class Clients {
        static final OkHttpClient GENERAL = build();
        static final OkHttpClient PLAYBACK = GENERAL.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .build();
    }

    private Http() {}

    public static OkHttpClient client() {
        return Clients.GENERAL;
    }

    private static OkHttpClient build() {
        try {
            final X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), tm)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(25, TimeUnit.SECONDS)
                    .writeTimeout(25, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Resp get(String url, Map<String, String> headers) throws IOException {
        return get(client().newCall(request(url, headers)));
    }

    /** Las playlists y sus credenciales son pequeñas y tienen un plazo total. */
    public static Resp getPlaybackMetadata(String url, Map<String, String> headers) throws IOException {
        Call call = Clients.PLAYBACK.newCall(request(url, headers));
        call.timeout().timeout(7, TimeUnit.SECONDS);
        return get(call);
    }

    private static Resp get(Call call) throws IOException {
        try (Response r = call.execute()) {
            Resp out = new Resp();
            out.status = r.code();
            out.body = r.body() != null ? r.body().bytes() : new byte[0];
            out.contentType = r.header("Content-Type");
            return out;
        }
    }

    /**
     * Abre la conexión y deja el cuerpo sin leer, para ir copiándolo al cliente.
     * El llamador es responsable de cerrar el {@link Stream}.
     */
    public static Stream open(String url, Map<String, String> headers) throws IOException {
        // El plazo de lectura se aplica entre bytes. El segmento entero puede tardar más.
        Response r = Clients.PLAYBACK.newCall(request(url, headers)).execute();
        Stream out = new Stream();
        out.response = r;
        out.status = r.code();
        out.contentType = r.header("Content-Type");
        if (r.body() != null) {
            out.contentLength = r.body().contentLength();
            out.body = r.body().byteStream();
        } else {
            out.contentLength = 0;
            out.body = new ByteArrayInputStream(new byte[0]);
        }
        return out;
    }

    private static Request request(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        return builder.build();
    }
}

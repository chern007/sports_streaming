package com.rea.directos;

import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Cliente HTTP unico. TLS sin verificacion, igual que rb_live.py (CERT_NONE). */
public final class Http {

    public static final class Resp {
        public int status;
        public byte[] body = new byte[0];
        public String contentType;
    }

    private static volatile OkHttpClient client;

    private Http() {}

    public static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (Http.class) {
                c = client;
                if (c == null) {
                    c = build();
                    client = c;
                }
            }
        }
        return c;
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
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Resp get(String url, Map<String, String> headers) throws IOException {
        Request.Builder b = new Request.Builder().url(url).get();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                b.header(e.getKey(), e.getValue());
            }
        }
        try (Response r = client().newCall(b.build()).execute()) {
            Resp out = new Resp();
            out.status = r.code();
            out.body = r.body() != null ? r.body().bytes() : new byte[0];
            out.contentType = r.header("Content-Type");
            return out;
        }
    }
}

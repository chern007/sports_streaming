# REA Directos (Android)

App Android (Java) para ver los partidos en directo de `com.fctv77.app` desde el teléfono,
sin PC: la app hace en local lo mismo que `tools/rb_live.py` (resolver la API, descifrar los
segmentos "bru" del CDN y reescribirlos como HLS normal).

- **Listado de partidos con directo** (según la API en vivo).
- Al pulsar un partido: **canales disponibles**.
- Al pulsar un canal: **reproductor integrado** (Media3 ExoPlayer) con botón **"Ver en VLC"**
  (intent a VLC usando la misma URL local del proxy).

## Instalación

1. Ve a la pestaña **Releases** de este repositorio.
2. Descarga el APK (si el build está firmado se adjunta `app-release.apk`; si no, `app-debug.apk`).
3. Instálalo en el teléfono (permitir "instalar apps de orígenes desconocidos").
4. Opcional: instala **VLC** para el botón "Ver en VLC".

## Cómo funciona

```
App Android (monolítica, Java)
├── RbApi        -> port de rb_live.py: Firestore (DATA_API) -> bs/md5 -> match/live,
│                   match/detail, stream/detail, ROT47 + bruDecode + firma _s2
├── ProxyService -> foreground service con servidor HTTP local (NanoHTTPD, 127.0.0.1:8899)
│                   /go/<sid>/index.m3u8  (playlist reescrita)
│                   /go/<sid>/seg?u=...   (segmento resuelto contra el CDN)
├── PlayerActivity -> ExoPlayer contra el proxy local + botón VLC (intent)
└── UI: MainActivity (partidos) -> MatchActivity (canales) -> PlayerActivity
```

Detalles clave del port:

- TLS sin verificación para API/CDN (igual que `rb_live.py`, `CERT_NONE`).
- Cleartext HTTP permitido **solo** para `127.0.0.1` (network security config).
- UA `rbapp/--dg-foth-app-320/3.0.320/12/13/UTC/en` + `origin/referer` en las llamadas a la API.
- VLC (otra app) puede leer `http://127.0.0.1:8899/...` porque el proxy corre en un
  foreground service; hay un botón "Detener" en la notificación.
- Tests unitarios (`app/src/test/.../RbApiTest.java`) con vectores generados por el propio
  `rb_live.py` (bruDecode, _s2, ctump) para validar el port.

## Compilación (GitHub Actions)

`.github/workflows/build.yml`:

- **Push a `main`** → tests + APK debug como artifact (`rea-directos-debug`).
- **Tag `v*`** (p. ej. `git tag v1.0.0 && git push origin v1.0.0`) → además APK release
  firmada y **Release de GitHub** con el APK adjunto.

### Firma (secrets)

Para que la release salga firmada hay que configurar 4 secrets en
`Settings → Secrets and variables → Actions`:

| Secret | Valor |
|---|---|
| `KEYSTORE_BASE64` | contenido del keystore en base64 (tu archivo `release.keystore.b64`) |
| `KEYSTORE_PASSWORD` | contraseña del keystore |
| `KEY_ALIAS` | alias de la clave |
| `KEY_PASSWORD` | contraseña de la clave |

Sin estos secrets el workflow sigue funcionando: adjunta el APK debug a la release.

### Build local (opcional)

Requiere JDK 17 y Android SDK 34. Para firmar en local, crea `keystore.properties`
(ignorado por git):

```properties
storeFile=release.keystore
storePassword=xxxx
keyAlias=rea
keyPassword=xxxx
storeType=PKCS12
```

```bash
./gradlew assembleDebug     # APK debug
./gradlew assembleRelease   # APK release (firmada si hay keystore)
```

## Estructura

```
app/src/main/java/com/rea/directos/
  Pb.java            parser mínimmo de protobuf
  Http.java          OkHttp (TLS sin verificación)
  RbApi.java         port de rb_live.py (API + CSL + utilidades)
  ProxyServer.java   proxy HLS (NanoHTTPD)
  ProxyService.java  foreground service que mantiene el proxy
  MainActivity.java  lista de partidos
  MatchActivity.java canales por partido
  PlayerActivity.java reproductor (ExoPlayer + VLC)
  RowsAdapter.java   adaptador de listas
```

## Notas

- Las URLs del CDN caducan por segundos: todo se resuelve en el momento (proxy), no
  guardes URLs de segmentos.
- Uso: verificación técnica de disponibilidad de las fuentes del propio servicio analizado
  (equivalente a lo que hace la app original); respeta los términos que apliquen.

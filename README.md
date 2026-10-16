# SportStreaming (Android)

App Android (Java) para ver los partidos en directo de `com.fctv77.app` desde el teléfono,
sin PC: la app hace en local lo mismo que `tools/rb_live.py` (resolver la API, descifrar los
segmentos "bru" del CDN y reescribirlos como HLS normal).

- **Listado de partidos con directo** (según la API en vivo) con **buscador/filtro** por nombre.
- Al pulsar un partido: **canales disponibles**.
- Al pulsar un canal: **reproductor integrado** (Media3 ExoPlayer) con:
  - botón **pantalla completa** (inmersivo + rotación),
  - botón **Chromecast** (`Enviar a TV`): el televisor reproduce el canal vía el proxy del
    móvil (misma red WiFi),
  - botón **"Ver en VLC"** (intent a VLC usando la misma URL local del proxy),
  - **página de diagnóstico** del proxy: `http://<IP-del-móvil>:8899/` (la URL aparece en la
    notificación) con los canales resueltos y las últimas peticiones recibidas.

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
├── ProxyService -> foreground service con servidor HTTP local (NanoHTTPD, puerto 8899+)
│                   /go/<sid>/index.m3u8  (playlist reescrita, con el host del cliente)
│                   /go/<sid>/seg?u=...   (segmento resuelto contra el CDN)
├── PlayerActivity -> ExoPlayer contra el proxy local + pantalla completa + Chromecast + VLC
└── UI: MainActivity (partidos + filtro) -> MatchActivity (canales) -> PlayerActivity
```

Detalles clave del port:

- TLS sin verificación para API/CDN (igual que `rb_live.py`, `CERT_NONE`).
- Cleartext HTTP permitido **solo** para `127.0.0.1` (network security config) para el
  reproductor local; el receptor de Chromecast accede al proxy por la **IP del móvil en la
  WiFi** (misma red), con cabeceras CORS incluidas.
- El proxy responde **HEAD sin cuerpo** (parche a un fallo conocido de NanoHTTPD 2.3.1 que
  adjuntaba el cuerpo y corrompía la conexión), soporta `Range` (respuestas 206) y añade
  `Access-Control-Allow-Private-Network` para receptores Chromium modernos.
- Antes de enviar al Chromecast la app hace una **autoprueba** del proxy por la IP LAN (socket
  crudo: la config de red bloquea OkHttp a IPs LAN) y muestra un aviso claro si falla; los
  errores del receptor también se notifican con un aviso.
- UA `rbapp/--dg-foth-app-320/3.0.320/12/13/UTC/en` + `origin/referer` en las llamadas a la API.
- VLC (otra app) puede leer `http://127.0.0.1:8899/...` porque el proxy corre en un
  foreground service; hay un botón "Detener" en la notificación.
- Tests unitarios (`app/src/test/.../RbApiTest.java`, `TextTest.java`) con vectores generados
  por el propio `rb_live.py` (bruDecode, _s2, ctump) y del filtro de búsqueda, para validar el port.

## Si el Chromecast no reproduce

1. Abre `http://<IP-del-móvil>:8899/` en un navegador (móvil o PC en la misma WiFi) mientras la
   TV intenta reproducir. La URL exacta del diagnóstico aparece en **la notificación** del proxy.
2. Si **no aparecen peticiones desde la IP del televisor** → la TV no llega al móvil: comprueba
   misma red WiFi y que el router no tenga activado "aislamiento de clientes/AP".
3. Si aparecen peticiones del TV con estado **502** → el CDN rechazó la petición (canal
   caducado): vuelve a abrir el canal en la app y reintenta.
4. Nota: el botón "Ver en VLC" abre VLC con la URL `127.0.0.1` (solo válida en el móvil). Si
   desde VLC intentas reproducir en el Chromecast, esa URL no vale para el televisor: usa el
   botón **Chromecast de la app**.

## Compilación (GitHub Actions)

`.github/workflows/build.yml`:

- **Push a `main`** → tests + APK debug como artifact (`sportstreaming-debug`).
- **Tag `v*`** (p. ej. `git tag v1.1.0 && git push origin v1.1.0`) → además APK release
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
  Pb.java               parser mínimo de protobuf
  Http.java             OkHttp (TLS sin verificación)
  RbApi.java            port de rb_live.py (API + CSL + utilidades)
  ProxyServer.java      proxy HLS (NanoHTTPD: CORS, host del cliente, HEAD/Range, /diag)
  ProxyService.java     foreground service que mantiene el proxy (prueba LAN, notificación)
  CastOptionsProvider.java  receptor por defecto de Chromecast
  RequestLog.java       registro circular de peticiones (página de diagnóstico)
  ByteRange.java        cabecera HTTP Range (respuestas 206)
  MainActivity.java     lista de partidos + filtro de búsqueda
  MatchActivity.java    canales por partido
  PlayerActivity.java   reproductor (ExoPlayer + pantalla completa + Chromecast + VLC)
  RowsAdapter.java      adaptador de listas
  Text.java             normalización de texto (filtro sin acentos)
```

## Notas

- Las URLs del CDN caducan por segundos: todo se resuelve en el momento (proxy), no
  guardes URLs de segmentos.
- Uso: verificación técnica de disponibilidad de las fuentes del propio servicio analizado
  (equivalente a lo que hace la app original); respeta los términos que apliquen.

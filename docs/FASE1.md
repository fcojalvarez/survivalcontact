# Fase 1: fork y limpieza — plan detallado

Análisis del código importado (bitchat-android, commit `14ab73c`) para quitar Nostr, Tor y geohash sin romper la malla. **Este documento no cambia código**: es la hoja de ruta para hacerlo en Android Studio, compilando en cada paso.

**Objetivo de la fase:** dos móviles en modo avión se envían mensajes, con nombre e icono propios y sin ningún código que use internet.

## Resumen

| Bloque | Dónde está | Tamaño | Qué hacemos |
|---|---|---|---|
| Nostr (internet) | `nostr/` | 34 ficheros, ~8.300 líneas | Borrar entero |
| Canales por geohash | `geohash/` (parte) + pantallas en `ui/` | 13 ficheros, ~2.000 líneas + UI | Borrar, **salvo los proveedores de GPS** |
| Tor (Arti) | `net/` + `jniLibs/*/libarti_android.so` | 4 ficheros + **21 MB** de librerías nativas | Borrar entero |
| Globo 3D de actividad | `ui/globe/` + `assets/world_*.geojson` | 7 ficheros + 236 KB | Borrar |
| Buscar actualizaciones en GitHub | `util/GitHubReleaseClient.kt`, `ApkDownloadViewModel` | — | Borrar (el plan prohíbe comprobaciones de red) |

En total se van unos **~15.000 líneas de ~82.000** y el APK baja más de 20 MB. Hay **35 ficheros fuera de esos paquetes** que los usan y hay que retocar (lista abajo).

## Qué se queda (y por qué)

- **`mesh/`, `noise/`, `protocol/`, `crypto/`**: la malla Bluetooth, el cifrado y los paquetes. No se tocan salvo para quitar referencias.
- **`wifi-aware/`**: segundo transporte local (Wi‑Fi Aware, sin router ni internet) con más alcance y ancho de banda que BLE en móviles compatibles. Encaja con el objetivo de alcance. Se mantiene, pero en la fase 5 hay que medir su consumo y, si hace falta, dejarlo desactivado por defecto.
- **`hotspot/`**: comparte el APK desde el propio móvil por Wi‑Fi local con un QR. Muy útil en una emergencia para instalar la app a quien no la tiene. Se mantiene.
- **Proveedores de GPS** (`geohash/LocationProvider.kt`, `FusedLocationProvider.kt`, `SystemLocationProvider.kt`): los necesitaremos en la fase 4. Se **mueven** a un paquete nuevo `location/` en vez de borrarse.
- **Favoritos** (`favorites/`): se quedan, pero sin el campo de clave Nostr.

## Pasos, en orden

Cada paso debe terminar **compilando** (`./gradlew assembleDebug`) y con commit. Así, si algo se rompe, se sabe en qué paso fue.

### Paso 1 — Mover los proveedores de GPS

Mover `LocationProvider.kt`, `FusedLocationProvider.kt` y `SystemLocationProvider.kt` de `geohash/` a `location/` y cambiar su `package`. Comprobar que nada más de `geohash/` los necesita.

### Paso 2 — Quitar Tor

1. Borrar `net/ArtiTorManager.kt`, `TorMode.kt`, `TorPreferenceManager.kt`.
2. `net/OkHttpProvider.kt` solo lo usan Nostr y la descarga de actualizaciones: se borra en el paso 4 junto con ellos.
3. Borrar `app/src/main/jniLibs/*/libarti_android.so` (21 MB) y `tools/arti-build/`.
4. Retocar: `BitchatApplication.kt` (arranque de Tor), `service/AppShutdownCoordinator.kt`, `ui/AboutSheet.kt` (interruptor de Tor), `ui/ChatHeader.kt` (indicador de Tor), `ui/LocationChannelsSheet.kt`.

### Paso 3 — Quitar el enrutado por Nostr de los mensajes privados

Es el paso delicado: toca el envío de mensajes.

1. `services/MessageRouter.kt`: hoy elige entre malla y Nostr. Dejar solo malla (Bluetooth o Wi‑Fi Aware) y, si el destinatario no está alcanzable, **encolar** el mensaje (esa cola ya existe: «Queued PM … no mesh»). Quitar `canSendViaNostr` y las ramas de geohash.
2. `mesh/UnifiedMeshService.kt` (línea ~128) y `mesh/BluetoothMeshService.kt` (línea ~1125): quitar las referencias a la identidad Nostr y al registro de alias de geohash.
3. `favorites/FavoriteControlMessage.kt`: dejar de enviar la clave Nostr (`npub`) en las notificaciones de favorito, manteniendo el formato del paquete compatible.
4. `services/ContactIdentityResolver.kt`, `ContactDirectory.kt`, `ui/ConversationSummary.kt`, `ui/UnreadConversationSummary.kt`, `ui/MeshPeerListSheet.kt`: quitar el campo `nostrPubkey`.

### Paso 4 — Borrar Nostr, canales geohash y globo

1. Borrar `nostr/` entero, lo que queda de `geohash/`, `ui/globe/`, y en `ui/`: `GeohashViewModel.kt`, `GeohashPickerActivity.kt`, `GeohashPeopleList.kt`, `LocationChannelsSheet.kt`, `LocationNotesSheet.kt`, `LocationNotesSheetPresenter.kt`, `LocationNotesButton.kt`.
2. Borrar `assets/nostr_relays.csv` y `assets/world_*.geojson`.
3. Borrar `util/GitHubReleaseClient.kt`, `ui/ApkDownloadViewModel.kt` y `net/OkHttpProvider.kt`; simplificar `util/UniversalApkManager.kt` y `ApkRateLimitStore.kt` para que solo compartan el APK instalado (sin descargar).
4. Retocar los ficheros que quedan con referencias: `ChatViewModel.kt` (la parte más grande: quitar `geohashViewModel` y el canal seleccionado por ubicación), `ChatScreen.kt`, `ChatHeader.kt`, `ChatState.kt`, `CommandProcessor.kt`, `MessageManager.kt`, `MessageInteractionUtils.kt`, `ChatUserSheet.kt`, `MeshDelegateHandler.kt`, `VerificationHandler.kt`, `DataManager.kt`, `ChannelManager.kt`, `MainActivity.kt` (notificaciones que abren un chat de geohash y la preferencia de prueba de trabajo).
5. Borrar los tests de `app/src/test/.../nostr/` y `.../geohash/` (30 ficheros de test referencian esos paquetes; los que prueban la malla se ajustan, no se borran).

### Paso 5 — Quitar dependencias y permisos de red

1. `app/build.gradle.kts` y `gradle/libs.versions.toml`: quitar `okhttp`, `tor-android-binary` y `okhttp-mockwebserver`. **No** quitar `libs.bundles.cryptography` (BouncyCastle y Tink): además de Nostr lo usan `noise/NoiseEncryptionService.kt` y `crypto/EncryptionService.kt`.
2. `AndroidManifest.xml`: quitar `INTERNET`, `ACCESS_NETWORK_STATE` y `CHANGE_NETWORK_STATE`. **Ojo:** el servidor del hotspot (`ApkWebServer`, NanoHTTPD) usa sockets locales y en Android eso también requiere `INTERNET`. Opciones: mantener el permiso solo por el hotspot, o quitar el hotspot. Recomendación: mantenerlo y documentar que la app no hace ninguna conexión a internet (se puede verificar con la herramienta de análisis de red de Android Studio).
3. `play-services-location`: se mantiene por ahora (GPS de bajo consumo para la fase 4). Si se quiere publicar en F-Droid, habrá que usar solo `SystemLocationProvider` en esa variante.

### Paso 6 — Nombre, icono e identificador propios

**Nombre elegido: Faro.** Lema: «Te guía hasta los tuyos en la oscuridad». Antes de publicar, comprobar que no choca con otra app en Google Play y F-Droid ni con una marca registrada en la OEPM; si choca, usar una variante como «Faro Familia».

1. `res/values/strings.xml`: `app_name` = `Faro`. Hay 35 carpetas de traducciones (`values-*`) con el nombre «bitchat» en textos: buscar y sustituir.
2. Icono: `res/mipmap-*` e `ic_launcher.xml` / `ic_launcher_round.xml`. Idea: un faro sencillo con un haz de luz, en blanco sobre negro (encaja con el tema oscuro OLED).
3. `applicationId` en `app/build.gradle.kts` (hoy `com.bitchat.droid`): cambiarlo para que la app conviva con Bitchat en el mismo móvil. Propuesta: `io.github.fcojalvarez.faro` (formato habitual en F-Droid; no se puede cambiar después de publicar).
4. El paquete Kotlin `com.bitchat.android` se puede dejar como está: renombrarlo toca los 290 ficheros y complica traer mejoras del proyecto original. No se ve desde fuera.
5. Revisar `README.md`, `PRIVACY_POLICY.md`, `fastlane/metadata` y la pantalla «Acerca de»: créditos a bitchat (obligatorio por la GPL) y nombre nuevo.

### Paso 7 — Prueba de la fase

Dos móviles en modo avión con Bluetooth activado: mensaje público, mensaje privado y mensaje con un tercer móvil en medio (reenvío). Ver la sección «Listo cuando» de `docs/PLAN.md`.

## Respuesta a la pregunta abierta del plan

> ¿Los clientes oficiales de Bitchat reenvían tipos de paquete que no conocen?

**Sí, con condiciones** (comprobado en `mesh/PacketProcessor.kt`, `mesh/PacketRelayManager.kt` y `protocol/PacketPayloadLimits.kt`):

- Un paquete de **difusión** (sin destinatario) de tipo desconocido se acepta y **se reenvía** mientras le quede TTL.
- Un paquete de tipo desconocido **dirigido a un móvil concreto** se descarta si va para él, y se reenvía si va para otro.
- El tamaño máximo aceptado para tipos desconocidos es **128 KB**, de sobra para posiciones (16 bytes) y mensajes de grupo.
- Los tipos desconocidos no pasan la verificación de firma de los tipos públicos (solo se exige a ANNOUNCE, MESSAGE, FILE_TRANSFER, VOICE_FRAME y LEAVE), así que no se rechazan por eso.

**Consecuencia para la fase 2:** los paquetes de grupo y de posición pueden viajar por móviles con Bitchat normal, lo que amplía mucho la malla. Pero hay que **elegir números de tipo que Bitchat no use**: ya tiene reservados, entre otros, `0x25` (sus propios mensajes de grupo), sobres de mensajería, carrier de Nostr, tablón, prekeys y ping/pong. Antes de la fase 2 hay que revisar la lista completa en `protocol/BinaryProtocol.kt` y `PacketPayloadLimits.kt` (y en el repositorio de iOS) y elegir valores libres.

## Riesgos de esta fase

- **`ChatViewModel.kt` y `MessageRouter.kt`** concentran la lógica: son donde es más fácil romper algo. Hacerlos en commits separados.
- **Compatibilidad con Bitchat:** no hay que tocar el formato de paquetes de la malla en esta fase, para seguir pudiendo usar sus móviles como repetidores.
- **Traer mejoras del original:** cuanto más se borre, más conflictos al hacer `git merge upstream/main`. Por eso es mejor borrar ficheros enteros que editar a medias, y no renombrar el paquete Kotlin.

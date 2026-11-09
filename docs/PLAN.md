# Faro — plan técnico: app de comunicación offline para emergencias

**Nombre: Faro.** Lema: «Te guía hasta los tuyos en la oscuridad».

## Objetivo y alcance

Una app Android, gratuita y sin servidores, para mantener el contacto en un apagón o catástrofe: mensajes por Bluetooth en malla, grupos y subgrupos con invitación aceptada, y ubicación de los miembros con brújula y mapa offline.

- **Primera versión: solo Android.** iOS limita el Bluetooth en segundo plano y eso rompe el envío automático de ubicación.
- **Sin internet en ningún momento** salvo para instalar la app y descargar el mapa antes de la emergencia.
- **Única excepción: actualizaciones.** Si hay Wi‑Fi, la app comprueba como mucho una vez al día si hay versión nueva en el repositorio público `fcojalvarez/faro-releases`, la descarga, comprueba la huella y la firma, y avisa para instalarla. Nunca se usa internet para mensajes, ubicaciones ni otros datos. Las actualizaciones también se pueden pasar de móvil a móvil sin internet.
- **Prioridad absoluta: batería.** Cada decisión técnica se mide por cuánto consume.
- Fuera de alcance de la v1: llamadas de voz, fotos, iOS y publicación en tiendas.

## Base de código: fork de Bitchat Android

Partimos de [bitchat-android](https://github.com/permissionlesstech/bitchat-android), en Kotlin, importado con su historial completo (commit de partida `14ab73c`, noviembre de 2026).

**Licencia: GPL v3.** bitchat-android fue MIT hasta el 28 de febrero de 2026 (commit `fb2bb64`) y desde entonces es GPL v3. El repositorio de dominio público (Unlicense) es el de iOS, no este. Elegimos la versión actual por rendimiento, batería y alcance (enrutado por origen, Wi‑Fi Aware y mejoras de rendimiento posteriores a febrero). Consecuencias:

- La app debe distribuirse bajo GPL v3 y con su código fuente disponible, algo que encaja con una app gratuita publicada en F-Droid.
- Hay que conservar los avisos de copyright y el fichero `LICENSE.md`.
- Las dependencias que añadamos deben ser compatibles con GPL v3.

Ya resuelve lo más difícil, que no hay que reinventar:

- **Malla Bluetooth LE**: descubrimiento de dispositivos y reenvío en varios saltos (hasta 7).
- **Cifrado**: sesiones Noise (patrón XX) con secreto hacia adelante entre pares.
- **Paquetes binarios compactos** con fragmentación, TTL y deduplicación.
- **Ahorro de batería**: escaneo por ciclos y límite de conexiones; servicio en primer plano para que Android no mate la malla.
- **Borrado de emergencia** de todos los datos.

Lo que **quitamos o desactivamos**: el transporte por internet (Nostr), los canales por geohash y Tor. Menos código, menos consumo y ninguna dependencia de red.

La app necesita **nombre e icono propios**: la licencia cubre el código, no la marca Bitchat. Fuente técnica: el [whitepaper del protocolo](https://github.com/permissionlesstech/bitchat/blob/main/WHITEPAPER.md).

## Grupos y subgrupos

Cada grupo tiene su propia clave simétrica: solo quien la tiene lee los mensajes, pero cualquier móvil de la malla los reenvía sin poder descifrarlos. Así se evita el caos de un canal general con cientos de personas.

| Tipo | Quién entra | Ubicación | Ejemplo |
|---|---|---|---|
| Privado | Solo por invitación | Compartida por defecto (cada uno puede pausarla) | Familia |
| Público | Visible para los cercanos; se pide unirse | Solo si cada persona lo permite | Vecinos del bloque |
| Subgrupo | Miembros del grupo padre, por invitación | Hereda la regla del padre | Familia > Abuelos |

**Modelo de datos (local, en el móvil):**

- `Group`: id (16 bytes aleatorios), nombre, tipo, clave de grupo, id del grupo padre (opcional), versión de clave.
- `Member`: huella de la clave pública, alias, rol (admin o miembro), permiso de ubicación y caducidad.
- `Invite`: id, grupo, quién invita, estado (pendiente, aceptada, rechazada, caducada).

**En la malla**, cada paquete de grupo lleva una etiqueta corta (8 bytes) derivada del id de grupo y el contenido cifrado con ChaCha20-Poly1305. Los móviles que no son del grupo solo ven la etiqueta y reenvían.

**Al expulsar a alguien** el admin genera una clave nueva y la envía a cada miembro restante por su sesión Noise individual. Un subgrupo tiene su propia clave: estar en el padre no da acceso a él.

## Invitaciones: QR automático, enlace con aceptación

Escanear un QR en persona ya es el consentimiento, así que la entrada es inmediata; una invitación a distancia nunca da acceso hasta que el invitado la acepta.

**Por QR (en persona):**

1. El admin muestra un QR con id de grupo, nombre, su huella pública y la clave del grupo, firmado con su clave Ed25519.
2. El invitado lo escanea, la app comprueba la firma y se une al momento.
3. La app avisa al grupo de que hay un miembro nuevo.

**Por enlace o invitación desde la app:**

1. El enlace solo lleva id de grupo, nombre, huella de quien invita y un token de un solo uso con caducidad. **Nunca la clave.**
2. Al abrirlo, el invitado ve quién le invita y a qué grupo, y elige Aceptar o Rechazar.
3. Si acepta, su app envía la aceptación por la malla hasta quien invitó.
4. Quien invitó le manda la clave del grupo cifrada solo para él (patrón Noise X) y queda dentro.

El enlace se puede compartir antes de la emergencia por WhatsApp o en la propia app; la aceptación y la entrega de la clave funcionan sin internet. Si el invitado nunca acepta, el token caduca y no pasa nada.

## Ubicación, brújula y mapa offline

El GPS funciona sin red; la app solo tiene que enviar la posición en un paquete diminuto y dibujar distancia y dirección hacia cada miembro.

**Permisos:**

- **Grupo privado:** se comparte por defecto; cada miembro puede pausarlo y ve siempre si lo está compartiendo.
- **Grupo público:** desactivado por defecto. Cada persona lo activa explícitamente, por grupo y con caducidad (1 hora, 24 horas o hasta desactivarlo).
- Se puede revocar en cualquier momento y los demás dejan de recibir posiciones nuevas.

**Paquete de posición (unos 16 bytes antes de cifrar):** latitud y longitud como enteros de 32 bits (grados × 10^7), precisión en metros (1 byte), marca de tiempo (4 bytes), batería en % (1 byte) y estado (1 byte: bien, necesito ayuda, en camino). Va cifrado con la clave del grupo.

**Reenvío inteligente:** cada móvil guarda solo la última posición de cada miembro y descarta las antiguas de su cola. Una posición vieja nunca ocupa la malla.

**Pantalla de brújula:** una flecha por miembro que apunta hacia él, con la distancia y hace cuánto se recibió la posición ("hace 4 min"). El rumbo se calcula con la fórmula de rumbo inicial y se corrige con la orientación del móvil (sensor de vector de rotación):

```
θ = atan2( sin Δλ · cos φ2 , cos φ1 · sin φ2 − sin φ1 · cos φ2 · cos Δλ )
```

La distancia, con la fórmula de haversine; a escala de ciudad sobra precisión.

**Mapa offline:** MapLibre Native con un extracto de OpenStreetMap de la provincia de Málaga en un único fichero PMTiles, descargado antes de la emergencia. Tema oscuro para ahorrar pantalla OLED.

**Botón SOS:** envía al instante posición y estado "necesito ayuda" a todos tus grupos privados, aunque tengas la ubicación pausada.

## Eficiencia energética

La app tiene tres modos y cambia sola al más ahorrador según la batería que quede; el usuario puede fijarlo a mano.

| Modo | Cuándo | Envío de posición | Escaneo Bluetooth |
|---|---|---|---|
| Normal | Batería > 50 % | Cada 5 min o al moverse > 100 m | Ciclos estándar de Bitchat |
| Ahorro | 20–50 % | Cada 15 min | Ciclos más largos |
| Crítico | < 20 % | Solo bajo petición o con SOS | Mínimo: solo para recibir |

Las cifras son de partida y hay que ajustarlas midiendo en móviles reales.

**Reglas que se aplican siempre:**

- **GPS encendido solo lo justo**: se pide una posición, se obtiene y se apaga. Nunca seguimiento continuo.
- **Detección de movimiento** con el acelerómetro (bajo consumo): si estás quieto, no se repite el GPS.
- **"Pedir ubicación"**: un miembro puede solicitar tu posición actual en lugar de que la envíes sin parar.
- **Pantalla**: tema oscuro puro (negro OLED), sin animaciones, y la brújula se actualiza solo con la pantalla encendida.
- **Paquetes mínimos** y TTL más bajo para posiciones que para mensajes de texto.
- **Sin trabajo en segundo plano innecesario**: nada de sincronizaciones ni analíticas. La comprobación de actualizaciones es la única tarea periódica: una vez al día, solo con Wi‑Fi y batería suficiente.

## Fases de desarrollo

Cinco fases, cada una termina con una prueba en móviles reales antes de pasar a la siguiente.

1. **Fork y limpieza.** Compilar el proyecto, quitar Nostr, Tor y geohash, poner nombre (Faro) e icono propios.
   - Listo cuando: dos móviles en modo avión se envían mensajes.
2. **Grupos y subgrupos.** Modelo de datos, claves de grupo, etiqueta de grupo en los paquetes, roles y rotación de clave.
   - Listo cuando: un tercer móvil sin la clave reenvía los mensajes pero no puede leerlos.
3. **Invitaciones.** QR firmado con unión inmediata; enlace con token, aceptación y entrega de clave por Noise X.
   - Listo cuando: una invitación por enlace no da acceso hasta pulsar Aceptar.
4. **Ubicación y brújula.** Paquete de posición, permisos por grupo, pantalla de brújula, mapa offline y SOS.
   - Listo cuando: andando por la calle, la flecha apunta bien al otro móvil.
5. **Batería.** Modos automáticos, detección de movimiento y medición real del consumo.
   - Listo cuando: un móvil con la app activa aguanta al menos 24 horas en modo ahorro (objetivo a validar).

Después: probarlo con la familia instalando el APK y, si funciona, publicarlo en F-Droid o Google Play.

## Cómo trabajar con Claude Code

**Necesitas:** Android Studio (SDK API 26 o superior), Claude Code y al menos dos móviles Android reales; mejor tres, para probar el reenvío. El emulador no sirve para Bluetooth.

```bash
git clone https://github.com/fcojalvarez/survivalcontact.git
cd survivalcontact
./gradlew assembleDebug
```

- Este plan vive en `docs/PLAN.md` y `CLAUDE.md` en la raíz recoge las reglas del proyecto.
- Al terminar cada fase, prueba en los móviles y haz commit antes de pedir la siguiente.
- Para traer mejoras del proyecto original: `git remote add upstream https://github.com/permissionlesstech/bitchat-android.git`, `git fetch upstream` y `git merge upstream/main`.

**Consejo:** pide a Claude Code tests unitarios para el formato de paquetes y la lógica de grupos; la parte de radio solo se valida con móviles reales.

## Riesgos y limitaciones

El mayor límite es físico: sin suficientes móviles con la app entre dos personas, el mensaje no llega, por bien hecha que esté.

| Riesgo | Impacto | Cómo mitigarlo |
|---|---|---|
| Alcance Bluetooth de decenas de metros por salto | Familias en barrios distintos no se conectan | Repetidores fijos con móviles viejos enchufados; en el futuro, soporte para nodos LoRa (Meshtastic) |
| Pocos usuarios cerca | Malla con huecos | Compatibilidad con Bitchat para usar sus usuarios como repetidores (por comprobar) |
| Android mata servicios en segundo plano | Se cae la malla | Servicio en primer plano y pedir excluir la app de la optimización de batería |
| Ubicación en grupos públicos | Riesgo de privacidad | Permiso explícito por grupo, con caducidad y revocable |
| Consumo real desconocido | La app agota la batería | Fase 5 dedicada a medir antes de publicar |
| Cifrado propio de grupos sin auditar | Fallos de seguridad | Usar solo primitivas ya presentes (ChaCha20-Poly1305, Noise) y no inventar criptografía |

**Pregunta abierta:** si los clientes oficiales de Bitchat reenvían tipos de paquete que no conocen. Si lo hacen, los nuevos paquetes de grupo y posición viajarían también por sus móviles; hay que verificarlo en el código del relay antes de la fase 2.

Al no haber servidores, los datos (ubicaciones incluidas) nunca salen de los móviles, lo que simplifica mucho la privacidad y el RGPD si se publica.

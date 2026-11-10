# Protocolo de Faro

Especificación de los mensajes propios de Faro, para que cualquier otra implementación se entienda
con la app de Android. Todo va encima de la malla de bitchat (Bluetooth) y, si hay internet, por
Nostr. Los números van en big-endian. Los textos, en UTF-8.

Solo se usan primitivas ya presentes en el proyecto: XChaCha20-Poly1305 (Tink), Ed25519 y X25519
(BouncyCastle) y SHA-256.

## 1. Transporte

| Vía | Cómo |
|---|---|
| Malla (Bluetooth) | Paquete de bitchat de tipo `0xFA`; la carga útil es el paquete de Faro tal cual. |
| Internet (Nostr) | Evento de tipo `4545`, contenido = paquete de Faro en Base64 (estándar, sin saltos), etiqueta `["f", <etiqueta en hex>]`. Cada evento se firma con una identidad Nostr desechable. Servidores: `relay.damus.io` y `relay.primal.net`. |

Al recibir por Nostr se pide lo de las últimas 12 h. Se descartan los paquetes repetidos
(grupo, autor, hora y tipo) y los de más de 12 h o más de 10 min en el futuro.

## 2. Grupos

Cada grupo tiene un id de 16 bytes y una clave de 32 bytes, ambos aleatorios.

**Etiqueta diaria** (8 bytes): `SHA-256("faro-group-tag-v2" ‖ clave ‖ día)` truncado a 8 bytes,
donde `día` = días desde 1970 en UTC (`floor(ms / 86 400 000)`) como entero de 8 bytes.
Se envía con la de hoy y se aceptan las de ayer, hoy y mañana.

### Paquete de grupo

```
"FR" (2) | versión = 1 (1) | etiqueta (8) | sellado
sellado = XChaCha20-Poly1305(clave del grupo, aad = las 11 bytes de cabecera)
        = nonce (24) | cifrado | tag (16)            (formato de Tink)
claro   = tipo (1) | hora en ms (8) | clave Ed25519 del autor (32)
          | largo del nombre (1) | nombre (≤ 48) | cuerpo | firma Ed25519 (64)
```

La firma cubre todo el texto en claro salvo la propia firma. Se rechaza si no verifica.

### Tipos y cuerpos

| Tipo | Nombre | Cuerpo |
|---|---|---|
| 1 | TEXT | Mensaje en UTF-8 (≤ 2000 caracteres). |
| 2 | LOCATION | Posición (12 bytes, ver abajo). |
| 3 | HELLO | Vacío. Lo manda quien acaba de entrar. |
| 4 | FOLLOW | Clave de la persona (32) + minutos (1, de 0 a 10; 0 = parar). |
| 5 | SEEN | Cómo (1: 0 mapa, 1 ficha, 2 en directo, 3 brújula) + claves vistas (32 cada una, máx. 50). **Solo por internet.** No se envía sin conexión ni si alguien ha pedido ayuda. Como mucho uno por persona cada 15 min. |
| 6 | CHECK | Tipo (1: 0 «estoy bien», 1 «¿estás bien?», 2 respuesta bien, 3 respuesta necesito ayuda) + clave destino (32; ceros = para todos). |
| 7 | PLACE | Borrar (1) + id (8) + lat (8, double) + lon (8, double) + radio en m (2, 50–2000) + largo (1) + nombre (≤ 40). |
| 8 | ARRIVE | Id del lugar (8) + 1 si llega / 0 si sale (1). |
| 9 | TRIP | Activo (1) + modo (1: 0 andando, 1 coche, 2 bus) + lat (8) + lon (8) + llegada en ms (8) + largo (1) + destino (≤ 40). Activo = 0 marca el fin del viaje. |

**Posición (12 bytes):** lat × 10⁷ (4, entero) | lon × 10⁷ (4) | precisión en m (2) |
batería % (1) | estado (1: 0 bien, 1 necesita ayuda, 2 en camino).

## 3. Entrar en un grupo (enlace o QR)

El enlace y el QR son lo mismo: `https://fcojalvarez.github.io/survivalcontact/unirse/#<código>`
(desde la web se abre la app con `faro://unirse?c=<código>`). El código es Base64 URL sin relleno de:

```
versión = 1 (1) | largo + nombre del grupo (≤ 48) | largo + nombre de quien invita (≤ 32)
| nº de miembros (1, ≤ 12) | por cada uno: largo + nombre (≤ 24)
| clave Ed25519 de quien invita (32) | token (16) | clave X25519 de la invitación (32)
| caducidad en s (8) | firma Ed25519 de todo lo anterior (64)
```

No contiene la clave del grupo. Sirve una vez y caduca a los 7 días.

**Petición** (de quien quiere entrar), etiqueta `SHA-256("faro-join-req-tag" ‖ token)[0..8]`:

```
"FJ" | 1 | etiqueta (8) | clave X25519 efímera (32) | sellado
secreto = X25519(efímera, clave de la invitación)
sellado = XChaCha20-Poly1305(SHA-256("faro-join-req-v1" ‖ secreto ‖ token), aad = cabecera)
claro   = clave Ed25519 de quien pide (32) | largo + nombre (≤ 32)
```

**Respuesta** (solo si el administrador acepta), etiqueta `SHA-256("faro-join-reply-tag" ‖ token)[0..8]`:

```
"FK" | 1 | etiqueta (8) | sellado
sellado = XChaCha20-Poly1305(SHA-256("faro-join-reply-v1" ‖ secreto ‖ token), aad = cabecera)
claro   = id del grupo (16) | clave del grupo (32) | largo + nombre del grupo (≤ 48)
```

Como mucho 5 peticiones pendientes por grupo.

## 4. Pendiente

- Expulsar o salir de un grupo no cambia todavía la clave: quien sale podría seguir leyendo.
  Hará falta un tipo nuevo para repartir una clave nueva a quien se queda.

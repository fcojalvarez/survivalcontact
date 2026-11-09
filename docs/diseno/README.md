# Rediseño de Faro

Decisiones tomadas pantalla a pantalla. Las imágenes son maquetas; se aplicarán a la app cuando estén todas aprobadas.

## Principios

- Fácil para cualquier edad (de 10 a 65+), intuitiva y minimalista.
- Fondo negro, acento azul calma `#A7C7E7`, rojo `#FF6B5E` solo para pedir ayuda.
- Bordes en lugar de rellenos; letra Inter; sin jerga técnica.
- Nada de iconos pixelados. Ajustes con un engranaje claro arriba a la derecha.
- El nombre propio se cambia en Ajustes, no tocándolo en la cabecera.
- Solo grupos privados (sin subgrupos). Se entra con enlace o QR y el administrador (quien creó el grupo) acepta o rechaza.
- Cada grupo es independiente: quien está en dos grupos ve a los de ambos, pero los de un grupo no ven a los del otro.

## 1. Pantalla principal (aprobada)

![Pantalla principal](01-principal-definitiva.png)

- **Cabecera:** logo y «Faro», etiqueta de conexión («● Sin internet») y engranaje de ajustes.
- **Personas:** todas las de todos tus grupos en una fila de caras con aro de estado (verde bien, naranja sin noticias), nombre y distancia o tiempo. Debajo, la leyenda de colores de cada grupo y «Gestionar».
- **Importantes** (se quedan hasta que actúes; sin texto explicativo en la cabecera del bloque): petición de ayuda, alguien sin señales, solicitud para entrar en tu grupo (con «Sí, dejarle entrar» / «No» en la propia tarjeta; solo para el administrador).
- **Novedades** (desaparecen a las 24 h; los mensajes siguen en su chat).
- Cada tarjeta lleva a la izquierda el color de su grupo. El tiempo va debajo del mensaje para dejarle todo el ancho.
- **No leído:** texto en blanco intenso y hora en azul; lo leído, en gris. Sin puntos (parecen estado «conectado»).
- La flecha ⌄ despliega con animación el mensaje completo y sus acciones (Responder, Leído, Descartar; Ver en el mapa; Llamar…).
- **Pedir ayuda:** botón (no deslizar) con confirmación antes de enviar.
- **Barra inferior:** Inicio · Chats · Mapa, compacta (unos 48 dp de alto: icono de 20 y texto de 11), suficiente para tocar con comodidad.

## 2. Chats (aprobada)

![Chats](02-chats.png)

- Título «Chats» con lupa (buscar) y «＋» (crear grupo, unirse con enlace o QR).
- Tres pestañas: **Grupos** (privados, con candado y el color del grupo), **Personas** (chats privados uno a uno) y **Cerca**.
- **Cerca** es una sola sala abierta, «Gente cerca», con quien esté al alcance de la malla (sin internet). Muestra cuántas personas hay y quiénes, y avisa de que lo escrito ahí lo lee cualquiera cerca. La pestaña muestra el número de personas al alcance («Cerca · 4»).
- **Se quitan los canales públicos por zona (geohash):** solo funcionaban con internet, justo cuando no hacen falta, y exponían la IP y la zona. Internet queda para grupos y chats privados, ubicación de la familia y actualizaciones.

### Cerca y sala «Gente cerca» (aprobada)

![Cerca y sala](03-cerca-y-sala.png)

- El aviso de que es público va abajo, pegado a la barra de navegación.
- Dentro de la sala: nombre y **distancia** de cada persona sobre su mensaje; botón para ver la lista de personas y escribir en privado; recordatorio «Visible para cualquiera que esté cerca» bajo el campo de escribir.

## 3. Conversación (aprobada)

![Conversación](04-conversacion.png)

- Cabecera: volver, foto y nombre, botón de mapa y menú ⋮. En grupos, «N personas»; en privados, si está cerca y a qué distancia (en verde).
- Burbujas con borde (los demás a la izquierda en blanco, tú a la derecha en azul calma), nombre en color sobre cada mensaje en grupos, ✓ enviado y ✓✓ leído, avisos del grupo en gris centrados, ubicación compartida como tarjeta con mini mapa.
- Abajo: «＋» (foto, ubicación, nota de voz), campo de texto y enviar; línea «🔒 Cifrado · llega por la red cercana / por internet».
- **Sin mensajes predefinidos** (ni aquí ni en «Gente cerca»).
- Grupos de cualquier tamaño, con un máximo propuesto de 50 personas para no saturar la red cercana. En Inicio, si no caben las caras, «+N».

![Menú, información y mapa](05-menu-info-mapa.png)

- **Menú ⋮:** se despliega desde el propio botón (arriba a la derecha), no desde abajo. Grupo: Miembros e información, Invitar, Compartir ubicación (interruptor), Silenciar, Buscar, Vaciar el chat y, separado en rojo, Salir del grupo. Privado: Silenciar, Buscar, Verificar identidad, Vaciar el chat y, en rojo, Bloquear.
- **Tocar la cabecera del grupo:** información con foto, nombre, «N personas · creado por…», botones Invitar / Ver en el mapa / Silenciar y lista de miembros con estado y distancia. Solo el administrador cambia nombre y color (lápiz) y expulsa (mantener pulsado); al expulsar, el grupo cambia de clave.
- **Mapa desde un chat privado:** centrado en esa persona, con línea entre ambos, distancia, dirección, hace cuánto y batería; «Abrir chat» y «Guiarme» (brújula a pantalla completa). Solo aparece si esa persona comparte su ubicación contigo en algún grupo.

## 4. Mapa (aprobada)

![Mapa](06-mapa.png)

- Mapa grande con todas las personas de tus grupos; aro del color de su grupo (naranja si no hay noticias) y nombre.
- Filtros arriba: Todos / cada grupo con su color. Etiqueta de estado («Mapa guardado · sin internet»).
- Botón para centrarte en ti.
- Abajo, lista deslizable (se sube con el dedo) con nombre, grupo, hace cuánto, distancia y dirección.
- Tocar a alguien: ficha con distancia, dirección, «Abrir chat» y «Guiarme» (brújula a pantalla completa).

## 5. Ajustes (aprobada)

![Ajustes](07-ajustes.png)

- Perfil arriba (inicial, nombre, «Así te ven los demás», «Cambiar»): aquí se cambia el nombre.
- «Tu ubicación»: un interruptor por grupo, a la vista.
- General, con el estado a la derecha: Avisos, Ahorro de batería, Mapa sin conexión (zona y tamaño), Compartir Faro, Actualizaciones, Privacidad y seguridad, Idioma.
- Pie: versión y «código abierto».

## Pendientes de diseñar

Crear grupo, invitar (enlace/QR), aceptar invitación, bienvenida y permisos.

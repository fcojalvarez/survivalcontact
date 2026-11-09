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

## Pendientes de diseñar

Conversación, Mapa, Ajustes, crear grupo, invitar (enlace/QR), aceptar invitación, bienvenida y permisos.

<img width="160" height="160" alt="Icono de Faro: un faro blanco con dos haces de luz azul calma sobre fondo negro" src="fastlane/metadata/android/en-US/images/icon.png" />

# Faro

**Te guía hasta los tuyos en la oscuridad.**

Faro es una app Android gratuita para mantener el contacto con tu familia y tus vecinos cuando no hay cobertura ni internet: un apagón, una DANA, un terremoto. Los mensajes saltan de móvil en móvil por Bluetooth hasta llegar a su destino, sin cuentas y sin número de teléfono. Si hay internet, también puede usarlo, siempre cifrado de extremo a extremo.

> **Estado: en desarrollo.** Hay una [versión de prueba](https://github.com/fcojalvarez/survivalcontact/releases/tag/prueba) para Android. El plan y las fases están en [`docs/PLAN.md`](docs/PLAN.md).

## Qué hace

- **Mensajes sin internet.** Cada móvil con Faro hace de repetidor: tu mensaje puede dar hasta 7 saltos para llegar más lejos.
- **Grupos privados y públicos.** «Familia», «Vecinos del bloque», y subgrupos como «Familia > Abuelos». Solo los miembros leen los mensajes; los demás móviles los reenvían sin poder abrirlos.
- **Invitaciones seguras.** En persona, escaneando un QR. A distancia, con un enlace que solo da acceso cuando el invitado lo acepta.
- **Dónde están los tuyos.** Una brújula que apunta a cada miembro del grupo, con la distancia, y un mapa que funciona sin conexión.
- **Botón SOS.** Envía tu posición y «necesito ayuda» a todos tus grupos con un toque.
- **Pensada para durar.** La batería es la prioridad: la app reduce su consumo sola según la carga que te quede.
- **Con internet, cuando lo hay.** Si no hay nadie cerca por Bluetooth, los mismos chats siguen funcionando por internet a través de Nostr, una red de servidores públicos que nadie controla. La app avisa cuando estás sin internet.
- **Privada por diseño.** Sin cuentas ni servidores propios. Todo va cifrado de extremo a extremo: ni los servidores de Nostr pueden leer tus mensajes ni tu ubicación, que solo se comparte en grupos privados y con tu permiso.
- **Código abierto.** Cualquiera puede revisar cómo funciona.

## Cómo funciona

Faro forma una **red en malla** con los móviles cercanos usando Bluetooth de bajo consumo (y Wi‑Fi Aware en los móviles que lo admiten). Cuantas más personas la tengan instalada a tu alrededor, más lejos llegan los mensajes.

Todo va cifrado: las conversaciones entre dos personas usan el protocolo Noise, y cada grupo tiene su propia clave.

**Límite importante:** cada salto de Bluetooth alcanza decenas de metros. Si entre dos personas no hay suficientes móviles con la app, el mensaje no llega. Por eso conviene instalarla con antelación en la familia y el vecindario. Faro puede compartirse de móvil a móvil por Wi‑Fi, sin tienda ni internet.

## Compilar

Necesitas Android Studio (SDK API 26 o superior) y, para probar de verdad, al menos dos móviles Android reales: el emulador no tiene Bluetooth.

```bash
git clone https://github.com/fcojalvarez/survivalcontact.git
cd survivalcontact
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

## Créditos y licencia

Faro es un fork de [**bitchat-android**](https://github.com/permissionlesstech/bitchat-android), creado por [permissionlesstech](https://github.com/permissionlesstech) y sus colaboradores. La malla Bluetooth, el cifrado Noise y el formato de paquetes son obra suya. Faro no está afiliado al proyecto bitchat ni avalado por él.

Se distribuye bajo la **GNU General Public License v3**, igual que el proyecto original: puedes usarlo, estudiarlo, modificarlo y compartirlo, siempre que las versiones que distribuyas sigan siendo GPL v3 y con su código fuente disponible. Consulta [`LICENSE.md`](LICENSE.md).

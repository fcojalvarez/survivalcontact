# Pruebas con móviles reales

La parte de Bluetooth no se puede probar en un emulador. Esta es la lista para comprobar Faro en
móviles de verdad antes de repartirla. Apunta en cada punto ✅, ❌ o lo que hayas visto.

**Necesitas:** 2 móviles como mínimo (mejor 3), todos con la última versión de prueba
(`faro-prueba-arm64.apk`). Sin la misma versión no se entienden.

## 1. Preparación

1. Instala Faro en todos y completa la bienvenida (nombre y permisos).
2. En el móvil A crea el grupo «Prueba». Invita al B con el QR y al C con «Enviar enlace…».
3. ✅ si: A recibe «quiere entrar» de B y de C, los acepta, y los tres ven el grupo con 3 personas.

## 2. Con internet

| # | Qué hacer | Qué debe pasar |
|---|---|---|
| 2.1 | Escribir en el chat del grupo desde cada móvil | Llega a los otros dos en segundos. |
| 2.2 | Abrir el Mapa en A | Salen B y C con distancia; B y C reciben «A ha visto dónde estás». |
| 2.3 | En A, ficha de B › «Seguir en directo» | B ve «A te está siguiendo»; el punto de B se mueve cada 30 s. |
| 2.4 | En A, ficha de B › «Preguntar si está bien»; B contesta desde la notificación | A recibe «B: Estoy bien». |
| 2.5 | En C, «Estoy bien» en Inicio | A y B lo ven en Novedades. |
| 2.6 | En A, Lugares › añadir «Casa» donde está B; B sale y vuelve a entrar | A y C reciben «B ha salido de Casa» / «ha llegado» (puede tardar hasta 5–15 min). |
| 2.7 | En B, Mapa › «Voy para allá» a «Casa» | A ve «B va para Casa · llega HH:MM»; al llegar se apaga solo. |

## 3. Sin internet (lo más importante)

Pon los tres en **modo avión con el Bluetooth encendido** (y la ubicación activada).

| # | Qué hacer | Qué debe pasar |
|---|---|---|
| 3.1 | Cerrar Faro en todos y esperar 1–2 min | Aparece la notificación «Red cercana activa». Si sale «Sin internet: toca para activar», anota el modelo del móvil. |
| 3.2 | Con la app **cerrada**, escribir desde A (abrir, escribir, cerrar) | B y C lo reciben (con la app cerrada puede tardar hasta 1 min). |
| 3.3 | Con la app **abierta** en todos | Los mensajes llegan en segundos. |
| 3.4 | A pulsa «Pedir ayuda» | B y C reciben el aviso con sonido y ven a A en el mapa (mapa guardado de la zona). |
| 3.5 | Con A pidiendo ayuda y apps cerradas | Los avisos llegan en ~15 s (modo emergencia). |
| 3.6 | Gente cerca: entrar en la sala y escribir | Lo ven todos los que estén cerca, aunque no sean del grupo. |
| 3.7 | **Saltos:** A y C lejos (que no se vean), B en medio | Un mensaje de A llega a C pasando por B. |
| 3.8 | **Alcance:** alejarse con B mientras A escribe | Anota a cuántos metros deja de llegar (en interior y en la calle). |
| 3.9 | Quitar el modo avión | La notificación «Red cercana» desaparece en un rato. |

## 4. Batería

Con el móvil al 100 %, sin cargar y con la pantalla apagada, apunta cuánto baja en cada caso:

| Caso | Duración | % gastado |
|---|---|---|
| Con internet, Faro instalado (red cercana automática) | 8 h | |
| Sin internet (modo avión + Bluetooth), app cerrada | 8 h | |
| Pidiendo ayuda, app cerrada, empezando al 50 % | 2 h | |
| Igual pero sin Faro (para comparar) | 8 h | |

Pásame los números: con ellos ajusto los ritmos de Bluetooth y de ubicación.

## 5. Actualización

1. Con una versión instalada, publica una nueva (cualquier cambio en la rama).
2. ✅ si en menos de un día (con Wi‑Fi) llega el aviso de versión nueva y se instala encima sin perder grupos ni mensajes. En Ajustes › Actualizaciones › «Buscar ahora» se fuerza.

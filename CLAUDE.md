# Faro (repositorio survivalcontact)

App Android de comunicación offline para emergencias, basada en un fork de bitchat-android.

- **Responde siempre en español**, también en mensajes de commit y documentación nueva.
- Lee `docs/PLAN.md` antes de empezar: es el plan técnico y define las fases.
- Trabajamos fase a fase; no adelantes trabajo de fases siguientes.
- Prioridad absoluta: consumo de batería. Justifica cualquier cambio que pueda aumentarlo.
- Sin internet: solo malla (Bluetooth/Wi‑Fi local) y mínimo consumo. Con internet, solo para: actualizaciones OTA (`update/FaroUpdater.kt`, desde `fcojalvarez/faro-releases`), chatear por Nostr y localizar a la familia en grupos privados con consentimiento. Nada de analíticas ni otras conexiones.
- Diseño: fondo negro, burbujas solo con borde (tuyas a la derecha en azul calma `#A7C7E7`, las demás en blanco), minimalista y sin jerga.
- Licencia GPL v3: conserva los avisos de copyright y no añadas dependencias incompatibles.
- No inventar criptografía: usa solo las primitivas ya presentes (Noise, ChaCha20-Poly1305, Ed25519).
- Sigue también `AGENTS.md` (guía heredada del proyecto original, en especial privacidad y datos de ubicación sintéticos en tests).
- La parte de radio no se puede probar en emulador; añade tests unitarios para formato de paquetes y lógica de grupos.

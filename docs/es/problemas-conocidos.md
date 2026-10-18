# Problemas conocidos en 0.1.0-alpha.1-demo

*Traducción de [known-issues.md](../known-issues.md). Si algo no coincide, manda la versión en inglés. Los
nombres de los menús aparecen como se ven en pantalla, con su traducción entre paréntesis.*

El comportamiento reportado puede depender del dispositivo, firmware y emulador. Si un problema
se comporta distinto en tu dispositivo, indica la versión de Mimir y qué ocurrió.

## Problemas abiertos

- **Bottom: la moneda flotante ᛗ tapa contenido.** En la pantalla inferior de la AYN Thor (Bottom), cualquier
  toque muestra la moneda flotante ᛗ. Puede quedar sobre un mosaico de plataforma y cortar su etiqueta:
  "Nintendo 64" se lee "Nintendo 6". También puede tapar parte de la página Activity (actividad).
- **Bottom: el conteo de juegos desaparece después de un juego.** El encabezado de Bottom muestra cuántas
  plataformas y juegos tienes, por ejemplo "18 platforms · 63 games". Después de lanzar un juego y volver, el
  conteo ya no está y no vuelve.
- **Quick Launch: el selector "Opens on" es transparente.** Mantén presionada una app en el Quick Launch
  (lanzamiento rápido) de Bottom: el selector que pregunta dónde se abre deja ver, por detrás, el título y los
  mosaicos de las apps.
- **Customize media: Search puede quedarse trabado.** Si presionas Search (Buscar) varias veces seguidas, la
  página puede quedarse en "searching".
- **El reloj de Now Playing se congela después de Recents.** Si recuperas un juego en pausa desde Recents
  (recientes) de Android, el reloj de Now Playing se queda congelado. Vuelve a correr cuando regresas con
  Resume (reanudar) de Mimir.
- **Los emuladores dibujan controles en pantalla.** Algunos emuladores dibujan sus propios controles en
  pantalla mientras el control de la Thor está conectado. Es un ajuste del emulador, no de Mimir. Desactiva los
  controles en pantalla en el propio emulador.

## Limitaciones

- **ScreenScraper e IGDB no están configurados en el alfa oficial.** Una cuenta personal no
  habilita las descargas de ilustraciones ni música de ScreenScraper en este APK. Los medios locales
  siguen funcionando; SteamGridDB usa tu propia clave.
- **Firmware del Tweaker.** Los chips admitidos también necesitan el servicio del fabricante y las
  capacidades necesarias. El ventilador solo se puede controlar si el dispositivo lo permite.

- **Fuentes muy grandes.** Con los tamaños de fuente más grandes de Android, una pantalla angosta puede perder
  un botón, por ejemplo la píldora Search en una lista de juegos.
- **Íconos oscuros de apps.** Los íconos de apps Android con un símbolo negro se ven mal sobre la placa oscura
  de la tarjeta de inicio.
- **Música de juegos "skipped".** La tarjeta de música de juegos cuenta como "skipped" (omitidos) los juegos
  cuyos archivos se movieron o se borraron, y no dice por qué. Vuelve a escanear tu biblioteca para limpiarlos.
- **De WAV a FLAC.** Puedes oír una pausa corta cuando una pista es un archivo WAV y la siguiente es FLAC, o al
  revés.
- **Carpetas de música con nombre de plataforma.** En tu carpeta de música, una subcarpeta con el nombre de una
  plataforma de juegos (por ejemplo "Genesis" o "Saturn") se trata como la música de juegos de esa plataforma.
- **Audio de baja latencia y el System EQ.** Los juegos que suenan por la ruta de audio de baja latencia de
  Android se saltan efectos como el System EQ. RetroArch lo hace; otros emuladores pueden comportarse distinto.
- **Versiones que compilas tú.** Llevan tu propia clave de firma, así que no pueden actualizar un release, y
  ScreenScraper necesita un registro de desarrollador que debes aportar (mira
  [BUILDING.md](../../BUILDING.md), en inglés).

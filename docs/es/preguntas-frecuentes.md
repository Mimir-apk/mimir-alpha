# Preguntas frecuentes

*Traducción de [faq.md](../faq.md). Si algo no coincide, manda la versión en inglés. Los nombres de los
menús aparecen como se ven en pantalla, con su traducción entre paréntesis.*

Los pasos de instalación y los permisos están en [instalar.md](instalar.md).

**¿Qué es Mimir?**
Un launcher de juegos para consolas portátiles Android, pensado para usarse con control. Escanea las
carpetas de juegos que elijas, muestra tu biblioteca en estantes, abre cada juego en el emulador que
configuraste. También funciona con touch. Para volver, sal del emulador, usa Inicio si Mimir
es tu app de inicio o usa el menú de Mimir cuando esté disponible.

**¿En qué consolas portátiles funciona?**
Mimir necesita Android 11 o más reciente. Los desarrolladores lo han usado en la AYN Thor (las dos
pantallas), la AYN Odin 3 y la Retroid Pocket Nova. En cualquier otro dispositivo, cuéntanos qué funciona y
qué no.

**¿Mimir trae juegos o archivos BIOS?**
No. Trae tus propios juegos y tus propios emuladores.

**¿Cómo pongo a funcionar mi primer juego?**
Sigue [Tu primer juego](instalar.md#tu-primer-juego): configura el emulador, elige una carpeta de juegos,
espera el escaneo, revisa el emulador de la plataforma y lanza un juego. Puedes hacerlo sin conexión,
sin Tweaker y sin una cuenta de proveedor. Si la biblioteca está vacía o el juego no se abre, consulta
[solución de problemas](instalar.md#solución-de-problemas).

**¿Por qué no hay un ícono de Tweaker o las dos apps no se conectan?**
El Tweaker de producción se abre desde Utility dentro de Mimir. Usa los dos APK del mismo release;
es normal que los números de versión sean distintos. Instala Tweaker primero y Mimir después.
Los certificados de firma deben coincidir, también en las compilaciones propias. Los controles
de hardware requieren capacidades compatibles del firmware. Consulta
[solución de problemas](instalar.md#solución-de-problemas).

**¿Cómo hago que Mimir sea mi app de inicio?**
La configuración inicial ofrece "Make Mimir your Home app" (hacer de Mimir tu app de inicio). Elige Permitir y
luego elige Mimir en el aviso de Android. Si la omitiste, abre Configuración de Android › Apps predeterminadas
› App de inicio y elige Mimir. Si Android te pregunta qué app de inicio usar, elige Mimir y luego Siempre.

**¿Dónde conecto ScreenScraper?**
**ScreenScraper no está disponible en el APK oficial 0.1.0-alpha.1-demo.** No contiene el registro de
desarrollador, así que iniciar sesión con una cuenta personal no habilita las descargas de
ilustraciones ni música. Una compilación debug propia puede usar tu registro de desarrollador:
consulta [BUILDING.md](../../BUILDING.md#optional-online-services), en inglés. En una compilación
configurada, la conexión está en Settings › Appearance › Enrichment & artwork. Los archivos
descargados permanecen en el dispositivo después de desconectar la cuenta.

**¿Para qué sirve la clave de SteamGridDB?**
Es una fuente opcional de íconos de juegos, imágenes hero e íconos de plataformas. Los juegos de PC la
necesitan para los hero y para la mayoría de los logos. Escribe tu propia clave en Settings › Appearance ›
Enrichment & artwork › Access key (clave de acceso). Se guarda cifrada en el dispositivo y se envía a SteamGridDB para autenticar las solicitudes. Las versiones de release
vienen sin clave.

**¿Qué hace Enrich?**
En una compilación configurada, le pide a ScreenScraper la descripción, la carátula, el logo, el hero y las ilustraciones del medio físico de
un juego. En un juego de PC usa sobre todo SteamGridDB. Cuando un intento termina, haya encontrado algo o no,
el botón Enrich desaparece para ese juego, después de obtener un resultado, aunque no haya coincidencias. Para cambiar las
ilustraciones de un juego después, usa Customize media (personalizar medios). Si el intento falló (por
ejemplo, no había red), el botón se queda para que lo intentes otra vez.

**¿Qué es la música de juegos?**
Es el sonido que suena bajo la animación de inicio de un juego, recortado del clip de video de ese juego en
ScreenScraper. Esta descarga no está disponible en el alfa oficial. En una compilación configurada,
conecta ScreenScraper y luego descárgala en Settings › Music › Game music. Launch sound
(sonido de inicio) define su volumen: Off, 25%, 50%, 75% o 100%. Un juego cuyo clip no tiene sonido no tiene
música de juego.

**¿Qué es Mimir Tweaker?**
Una segunda app opcional con controles de hardware. Su panel aparece dentro del panel Utility (Utilidades) de
Mimir: un perfil de rendimiento (Stock, Quiet, Balanced o Performance) que previsualizas y luego aplicas o
cancelas, un modo de ventilador (Auto o Manual, con un ajuste de respuesta) y una superposición de
rendimiento. Los perfiles admiten las familias Snapdragon 865, 8 Gen 2 y 8 Elite cuando el firmware expone el
servicio del fabricante y las capacidades necesarias. El ventilador depende de los controles disponibles. "Let other music play
over games" también lo necesita. Escanear, explorar la biblioteca y lanzar juegos no requieren Tweaker.

**¿Qué significa "doble pantalla"?**
Una consola portátil con dos pantallas físicas, como la AYN Thor. Mimir usa la pantalla inferior, que llama
Bottom, para una segunda superficie con dos páginas, Shelf (estante) y Activity (actividad). Desliza para
cambiar. Activity muestra lo que juegas o lo último que jugaste, los juegos de esta semana, los más lanzados
y tus logros. Cuenta lanzamientos, no tiempo de juego. Mientras un juego está en pausa, NOW PLAYING muestra
PAUSED junto al reloj. Bottom también tiene un reproductor de música (Volume, Equalizer, Source) y un
trackpad. La primera vez que abres el trackpad, Mimir te pide tu consentimiento.

**¿Qué envía Mimir por la red?**
El escaneo y el envío del lanzamiento funcionan sin conexión. Las funciones conectadas pueden
actualizarse mientras la app está abierta, sin otro toque para cada solicitud. Los proveedores incluyen
ScreenScraper en compilaciones configuradas, SteamGridDB (la consulta de descripciones de la tienda de Steam para
juegos de PC depende de ese ajuste), RetroAchievements, Enrich, y Settings › Emulators › Check for updates,
que descarga de GitHub una lista pequeña de datos de lanzamiento. Cuando un escaneo encuentra juegos nuevos,
Mimir te pregunta antes de buscar sus ilustraciones. En una compilación debug configurada, IGDB/Twitch
puede usarse como alternativa para datos e ilustraciones. Consulta [Privacidad](privacidad.md) para
los datos enviados, las cuentas y las copias exportadas.

**¿Dónde reporto un bug?**
Abre un issue con el formulario de bug. Mira [instalar.md](instalar.md#reportar-un-bug) para saber qué incluir.

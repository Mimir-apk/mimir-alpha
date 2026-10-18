# Instalar Mimir

*Traducción de [install.md](../install.md). Si algo no coincide, manda la versión en inglés. Los
nombres de los menús aparecen como se ven en pantalla, con su traducción entre paréntesis.*

Mimir necesita Android 11 o más reciente. Esta es una versión alfa: mira
[problemas-conocidos.md](problemas-conocidos.md). Las respuestas cortas están en
[preguntas-frecuentes.md](preguntas-frecuentes.md).

## Descargar los APK

Descárgalos desde [Releases](https://github.com/Mimir-apk/mimir-alpha/releases/tag/v0.1.0-alpha.1-demo):

| App | Archivo adjunto a Alpha Release 1 - Demo | ¿Es necesario? |
|---|---|---|
| Mimir (`com.gruning.mimir.rewrite`) | [mimir-0.1.0-alpha.1-demo.apk](https://github.com/Mimir-apk/mimir-alpha/releases/download/v0.1.0-alpha.1-demo/mimir-0.1.0-alpha.1-demo.apk) | Sí |
| Mimir Tweaker (`com.gruning.mimir.tweaker`) | [mimir-tweaker-0.4.4-tw12.apk](https://github.com/Mimir-apk/mimir-alpha/releases/download/v0.1.0-alpha.1-demo/mimir-tweaker-0.4.4-tw12.apk) | Controles de hardware opcionales |

Las dos apps tienen números de versión distintos; usa el par del mismo release. Los archivos de
código fuente ZIP/tar.gz de GitHub sirven para compilar, no para instalar en Android.
[Archivos del release](../release-notes/0.1.0-alpha.1-demo-files.md), en inglés, indica los hashes,
el certificado de firma y el código fuente correspondiente.

Para instalar la versión oficial, usa los APK adjuntos a ese release. Android puede pedirte que permitas
instalaciones desde la app con la que abriste el archivo; permítelo para esta instalación.

El Tweaker es una app aparte con controles de hardware. Sus perfiles de rendimiento solo funcionan en
consolas portátiles con un chip Snapdragon 865, 8 Gen 2 u 8 Elite y firmware compatible que exponga el
servicio del fabricante necesario. El chip por sí solo no basta. El control del ventilador también
depende del hardware disponible.
El ajuste "Let other music play over games" (dejar que otra música suene sobre los juegos) también lo
necesita. Escanear, explorar la biblioteca y lanzar juegos no requieren Tweaker. Si lo quieres, instálalo primero y Mimir después.

Los dos APK del release están firmados con la misma clave, y así se reconocen las dos apps. Un APK que
compilas tú lleva tu propia clave: Android no lo instalará sobre un release, y un Mimir y un Tweaker
compilados con claves distintas no se emparejan.

## Tu primer juego

1. Instala y configura el emulador por separado. Comprueba que abre un juego directamente antes de
   intentar lanzarlo desde Mimir. Mimir no incluye emuladores, juegos ni archivos BIOS.
2. Abre Mimir y completa la configuración inicial. Puedes omitir los permisos opcionales y las
   conexiones de cuentas; consulta [los permisos](#qué-pide-mimir). No necesitas una cuenta en línea
   para escanear ni lanzar juegos.
3. Selecciona **Choose folder** (elegir carpeta) durante la configuración y permite el acceso a tu
   carpeta de juegos. Después, usa **Settings › Library › Add Library folder** para agregar otra
   ubicación. **Choose Mimir ROM folder** cambia la carpeta principal; usa Add Library folder si
   quieres conservar también la anterior.
4. Espera a que termine el escaneo. Si agregaste o moviste archivos, usa **Settings › Library › Rescan
   library**. Mantén conectado el almacenamiento y vuelve a permitir el acceso si Android lo pide.
5. En **Settings › Emulators**, revisa el emulador predeterminado de cada plataforma escaneada.
   La lista se llena después de escanear juegos. Usa **Choose emulator**, **Choose another** o
   **Change** para seleccionar un emulador instalado cuando haga falta.
6. Elige un juego y lánzalo. **Open with** selecciona el emulador para ese juego; **Automatic** vuelve al
   predeterminado de la plataforma. Si no se abre, consulta [solución de problemas](#solución-de-problemas)
   antes de cambiar tu biblioteca.

Para volver, sal del emulador, usa Inicio de Android si Mimir es tu app de inicio, o usa el menú de
Mimir cuando esté disponible. El regreso automático varía según el emulador y el firmware. Las
descargas de ilustraciones y convertir Mimir en tu app de inicio pueden esperar hasta que hayas
lanzado tu primer juego.

Si instalaste Tweaker, usa **Settings › Controls › Utility › Open** dentro de Mimir. El Tweaker de
producción no tiene un ícono de inicio separado. Los controles dependen del hardware y del firmware;
consulta [la ayuda de Tweaker](../../mimir-tweaker/README.md), en inglés.

## Actualizar

Instala el APK nuevo sobre el anterior: ábrelo y elige Actualizar, o ejecuta `adb install -r <archivo>`.
Tu biblioteca, tus Tomos y tu configuración se conservan.

- No desinstales Mimir para actualizarlo. Al desinstalar se borran esos datos.
- No instales una versión más vieja sobre una más nueva. La app vieja puede no poder leer la base de datos
  nueva.

## Qué pide Mimir

Durante la configuración inicial, Mimir pide cuatro cosas. Todas son opcionales: elige "Not now" (Ahora no)
para omitirlas. Después puedes cambiarlas en la configuración de Android.

| Permiso | Para qué sirve |
|---|---|
| Acceso a todos los archivos (All files access) | El administrador de archivos de Mimir, y mostrar y guardar ilustraciones junto a tus juegos. Si lo rechazas, eliges las carpetas en el selector de Android. |
| Mostrar sobre otras apps (Display over other apps) | El botón de Mimir: una M que mantienes presionada para abrir un menú, incluso en pleno juego. |
| Acceso a datos de uso (Usage access) | Permite que Mimir vea cuándo sales de un juego, para que el botón de Mimir pueda mostrarse encima. Una consola portátil con segunda pantalla, como la AYN Thor, no lo necesita. |
| App de inicio (Home app) | Hace que Mimir sea la app de inicio, así la consola arranca en Mimir. Puedes elegir otra en Configuración de Android › Apps predeterminadas. |

Más adelante, solo cuando uses la función:

- **Notificaciones**: se piden una sola vez, la primera vez que activas la superposición de rendimiento, o el
  botón de Mimir en una consola de una sola pantalla. Los dos funcionan sin importar lo que respondas.
- **Modificar ajustes del sistema (Modify system settings)**: se pide desde el panel Utility (Utilidades) para
  el control deslizante de brillo. Si el dispositivo usa brillo automático, desactívalo primero; si no, el
  control no podrá cambiar el brillo.

Tres funciones las activas tú en la configuración de Android:

- **Mimir Keyboard**, un método de entrada. Settings › Controls › Keyboard (Configuración › Controles ›
  Teclado), en Mimir, muestra su estado y te lleva a la configuración de teclado de Android.
- **El servicio del trackpad**, un servicio de accesibilidad. Puede tocar y deslizar donde está el puntero del
  trackpad. No puede leer la pantalla ni lo que escribes.
- **Acceso a notificaciones**, para que Mimir muestre y controle los reproductores de música de otras apps
  (Settings › Music › Behaviour › Show other players).

Android concede esto al instalar, sin preguntarte:

- Internet, para ilustraciones, metadatos y acciones de logros que tú inicias. Escanear y lanzar juegos nunca
  lo usan.
- Servicio en primer plano y wake lock, para el botón de Mimir, la superposición de
  rendimiento y la música que sigue sonando con Mimir en segundo plano.
- Vibración, ajustes de audio (el volumen de la música en Utility) y cerrar procesos en segundo plano (para
  cerrar los juegos que Mimir abrió).
- Control del Tweaker, un permiso que solo puede tener una app con la firma del Tweaker.

Mimir no pide ver todas las apps del dispositivo. Conoce los emuladores de su lista y ve las apps que tienen
un acceso en el lanzador.

Consulta [Privacidad](privacidad.md) para conocer los datos guardados, las solicitudes a proveedores
y las copias de seguridad. El acceso a notificaciones permite leer notificaciones multimedia;
el teclado recibe el texto que escribes con él.

## Solución de problemas

| Qué ocurre | Qué revisar |
|---|---|
| Android indica que la actualización es incompatible o que el paquete entra en conflicto | Los APK oficiales, propios y de CI pueden tener firmas distintas. Usa una actualización del mismo firmante que la app instalada. No desinstales ni borres los datos para evitar el error. Consulta [firmas](../../mimir-tweaker/SIGNING.md), en inglés. |
| No aparece un ícono de Tweaker después de instalarlo | Es normal en la app de producción. Abre Utility dentro de Mimir. |
| Mimir no puede usar Tweaker | Usa los dos APK del mismo release, instala Tweaker primero, instala o actualiza Mimir y vuelve a abrirlo. Las compilaciones propias necesitan la clave compartida antes de compilar ambas apps. Un chip compatible aún necesita acceso del fabricante; instalar Tweaker no lo agrega. |
| La biblioteca está vacía o faltan juegos | Comprueba que el almacenamiento esté conectado y que Mimir tenga acceso a la carpeta elegida. Agrega la carpeta correcta en Settings › Library y usa Rescan library. No reemplaces la carpeta principal si querías agregar otra. |
| El juego aparece pero no se abre | Ábrelo directamente en el emulador, revisa el predeterminado en Settings › Emulators y prueba Open with para ese juego. La configuración y los formatos compatibles dependen del emulador. |
| Iniciar sesión en ScreenScraper no habilita descargas | El alfa oficial no tiene registro de desarrollador de ScreenScraper. Una cuenta personal no lo habilita. Los medios locales funcionan; SteamGridDB usa tu propia clave. Consulta [preguntas frecuentes](preguntas-frecuentes.md). |

Si no se resuelve, consulta [los problemas conocidos](problemas-conocidos.md) y reporta el problema.

## Reportar un bug

Abre un issue con el formulario de bug. Indica tu dispositivo, tu versión de Android y la versión de Mimir
(Settings › About › Version), si usaste los APK oficiales o una compilación propia, y la versión de Tweaker
si corresponde. Para un problema de lanzamiento, indica el nombre y la versión del emulador y si el mismo
archivo se abre directamente en él. Describe qué hiciste, qué esperabas y qué pasó. Una captura de pantalla o un video corto
ayuda. Los logs son opcionales: quita antes los nombres de cuenta y los correos. Nunca publiques una
contraseña ni un enlace que contenga una.

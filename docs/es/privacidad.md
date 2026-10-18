# Privacidad en Mimir 0.1.0-alpha.1-demo

Actualizado el 18 de octubre de 2026. Consulta también [la descripción completa en inglés](../../PRIVACY.md).

Mimir guarda en el dispositivo las carpetas elegidas, nombres e identificadores de juegos,
configuración, Favoritos, Tomos, historial de lanzamientos, medios descargados y conexiones de
cuentas. Las contraseñas y claves de API se cifran con claves protegidas por Android Keystore.
Los nombres de usuario y ciertos datos de conexión quedan en almacenamiento privado de la app,
pero no todos están cifrados.

El teclado recibe el texto que escribes con él. El acceso a notificaciones permite leer
notificaciones multimedia. El servicio de accesibilidad del trackpad realiza gestos y no tiene
declarado acceso al contenido de las ventanas.

Escanear y lanzar juegos no requiere consultas a proveedores. Las funciones conectadas son
opcionales, pero pueden actualizar datos sin pedir otro toque para cada solicitud. SteamGridDB
recibe tu clave y las consultas de juegos; la descripción de juegos de PC puede consultarse en
Steam. RetroAchievements recibe los datos necesarios de tu cuenta y de logros. Los proveedores
también ven tu dirección IP y datos normales de conexión. ScreenScraper e IGDB no están configurados
en el alfa oficial. En una compilación debug configurada, ScreenScraper puede recibir
credenciales, nombres, tamaños y hashes de juegos; IGDB/Twitch recibe autenticación y consultas.
La actualización manual de definiciones de emuladores consulta GitHub.

Las copias JSON que exportes no están cifradas por Mimir. Pueden contener tu nombre de perfil,
pistas de las carpetas, selecciones de biblioteca e información de logros; no incluyen los
almacenes de credenciales. Revísalas antes de compartirlas. La copia automática de Android está
desactivada. Esta versión no incluye un SDK publicitario o de analítica ni un servicio
automático para subir fallos.

Desconectar una cuenta no borra medios descargados. Borrar los datos de la app o desinstalarla
elimina sus datos privados; los archivos guardados junto a los juegos o exportados pueden quedar.
Estas acciones no borran los registros de los proveedores ni publicaciones en GitHub. Sus políticas
se aplican por separado; los enlaces están en la descripción completa.

Los reportes públicos de GitHub pueden ser vistos por otros. Quita datos personales y secretos
de capturas, logs y archivos antes de adjuntarlos. Para vulnerabilidades, usa
[el reporte privado](../../SECURITY.md).

# Rediseño 0.2 (15/10/2026)

## Qué cambia para quien usa la app
- **Mapa oscuro propio («Trazo Noche»)** con calles jerarquizadas, edificios en 3D desde zoom 15 y etiquetas legibles. Usa los mapas vectoriales de **OpenFreeMap**: gratis, sin clave, sin límite de uso y con uso comercial permitido. Atribución obligatoria visible: «OpenFreeMap © OpenMapTiles Data from OpenStreetMap». Los estilos anteriores siguen en el menú de capas.
- **Abre en tu calle**: usa la última posición conocida al instante y centra a zoom 16–17 (según la precisión) con el primer fijo real. Mientras exploras el mapa no se vuelve a centrar; el botón de localizar vuelve a seguirte. La cámara se guarda al salir.
- Estados claros: «Buscando tu ubicación…», permiso denegado (con botón para concederlo; el mapa sigue explorable), GPS apagado, sin conexión y error al cargar el mapa con reintento.
- **Posición en directo** de los amigos que te comparten ubicación (Supabase Realtime). Se reconecta sola con espera progresiva, ignora actualizaciones antiguas y los marcadores se deslizan entre fijos reales (nunca se inventan posiciones intermedias por delante del último dato).
- **Marcadores**: foto circular con un coche pequeño debajo (orientado según el rumbo), nombre y aviso de dato antiguo (gris con reloj) o pausado. Se agrupan al alejar el mapa; tocar un grupo acerca, tocar a alguien abre su tarjeta.
- **Líneas en directo** por persona, cada una de su color, con interruptor por persona y botón para empezar la línea de nuevo. Se descartan puntos imprecisos y saltos; un corte de señal largo corta la línea en vez de trazar una recta. No toca el historial de viajes.
- Mientras la app está abierta, tu posición se comparte aunque no haya un viaje (si compartir está activado y no estás en modo fantasma).
- **Perfil**: nombre de usuario (@usuario) y presentación corta. **Amigos**: búsqueda por @usuario además del código, y se ve quién te comparte su ubicación.

## Lo que sigue igual
Viajes, historial, mapa de calor, convoyes, grupos, exportación GPX, privacidad por amigo, modo fantasma. No se ha quitado ninguna función.

## Base de datos
Migración `supabase/migrations/20261015000000_live_profiles.sql`. Solo añade: columnas `username` y `bio`, un índice, y las funciones `find_users`, `send_friend_request_to` y `live_trail`. No borra tablas, columnas ni filas y puede ejecutarse varias veces. Si no se ejecuta, la app funciona igual salvo usuario/presentación, búsqueda por @usuario y la línea ya recorrida de un amigo al abrir el mapa.

Reglas de privacidad: `find_users` devuelve solo nombre, @usuario y foto (nunca ubicación) y exige 3 caracteres. `live_trail` aplica la misma regla que la posición (`can_see_location`): solo amigos a los que compartes, sin modo fantasma ni pausa, y solo del viaje en curso.

## Límites honestos
- **Con la app cerrada** la posición se actualiza solo durante un viaje (detección automática al conducir) o con la vigilancia continua activada. Con la app cerrada y sin viaje, los amigos ven tu última posición marcada como antigua.
- **OpenFreeMap** se financia con donaciones y no garantiza disponibilidad. Si cae, el mapa muestra el error con reintento.
- **Supabase gratis**: 200 conexiones Realtime simultáneas, 2 millones de mensajes al mes, 500 MB de base de datos, 1 GB de archivos y el proyecto se pausa tras 7 días sin uso (se reactiva desde el panel). Con un grupo de amigos queda muy por debajo. No hay nada de pago en la app.
- Las fotos de perfil están en un bucket público con nombres aleatorios: quien tenga el enlace exacto puede verla.

## Firma de las versiones de prueba
Desde 0.2 todas las APK de prueba se firman con `app/trazo-test.keystore` (solo para pruebas, no para Play Store), así cada versión nueva se instala encima de la anterior. Las 0.1.x tenían otra firma: hay que desinstalar la 0.1.x una vez.

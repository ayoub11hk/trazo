# Trazo

App Android para registrar tus trayectos en coche o moto con GPS de alta precisión y compartirlos con tus amigos: ubicación en directo, historial con reproducción animada, convoyes, garaje y estadísticas. **Todo es gratis**: no hay suscripciones, compras dentro de la app, anuncios ni límites artificiales (lo verifican también las pruebas del backend).

Inspirada en la categoría de apps como Wheelz, con nombre, marca, iconos, código y diseño propios.

## Qué hay en el repositorio

| Carpeta | Contenido |
|---|---|
| `core/` | Lógica pura en Kotlin/JVM: filtro GPS, detección de viajes, análisis, reproducción, estadísticas, invitaciones, exportación GPX, simulador. Con pruebas unitarias. |
| `app/` | App Android (Kotlin + Jetpack Compose + MapLibre + Room + WorkManager + Supabase). |
| `supabase/` | Esquema de base de datos, reglas de privacidad (RLS) y funciones del servidor, con pruebas de acceso. |
| `docs/` | Arquitectura, backend, algoritmo GPS, privacidad, pruebas e instalación. |
| `.github/workflows/android.yml` | CI: pruebas del núcleo, pruebas SQL, APK de depuración y release firmado opcional. |

## Empezar

1. Crea un proyecto en Supabase y aplica la migración: ver [docs/BACKEND.md](docs/BACKEND.md).
2. Compila: ver [docs/INSTALACION.md](docs/INSTALACION.md). Sin servidor configurado en la compilación, la app pide la URL y la clave pública al abrirse.
3. Estado de las pruebas (qué se ha probado y qué no): [docs/PRUEBAS.md](docs/PRUEBAS.md).

## Funciones

- Cuenta con correo y contraseña, recuperación por código, foto de perfil.
- Amigos con códigos únicos `XXXX-XXXX` y enlaces `trazo://invite/…`, solicitudes, grupos, convoyes.
- Mapa MapLibre/OpenStreetMap con estilos de día y noche, vista inclinada 3D, marcadores con el vehículo de cada amigo, agrupación, frescura de la posición (en directo / hace X min / pausado).
- Registro automático con histéresis (semáforos y atascos no cortan el viaje), inicio y fin manual, recuperación tras cierre o reinicio, unir y dividir viajes, tres niveles de sensibilidad.
- Historial con búsqueda y filtros, detalle con incidencias de señal, reproducción con tiempos reales a 1×–60×, ajuste opcional a carreteras (sin alterar lo medido), exportación GPX.
- Garaje ilimitado con catálogo de 53 marcas y 442 modelos, colores y carrocerías; el vehículo activo aparece en el mapa de tus amigos.
- Estadísticas por semana, mes, año y total, comparación con el periodo anterior, por vehículo, récords, mapa de calor y clasificación entre amigos que **nunca premia la velocidad**.
- Privacidad aplicada en el servidor: permisos por amigo, grupo y convoy, modo fantasma, ocultar velocidad, retención configurable, exportación y borrado de datos y de la cuenta.
- Funciona sin conexión: todo se guarda primero en el teléfono y se sincroniza después.

## Limitaciones conocidas

- Los vehículos en el mapa son siluetas cenitales generadas (orientadas y alineadas con la inclinación del mapa), no modelos 3D: MapLibre Native no carga modelos 3D.
- Las notificaciones llegan mientras la app o el servicio de registro están activos; no hay push con la app cerrada (requeriría Firebase Cloud Messaging).
- Los mapas sin conexión usan la caché de teselas de MapLibre (zonas ya vistas); no hay descarga de regiones completas.

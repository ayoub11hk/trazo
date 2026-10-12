# Arquitectura

```
┌────────────── app (Android) ──────────────┐        ┌──── Supabase ────┐
│ Compose UI ─ pantallas                     │        │ Auth (correo)    │
│   │                                        │ HTTPS  │ Postgres + RLS   │
│ Repositorios ── Room (primero local) ──────┼───────▶│ RPC (SQL)        │
│   │              WorkManager (sync)        │  WSS   │ Realtime         │
│ TrackingService (primer plano, ubicación)  │◀───────┤ Storage (fotos)  │
│   │                                        │        └──────────────────┘
│ core (JVM puro, probado)                   │
└────────────────────────────────────────────┘
```

## Módulos

- **core**: sin dependencias de Android, por eso se prueba en la JVM.
  - `location/GpsFilter`: descarta saltos y fijos imprecisos, detecta cortes de señal.
  - `location/DistanceAccumulator`: distancia medida sin sumar el «temblor» estando parado.
  - `trip/TripDetector`: máquina de estados IDLE → ARMED → DRIVING ⇄ STOPPED → fin.
  - `trip/TripRecorder`: une filtro y detector; búfer previo para no perder el arranque; estado serializable para sobrevivir a cierres.
  - `trip/TripAnalyzer`, `RouteGeometry`, `TripEditor`: resumen, geometrías (medida, suavizada, huecos, descartados), unir, dividir, recortar.
  - `playback/PlaybackTimeline`: reproducción con tiempos reales; en un corte de señal el marcador espera, no inventa recorrido.
  - `stats/*`: periodos, comparativas, series, récords, clasificación (sin métricas de velocidad) y mapa de calor.
  - `policy/LocationPolicy`: frecuencia y prioridad de GPS según estado, batería y visibilidad.
  - `export/Gpx`, `social/*` (códigos de invitación, frescura), `sim/DriveSimulator`.
- **app**
  - `tracking/TrackingService`: servicio en primer plano de tipo `location`. Proveedor *fused* de Google (o el de la plataforma sin Play Services), reconocimiento de actividad como pista, escritura ordenada en Room, publicación de la posición en directo, recuperación tras reinicio.
  - `data/local`: Room (viajes, puntos, vehículos, caché de amigos). Los puntos se guardan uno a uno: si la app muere, no se pierde lo grabado.
  - `data/repo`: acceso a Supabase. Los viajes usan UUID generado en el teléfono y los puntos `(trip_id, seq)`, así que reintentar la subida nunca duplica datos.
  - `sync`: WorkManager con restricción de red y reintentos.
  - `map`: MapLibre con estilos de OpenFreeMap (sin clave), capas de ruta, marcadores, mapa de calor; ajuste a carreteras con OSRM.
  - `ui`: Compose, Material 3, tema propio (grafito, Volt #B8FF3C, Signal #39C6FF).

## Decisiones

- **Supabase** porque ofrece Auth, Postgres con RLS, Realtime que respeta RLS y Storage, todo con una clave pública. Toda la privacidad vive en la base de datos, no en la app.
- **Local primero**: la app funciona sin red; la sincronización es idempotente.
- **Nunca se presenta una posición vieja como actual**: la frescura usa la hora en que el teléfono midió el fijo (`recorded_at`), y a partir de 45 s deja de marcarse «en directo».
- **Tres geometrías distintas**: la medida (fijos aceptados), la suavizada solo para dibujar y la ajustada a carreteras (opcional, de un servicio externo). Las estadísticas usan siempre la medida.

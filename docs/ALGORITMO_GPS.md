# Algoritmo GPS y detección de viajes

## Captura
- Proveedor *fused* de Google Play Services con `PRIORITY_HIGH_ACCURACY` (sin Play Services, `LocationManager` de la plataforma).
- Frecuencia adaptativa (`core/policy/LocationPolicy.kt`):

| Estado | Prioridad | Intervalo |
|---|---|---|
| Conduciendo | alta precisión | 1 s |
| Armando (posible inicio) | alta precisión | 2 s |
| Parado dentro de un viaje | alta precisión | 5 s |
| En espera, app visible | alta precisión | 3 s |
| En espera, compartiendo | equilibrada | 60 s (lotes hasta 2 min) |
| En espera, sin compartir | pasiva | 5 min |

- El reconocimiento de actividad de Android (IN_VEHICLE, ON_FOOT, ON_BICYCLE, STILL) solo es una pista: arranca el servicio y acorta el cierre cuando bajas del coche. La decisión la toman los datos GPS.
- La publicación en directo va aparte (intervalo de 1 a 30 s elegido por el usuario, con latido para que no parezca desconectado).

## Filtro (`GpsFilter`)
Cada fijo recibe una marca:
- `LOW_ACCURACY`: precisión peor de 35 m.
- `JUMP`: implicaría más de 90 m/s (324 km/h) desde el último bueno. Si llegan 3 seguidos coherentes entre sí, el filtro se reancla (era el anterior el malo).
- `DUPLICATE`: mismo instante.
- `STATIONARY_JITTER`: casi sin velocidad Doppler; se guarda pero no suma distancia.
- Sin fijos durante más de 20 s → el siguiente abre un **segmento** nuevo: el hueco nunca se dibuja como recorrido medido (se muestra discontinuo y se contabiliza aparte como «sin señal»).

Nunca se crean coordenadas: lo guardado son los fijos tal cual con su marca. La línea suavizada (filtro de Kalman) solo sirve para dibujar; la ajustada a carreteras (OSRM) es opcional y se muestra como aproximación.

## Distancia (`DistanceAccumulator`)
Solo entre fijos aceptados del mismo segmento, ignorando desplazamientos menores que el ruido esperado (función de la precisión de ambos fijos, mínimo 3 m) y cuando la velocidad Doppler indica parado. Así una hora aparcado no suma kilómetros.

## Detección de viajes (`TripDetector`)
Estados: `IDLE → ARMED → DRIVING ⇄ STOPPED → fin`.

| Parámetro (sensibilidad normal) | Valor |
|---|---|
| Velocidad para armar | 5 m/s (18 km/h) |
| Confirmar inicio | ≥ 25 s armado, ≥ 200 m de desplazamiento y mediana ≥ 3,2 m/s (descarta andar y correr) |
| Parado | < 1,5 m/s dentro de 35 m |
| Fin por quietud | 5 min parado (2 min si Android detecta que has bajado del vehículo) |
| Fin por silencio | 20 min sin ningún fijo |
| Descartar falso positivo | < 400 m o < 90 s |

Las sensibilidades baja y alta cambian estos umbrales. La histéresis (5 min) evita cortar en semáforos, pasos a nivel y atascos. Al cerrar se recortan los puntos posteriores al último movimiento real.

Un búfer previo de 900 puntos guarda lo ocurrido mientras se armaba, así el viaje empieza donde empezaste a moverte, no 25 s después.

## Recuperación
El estado del grabador se guarda en Room. Si Android mata la app, al volver se continúa el viaje. Tras un reinicio del teléfono, si han pasado menos de 20 minutos se continúa; si no, se cierra como interrumpido en su último punto. Las marcas de tiempo de arranque (`elapsedRealtime`) se sustituyen por la hora real cuando cambian de época.

## Reproducción (`PlaybackTimeline`)
Usa las horas reales de cada fijo. Entre fijos consecutivos interpola solo para el movimiento visual (≈1 s de separación); en un corte de señal el marcador se queda en el último punto y reaparece en el primero tras el corte.

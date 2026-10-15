# Pruebas: qué se ha comprobado y qué no

Este documento distingue lo **ejecutado** de lo **pendiente**. No se da nada por probado sin haberlo ejecutado.

## Rediseño 0.2 (15/10/2026)

### Ejecutado y en verde
- Núcleo: `gradle -p core test` → 52 pruebas, 0 fallos (17 nuevas: líneas en directo, movimiento de marcadores, cámara).
- Backend: `supabase/tests/run.sh` → 106 comprobaciones, 0 fallos (26 nuevas: @usuario, presentación, búsqueda, solicitudes, `live_trail` con amigos, desconocidos, sin compartir, modo fantasma, viaje terminado y anónimo). La migración se ejecutó dos veces seguidas sin error.
- Estilo de mapa: validado con el validador oficial de MapLibre (0 errores).
- Compilación de la APK en GitHub Actions.

### No ejecutado aquí (requiere teléfono o el Supabase real)
El contenedor no puede descargar los mapas de OpenFreeMap ni ejecutar Android, así que estas pruebas quedan para el teléfono:
1. Abrir la app con permiso: centra en tu calle a zoom 16–17 en pocos segundos.
2. Denegar el permiso: aparece el aviso con botón y el mapa se puede mover.
3. Modo avión: aparece «Sin conexión»; al volver, se reconecta solo.
4. Mover el mapa: no vuelve a centrarse hasta pulsar el botón de localizar.
5. Cerrar y abrir: vuelve a la última vista.
6. Con dos teléfonos amigos: cada uno ve al otro moverse con suavidad y su línea de color.
7. Dejar de compartir o modo fantasma: el otro deja de verte.
8. Un tercero que no es amigo no ve nada.
9. Perder señal un rato (túnel): la línea se corta, no hay recta.
10. Ocultar y borrar la línea de un amigo desde el panel.
11. Poner @usuario y presentación, buscar a un amigo por @usuario y enviarle solicitud.
12. Subir foto de perfil y verla en el marcador del amigo.

## Ejecutado y en verde (12/10/2026)

### Núcleo — `gradle -p core test` → 30 pruebas, 0 fallos
- Filtro GPS: descarta saltos aislados, se reancla tras un salto real, rechaza fijos imprecisos, marca cortes de señal y no inventa puntos.
- Distancia: un trayecto simulado de distancia conocida queda dentro de tolerancia; 10 minutos parado con ruido no suman distancia.
- Detección: arranca al conducir, no arranca andando ni corriendo, sobrevive a semáforos y atascos, termina tras la quietud configurada, descarta trayectos falsos cortos, inicio/fin manual, restauración tras cierre y tras reinicio, sensibilidades.
- Análisis, geometrías, unir/dividir/recortar, reproducción con tiempos reales y espera en cortes, polilíneas, simplificación, estadísticas por periodo y comparativas, clasificación sin velocidad, mapa de calor, códigos de invitación, frescura de posición, política de batería, exportación GPX por segmentos.

### Backend — `supabase/tests/run.sh` → 80 comprobaciones, 0 fallos
PostgreSQL 16 con una capa mínima que imita Supabase (roles, `auth.uid()`, Storage). Cubre visibilidad de ubicaciones y viajes entre amigos, grupos y convoyes; modo fantasma, pausa y ocultar velocidad; solicitudes (incluida la aceptación cruzada y el límite por hora); permisos de grupos y convoyes; exportación, borrado de viajes y de cuenta; acceso anónimo denegado; ausencia de cualquier estructura de pago.

### App Android — compilación en GitHub Actions
- `assembleDebug` compila sin errores desde el 12/10/2026 (tras corregir 16 errores de compilación y subir `compileSdk` a 36). Compilar no significa que todo funcione: falta probarla en un dispositivo.

## Pendiente de ejecutar
- **Pruebas en un teléfono real**: GPS en coche, túneles, segundo plano con la pantalla apagada durante horas, reinicio del teléfono a mitad de viaje, consumo de batería, ahorro de energía de distintos fabricantes.
- **Integración con un Supabase real**: registro, correo de recuperación, Realtime y subida de fotos (requieren un proyecto real y sus datos de conexión).
- Pruebas de interfaz automatizadas (Compose UI tests) e instrumentadas.

Cuando se ejecuten, se anotarán aquí con fecha y resultado.

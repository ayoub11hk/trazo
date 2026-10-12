# Pruebas: qué se ha comprobado y qué no

Este documento distingue lo **ejecutado** de lo **pendiente**. No se da nada por probado sin haberlo ejecutado.

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

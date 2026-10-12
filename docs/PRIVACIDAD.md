# Política de privacidad de Trazo

*Plantilla para quien opere el servidor. Sustituye los campos entre corchetes antes de publicar la app. No es asesoramiento jurídico: revísala con un profesional.*

**Responsable del tratamiento**: [nombre o razón social], [NIF], [dirección], contacto: [correo]. Delegado de protección de datos, si lo hay: [correo].

## Qué datos tratamos
| Dato | Para qué | Base jurídica (RGPD art. 6) |
|---|---|---|
| Correo y contraseña (cifrada por Supabase Auth) | Crear y proteger tu cuenta | Ejecución del servicio (6.1.b) |
| Nombre y foto | Que tus amigos te reconozcan | Ejecución del servicio |
| Ubicación precisa, también en segundo plano | Registrar viajes y mostrarte a quien autorices | Consentimiento (6.1.a), revocable en cualquier momento desde Android o la app |
| Viajes (puntos GPS, horas, velocidades, distancias) | Historial, estadísticas, compartir | Ejecución del servicio |
| Vehículos | Garaje y mostrar tu vehículo | Ejecución del servicio |
| Relaciones (amigos, grupos, convoyes) | Funciones sociales | Ejecución del servicio |
| Nivel de batería al compartir | Que tus amigos sepan por qué dejas de actualizar | Interés legítimo (6.1.f), se puede desactivar dejando de compartir |

No usamos los datos para publicidad, no los vendemos, no hay rastreadores de terceros ni elaboración de perfiles comerciales. La actividad física de Android solo se usa en el teléfono para detectar viajes.

## Quién puede verlos
Solo las personas a las que das permiso y solo mientras dure: cada amigo por separado (ubicación y viajes), grupos (ubicación, desactivable por grupo) y convoyes activos. Lo garantizan reglas en la base de datos, no la app. El **modo fantasma** y la **pausa** retiran tu posición del servidor. Puedes ocultar tu velocidad y no aparecer en clasificaciones.

## Encargados y transferencias
- Supabase Inc. (alojamiento de base de datos, autenticación y archivos) — región [UE, p. ej. Frankfurt]. Contrato de encargo y cláusulas contractuales tipo según su DPA.
- Teselas de mapa: OpenFreeMap. Tu teléfono descarga teselas por zona; no se envía tu identidad.
- Ajuste a carreteras (opcional, desactivado por defecto): se envían las coordenadas del viaje al servidor OSRM configurado. El servidor de demostración público está fuera de nuestro control; recomendamos uno propio.

## Conservación
Mientras mantengas la cuenta, o el plazo que elijas en «Conservar mis viajes» (30, 90, 180 o 365 días). La última posición compartida se sustituye con cada actualización. Al eliminar la cuenta se borran todos tus datos del servidor de inmediato; las copias de seguridad del proveedor se rotan según su política ([n] días).

## Tus derechos (RGPD arts. 15-22, LOPDGDD)
Acceso, rectificación, supresión, limitación, portabilidad y oposición, y retirar el consentimiento:
- **Acceso y portabilidad**: Perfil → «Descargar todos mis datos» (JSON).
- **Rectificación**: edita nombre, foto, vehículos y viajes en la app.
- **Supresión**: borra viajes o Perfil → «Eliminar mi cuenta».
- Por correo a [correo] para cualquier otra solicitud. Respondemos en un mes como máximo.

Si no estás conforme, puedes reclamar ante la **Agencia Española de Protección de Datos** (www.aepd.es).

## Menores
Trazo no está dirigida a menores de 14 años (art. 7 LOPDGDD). Si detectamos una cuenta de un menor de esa edad sin consentimiento de sus tutores, la eliminaremos.

## Seguridad
Conexiones cifradas (HTTPS/TLS). La sesión se guarda cifrada con una clave del almacén seguro de Android y no se incluye en copias de seguridad. No hay secretos del servidor dentro de la app.

## Aviso legal y uso responsable
No manipules el teléfono mientras conduces. Trazo no premia la velocidad y no debe usarse para competir en vía pública. Respeta la ley 6/2015 de tráfico y el Reglamento General de Circulación.

Última actualización: [fecha].

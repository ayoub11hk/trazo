# Compilar e instalar

## Requisitos
JDK 17, Android SDK (API 35) y Gradle 8.14 (o Android Studio Ladybug o posterior, que lo trae todo).

## Configuración (sin secretos en el código)
Crea `local.properties` en la raíz (está en `.gitignore`):

```properties
sdk.dir=/ruta/a/Android/Sdk
SUPABASE_URL=https://xxxx.supabase.co
SUPABASE_ANON_KEY=eyJ...            # clave «anon public», nunca la service_role
# MAP_MATCHING_URL=https://tu-osrm.example.com   # opcional
```

También se aceptan como variables de entorno. Sin ellas el APK funciona igual y pide los datos del servidor al abrirlo.

## APK de depuración
```bash
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## APK de release firmado
1. Crea un almacén de claves (guárdalo fuera del repositorio):
   `keytool -genkeypair -v -keystore trazo.jks -alias trazo -keyalg RSA -keysize 4096 -validity 10000`
2. Crea `keystore.properties` (también ignorado por git):
   ```properties
   TRAZO_KEYSTORE_FILE=/ruta/a/trazo.jks
   TRAZO_KEYSTORE_PASSWORD=...
   TRAZO_KEY_ALIAS=trazo
   TRAZO_KEY_PASSWORD=...
   ```
   o las mismas claves como variables de entorno.
3. `gradle assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

Sin firma configurada, `assembleRelease` genera un APK sin firmar.

## Con GitHub Actions
El flujo `.github/workflows/android.yml` se ejecuta en cada push:
- pruebas del núcleo y del backend;
- APK de depuración como artefacto `trazo-debug-apk`;
- APK de release firmado si existen los secretos `TRAZO_KEYSTORE_B64` (el `.jks` en base64), `TRAZO_KEYSTORE_PASSWORD`, `TRAZO_KEY_ALIAS` y `TRAZO_KEY_PASSWORD`.

Secretos opcionales `SUPABASE_URL` y `SUPABASE_ANON_KEY` para incluir el servidor en el APK.

## Primer uso en el teléfono
1. Concede ubicación «Permitir siempre», actividad física y notificaciones (la app explica cada una).
2. Quita la optimización de batería para Trazo; en Xiaomi, Huawei, Samsung y otros revisa además el ahorro de energía del fabricante (https://dontkillmyapp.com).
3. Perfil → «Probar sin conducir» reproduce un viaje simulado para comprobar todo sin moverte.

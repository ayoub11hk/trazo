# Backend (Supabase)

Todo el control de acceso está en `supabase/migrations/20261012000000_init.sql`: tablas, políticas RLS, disparadores y funciones RPC. La app solo usa la **URL del proyecto** y la clave **anon public**, que son públicas por diseño; nunca la `service_role`.

## 1. Crear el proyecto

1. En https://supabase.com crea un proyecto (región UE recomendada, p. ej. Frankfurt, por el RGPD).
2. **SQL Editor** → pega y ejecuta el contenido de `supabase/migrations/20261012000000_init.sql`.
   - Con la CLI: `supabase link --project-ref <ref>` y `supabase db push`.
3. La migración crea el bucket `avatars` y añade las tablas a la publicación `supabase_realtime`.

## 2. Autenticación

**Authentication → Providers → Email**: activado.
- *Confirm email*: recomendado. Si está activo, tras registrarse la app pide confirmar el correo antes de entrar.
- *Minimum password length*: 8.

**Authentication → Email Templates → Reset Password**: la app recupera la cuenta con un **código**, no con un enlace. Sustituye la plantilla por algo como:

```html
<h2>Recupera tu cuenta de Trazo</h2>
<p>Tu código es: <strong>{{ .Token }}</strong></p>
<p>Escríbelo en la app. Si no lo has pedido, ignora este correo.</p>
```

Para producción configura un SMTP propio (**Project Settings → Auth → SMTP**): el de Supabase tiene un límite bajo de envíos por hora.

## 3. Retención automática

Las preferencias «Conservar mis viajes» se aplican con `public.purge_expired_data()`. Programa su ejecución diaria con pg_cron (**Database → Extensions → pg_cron**):

```sql
select cron.schedule('trazo-retention', '30 3 * * *', $$ select public.purge_expired_data(); $$);
```

## 4. Conectar la app

- **En la compilación** (recomendado): variables `SUPABASE_URL` y `SUPABASE_ANON_KEY` (entorno o `local.properties`), o secretos del repositorio en GitHub con esos nombres para la CI.
- **En el primer arranque**: si el APK se compiló sin ellos, la app los pide.

## 5. Qué garantizan las reglas

Probado en `supabase/tests/10_access_rules.sql` (80 comprobaciones):

- Nadie ve la ubicación de otra persona salvo que sean amigos que la comparten, compartan grupo con la ubicación activada o estén en un convoy activo.
- El modo fantasma y la pausa eliminan la posición en el propio servidor; el servidor quita la velocidad si el usuario no la comparte y rechaza horas del futuro.
- Los viajes de otros solo se leen por RPC (`visible_trips`, `trip_points_for`, `convoy_trips`), que comprueban permisos y visibilidad de cada viaje.
- Los códigos de invitación no se pueden elegir ni modificar desde la app; solicitudes limitadas a 30 por hora.
- El rol anónimo no lee nada. No existen columnas ni funciones de pago o suscripción.

## 6. Ejecutar las pruebas en local

```bash
sudo service postgresql start
sudo -u postgres supabase/tests/run.sh
```

`00_supabase_shim.sql` reproduce lo mínimo de Supabase (roles, `auth.uid()`, `storage`) sobre un PostgreSQL normal.

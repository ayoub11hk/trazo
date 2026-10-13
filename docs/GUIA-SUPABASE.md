# Conectar Trazo a Supabase, paso a paso

Supabase es el servidor gratuito de Trazo: guarda cuentas, amigos, grupos, convoyes, viajes y fotos de perfil. La app ya está preparada; falta crear el proyecto en tu cuenta. Nada de esto toca la app instalada ni el código.

## 1. Crear el proyecto (plan Free)

1. Entra en https://supabase.com/dashboard y pulsa **New project**. Si te pide crear una organización primero, créala con el plan **Free**.
2. Nombre: `trazo`. Pon una **contraseña de base de datos** y guárdala tú (no me la mandes, no hace falta).
3. Región: **Central EU (Frankfurt)**, por el RGPD.
4. Pulsa **Create new project** y espera a que termine de prepararse (un par de minutos).

## 2. Crear la base de datos

1. Menú izquierdo → **SQL Editor** → **New query**.
2. Abre el archivo `trazo-base-de-datos.sql`, copia **todo** su contenido y pégalo.
3. Pulsa **Run**. Debe terminar con «Success. No rows returned».
   - Esto crea las tablas, las reglas de privacidad, el bucket de fotos `avatars` y el tiempo real.
   - Ejecútalo **una sola vez**. Si lo repites, dará errores de «already exists»; no pasa nada, pero no hace falta.

## 3. Correo y contraseñas

**Authentication → Sign In / Providers → Email**:
- **Enable Email provider**: activado.
- **Confirm email**: activado (recomendado). Al registrarte te llegará un correo para confirmar.
- **Minimum password length**: 8.

**Authentication → Emails → Reset Password**: sustituye el contenido de la plantilla por:

```html
<h2>Recupera tu cuenta de Trazo</h2>
<p>Tu código es: <strong>{{ .Token }}</strong></p>
<p>Escríbelo en la app. Si no lo has pedido, ignora este correo.</p>
```

Haz lo mismo en **Confirm signup** si quieres confirmar con código; si no, déjala como está.

Nota: el correo gratuito de Supabase solo envía unos pocos correos por hora. Para pruebas basta.

## 4. Borrado automático de viajes antiguos (opcional)

1. **Database → Extensions** → busca `pg_cron` → actívalo.
2. **SQL Editor** → ejecuta:

```sql
select cron.schedule('trazo-retention', '30 3 * * *', $$ select public.purge_expired_data(); $$);
```

## 5. Copiar las dos claves públicas

**Project Settings → Data API** (o **API**):
- **Project URL**: algo como `https://abcdxyz.supabase.co`
- **anon public** (en **API Keys**; si aparece la nueva «publishable key», también vale): un texto largo que empieza por `eyJ` o `sb_publishable_`.

Nunca copies ni compartas la clave **service_role** / **secret**. Trazo la rechaza a propósito.

## 6. Conectar

- Opción A: pega la URL y la clave anon en la pantalla que te muestra Trazo al abrirla.
- Opción B: mándamelas en el chat y te preparo un APK que ya las lleve dentro.

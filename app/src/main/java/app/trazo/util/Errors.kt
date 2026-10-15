package app.trazo.util

import app.trazo.data.remote.BackendNotConfiguredException
import java.io.IOException
import java.net.UnknownHostException

/** Turns technical failures into short Spanish messages a person can act on. */
fun Throwable.userMessage(): String {
    val m = (message ?: "").lowercase()
    return when {
        this is BackendNotConfiguredException -> "Falta configurar el servidor de Trazo."
        this is UnknownHostException || this is IOException || "unable to resolve host" in m || "timeout" in m ->
            "Sin conexión. Se reintentará cuando vuelva Internet."
        "invalid login credentials" in m -> "Correo o contraseña incorrectos."
        "user already registered" in m -> "Ya existe una cuenta con ese correo."
        "email not confirmed" in m -> "Confirma tu correo con el enlace que te hemos enviado."
        "password should be at least" in m -> "La contraseña es demasiado corta (mínimo 8 caracteres)."
        "token has expired" in m || "otp" in m && "invalid" in m -> "El código no es válido o ha caducado."
        "profiles_username_unique" in m -> "Ese nombre de usuario ya lo usa otra persona."
        "profiles_username_format" in m -> "El nombre de usuario debe tener de 3 a 20 letras, números, puntos o guiones bajos."
        "profiles_bio_length" in m -> "La presentación no puede pasar de 140 caracteres."
        "'username' column" in m || "'bio' column" in m || ("find_users" in m || "live_trail" in m || "send_friend_request_to" in m) && ("not find" in m || "does not exist" in m || "pgrst202" in m) ->
            "Falta actualizar la base de datos de Trazo (ver la guía de la migración 15/10)."
        "user not found" in m -> "Ese usuario ya no existe."
        "that is you" in m -> "Ese eres tú."
        "invalid code" in m -> "Ese código no existe."
        "your own code" in m -> "Ese es tu propio código."
        "too many requests" in m || "rate limit" in m -> "Demasiados intentos. Espera un poco."
        "only friends" in m || "you can only invite friends" in m -> "Solo puedes invitar a amigos."
        "finished" in m -> "Ese convoy ya ha terminado."
        "not a member" in m || "not an active member" in m -> "No formas parte de ese grupo o convoy."
        "only the owner" in m || "only the organiser" in m -> "Solo quien lo creó puede hacer eso."
        "trip not found" in m -> "Ese viaje no existe o ya no tienes permiso para verlo."
        "jwt" in m || "not authenticated" in m || "sesión no iniciada" in m -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
        else -> "Algo ha fallado: ${message ?: javaClass.simpleName}"
    }
}

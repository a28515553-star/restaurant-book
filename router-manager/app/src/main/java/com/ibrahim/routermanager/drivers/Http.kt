package com.ibrahim.routermanager.drivers

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String) : Exception(message)

/** Minimal blocking HTTP call; always invoke from Dispatchers.IO. */
fun httpCall(
    method: String,
    url: String,
    body: String? = null,
    basicAuth: Pair<String, String>? = null,
): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/json")
        basicAuth?.let { (u, p) ->
            val token = Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val hint = if (code == 401) "اسم المستخدم أو كلمة المرور غير صحيحة" else "HTTP $code"
            throw HttpException(code, hint)
        }
        return text
    } finally {
        conn.disconnect()
    }
}

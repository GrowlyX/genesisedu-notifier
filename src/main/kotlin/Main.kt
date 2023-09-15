import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.io.File
import java.util.*

private var currentSessionId: String? = null

object GenesisProps
{
    private val handle = Properties()
        .apply {
            load(
                File("configuration.properties")
                    .inputStream()
            )
        }

    fun username() = handle["username"]
    fun password() = handle["password"]
    fun organization() = handle["organization"]
}

object SessionIDInterceptor : CookiesStorage
{
    private val handle = AcceptAllCookiesStorage()
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = handle.addCookie(requestUrl, cookie)
        .apply {
            if (cookie.name == "JSESSIONID")
            {
                val sessionId = cookie.value
                if (currentSessionId != sessionId)
                {
                    currentSessionId = sessionId
                    println("updating to new session id -> $currentSessionId")
                }
            }
        }

    override fun close() = handle.close()
    override suspend fun get(requestUrl: Url) = handle.get(requestUrl)
}

fun ensureConfigCreated() = check(
    File("configuration.properties").exists()
) {
    "Config file is not created! Read configuration.properties.example and rename it to configuration.properties."
}

val client = HttpClient(CIO) {
    install(HttpCookies) {
        storage = SessionIDInterceptor
    }
}

suspend fun updateSessionId()
{
    val req = client
        .preparePost(
            "https://parents.c1.genesisedu.net/${
                GenesisProps.organization()
            }/sis/j_security_check"
        ) {
            contentType(ContentType.parse("application/x-www-form-urlencoded"))
            setBody(
                "idTokenString=&j_username=${
                    GenesisProps.username()
                }&j_password=${
                    GenesisProps.password()
                }"
            )
        }

    req.execute()
}

suspend fun main()
{
    ensureConfigCreated()
    updateSessionId()
}

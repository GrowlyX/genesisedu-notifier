import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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

    val courses by lazy {
        handle["courses"].toString().split(",")
    }

    fun username() = handle["username"]
    fun password() = handle["password"]
    fun organization() = handle["organization"]
    fun studentId() = handle["student_id"]
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

val courses = mutableMapOf<Pair<Int, Int>, String>()

suspend fun buildCourseIndexes()
{
    val response = fetchStudentDataSubPage("")

    println(response)
    println(response.split("\n")
        .filter {
            it.contains("goToCourseSummary")
        })
}

val client = HttpClient(CIO) {
    install(ContentEncoding) {
        gzip(0.9F)
    }
    install(HttpCookies) {
        storage = SessionIDInterceptor
    }
}

val defaultRequestHeaders = mutableMapOf(
    "Accept-Encoding" to "gzip, deflate, br",
    "Cache-Control" to "max-age=0",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
)

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

suspend fun fetchStudentDataSubPage(subPage: String): String
{
    val urlString = "https://parents.c1.genesisedu.net/${
        GenesisProps.organization()
    }/parents?tab1=studentdata&tab2=gradebook&tab3=coursesummary&studentid=${
        GenesisProps.studentId()
    }&action=form$subPage"

    return client
        .prepareGet(urlString) {
            headers {
                defaultRequestHeaders.forEach { (t, u) -> append(t, u) }
            }
        }
        .execute()
        .bodyAsText()
}

suspend fun main()
{
    ensureConfigCreated()
    updateSessionId()
    println(
        "Listening to courses ${
            GenesisProps.courses
        }"
    )

    buildCourseIndexes()

    /*fetchStudentDataSubPage(
        "&courseCode=123&courseSection=1&mp=MP1"
    )*/
}

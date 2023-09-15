import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import it.skrape.core.htmlDocument
import it.skrape.matchers.toBe
import it.skrape.matchers.toContain
import it.skrape.selects.html5.b
import it.skrape.selects.html5.div
import it.skrape.selects.html5.style
import it.skrape.selects.text
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

val courses = mutableMapOf<Int, Int>()

suspend fun buildCourseIndexes()
{
    val response = fetchStudentDataSubPage("weeklysummary")
    val pattern = "showAssignmentsByMPAndCourse\\('\\d+', '\\d'\\)".toRegex()
    val matches = pattern.findAll(response)

    val parsedCoursePairs = matches
        .map {
            val functionDecEnd = it.range.last
            response.substring(
                (functionDecEnd + 2)..(functionDecEnd + 10)
            )
        }
        .filter {
            it.startsWith("'")
        }
        .map {
            it.split(",")
        }
        .map { codes ->
            codes
                .map {
                    it.removeSurrounding("'")
                }
                .map { it.toInt() }
        }
        .associateBy { it.first() }
        .mapValues {
            it.value.last()
        }

    courses.putAll(parsedCoursePairs)

    courses.forEach { (k, v) ->
        val resp = fetchStudentDataSubPage(
            page = "coursesummary",
            params = "&courseCode=$k&courseSection=$v"/** TODO required MP? + "&mp=MP1" **/
        )

        if (k == 320)
        {
            println(resp)
            htmlDocument(resp) {
                div {
                    findFirst {
                        text toContain "Marking Period"
                    }
                }
            }
        }
    }
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

            headers {
                defaultRequestHeaders.forEach { (t, u) -> append(t, u) }
            }
        }

    req.execute()
}

suspend fun fetchStudentDataSubPage(page: String, params: String = ""): String
{
    val urlString = "https://parents.c1.genesisedu.net/${
        GenesisProps.organization()
    }/parents?tab1=studentdata&tab2=gradebook&tab3=$page&studentid=${
        GenesisProps.studentId()
    }&action=form$params"

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

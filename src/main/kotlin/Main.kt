import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.send.WebhookEmbed
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import it.skrape.core.htmlDocument
import it.skrape.selects.html5.*
import kotlinx.coroutines.runBlocking
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
    fun studentId() = handle["student_id"]
    fun webhook() = handle["webhook"]
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

data class Assignment(
    val date: String,
    val name: String,
    var grade: String? = null
)
{
    enum class GradeClassifier(
        val range: IntRange
    )
    {
        Good(90..<200),
        Fine(80..<90),
        Bad(0..<80)
    }

    val gradePercentage: Int?
        get() = grade
            ?.removeSuffix("%")
            ?.toInt()

    fun classifyGrade() = GradeClassifier.entries
        .firstOrNull {
            (gradePercentage ?: -1) in it.range
        }
}

data class Course(
    val code: Int, val section: Int, var name: String? = null,
    val assignments: MutableList<Assignment> = mutableListOf(),
    val lockObject: Any = Any()
)
{
    fun buildCourseSummaryURL() = buildStudentDataSubPageURL(
        "coursesummary", "&courseCode=$code&courseSection=$section"
    )

    suspend fun courseSummary() = fetchStudentDataSubPage(
        page = "coursesummary",
        params = "&courseCode=$code&courseSection=$section"
    )
}

val courses = mutableListOf<Course>()

val coursePattern = "showAssignmentsByMPAndCourse\\('\\d+','\\d'\\)".toRegex()
val numericPattern = "\\d+".toRegex()
val dateMatcher = "[\\s\\S]* \\d+\\/\\d+".toRegex()
val percentageMatcher = "\\d+%".toRegex()
suspend fun buildCourseIndexes()
{
    val response = fetchStudentDataSubPage("weeklysummary")
    val matches = coursePattern.findAll(response)

    val parsedCoursePairs = matches
        .map {
            val results = response.substring(
                it.range.first..it.range.last
            )
            numericPattern.findAll(results).toList()
                .map(MatchResult::value)
        }
        .toList()
        .map {
            // we don't want to flatmap it here
            it.map(String::toInt)
        }

    courses.addAll(parsedCoursePairs.map {
        Course(code = it[0], section = it[1])
    })

    courses.forEach { course ->
        val summary = course.courseSummary()
        val courseName = htmlDocument(summary) {
            table {
                select {
                    findLast {
                        option {
                            withAttribute = "value" to "${course.code}:${course.section}"
                            findFirst { text }
                        }
                    }
                }
            }
        }

        course.name = courseName
    }
}

var initialIndexBuild = true
suspend fun rebuildAssignmentIndexes(client: WebhookClient)
{
    courses.forEach { course ->
        val summary = course.courseSummary()
        val assignments = mutableListOf<Assignment>()

        htmlDocument(summary) {
            table {
                tr {
                    withClass = "listrowodd"
                    findAll {
                        var date = ""
                        var name = ""
                        var someGrade: String? = ""

                        td {
                            findFirst {
                                date += text
                            }

                            findAll {
                                forEach {
                                    if ("%" in it.text)
                                    {
                                        val percentMatches = percentageMatcher.findAll(it.text)
                                            .toList()
                                            .firstOrNull()
                                        someGrade = percentMatches?.value
                                    }
                                }
                            }
                        }

                        b {
                            findFirst {
                                name = text
                            }
                        }

                        val matchResults = dateMatcher.findAll(date).toList()
                        if (matchResults.isNotEmpty())
                        {
                            assignments += Assignment(date = date, name = name, grade = someGrade)
                        }
                    }
                }
            }
        }

        if (course.assignments.size < assignments.size)
        {
            val prevSize = course.assignments.size
            val prevAssignments = course.assignments
            synchronized(course.lockObject) {
                course.assignments.clear()
                course.assignments.addAll(assignments)
            }

            val newAssignments = course.assignments
                .filterNot {
                    // might not work properly?
                    prevAssignments.any { assignment ->
                        assignment.date == it.date && assignment.name == it.name
                    }
                }

            if (!initialIndexBuild)
            {
                val embed = WebhookEmbedBuilder()
                    .setColor(0x00E3FF)
                    .setTitle(
                        WebhookEmbed.EmbedTitle(
                            "Grade posted",
                            course.buildCourseSummaryURL()
                        )
                    )
                    .setDescription(
                        """
                            New grades have been posted for your **${course.name}** course:
                            ${newAssignments.joinToString("\n") { "> ${it.name}: **${"You Did Fine."}**" }}
                            
                            Click the title to see additional grade details!
                        """.trimIndent()
                    )
                    .build()

                client.send(embed).join()
                client.send("@everyone").join()

                println("${course.name} received updates with ${assignments.size - prevSize} new assignments! Webhook message sent.")
            }
        }
    }

    initialIndexBuild = false
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

fun buildStudentDataSubPageURL(page: String, params: String = "") = "https://parents.c1.genesisedu.net/${
    GenesisProps.organization()
}/parents?tab1=studentdata&tab2=gradebook&tab3=$page&studentid=${
    GenesisProps.studentId()
}&action=form$params"

suspend fun fetchStudentDataSubPage(page: String, params: String = ""): String
{
    return client
        .prepareGet(
            buildStudentDataSubPageURL(page, params)
        ) {
            headers {
                defaultRequestHeaders.forEach { (t, u) -> append(t, u) }
            }
        }
        .execute()
        .bodyAsText()
}

fun main()
{
    val startMillis = System.currentTimeMillis()
    runBlocking {
        ensureConfigCreated()
        updateSessionId()

        buildCourseIndexes()
        println("Built course indexes. ${courses.size} courses found.")
    }

    val client = WebhookClient
        .withUrl(
            GenesisProps.webhook().toString()
        )

    while (true)
    {
        val initial = initialIndexBuild
        if (initial)
        {
            println("Please wait as we rebuild course indexes...")
        }

        runBlocking {
            rebuildAssignmentIndexes(client)
        }

        if (initial)
        {
            println(
                "Rebuilt assignment indexes. Took ${
                    (System.currentTimeMillis() - startMillis) / 1000L
                } seconds to initialize the app."
            )
            println("Assignment indexes will be updated every minute. Please keep this app open!")
        }

        Thread.sleep(1000L * 60L)
    }
}

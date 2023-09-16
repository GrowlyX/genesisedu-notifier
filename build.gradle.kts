import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.9.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

group = "gg.growly.scrape"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktor_version: String by project

dependencies {
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-encoding:$ktor_version")

    implementation("it.skrape:skrapeit:1.2.2")
    testImplementation("junit:junit:4.13.2")

    implementation("club.minnced:discord-webhooks:0.8.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    archiveFileName.set(
        "on-bot-kotlin-${project.name}.jar"
    )
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}

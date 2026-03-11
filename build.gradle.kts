plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    application
}

group = "com.example"
version = "1.0.0"

application {
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.2.3"

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.9.0")

    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:1.5.15")
}

kotlin {
    jvmToolchain(17)
}

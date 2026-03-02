plugins {
    kotlin("jvm") version "2.2.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("de.fabmax.kool:kool-core:0.19.0")
    implementation("de.fabmax.kool:kool-physics:0.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
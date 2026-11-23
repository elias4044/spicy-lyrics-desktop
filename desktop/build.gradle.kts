plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
application {
    mainClass.set("spicy.desktop.MainKt")
    applicationName = "SpicyLyrics"
}

tasks.jar {
    val libraries = configurations.runtimeClasspath
    inputs.files(libraries)
    doFirst {
        manifest.attributes("Class-Path" to libraries.get().files.joinToString(" ") { it.name })
    }
}

// Plain JVM Kotlin with no Android SDK: the lyrics sources, blends, matching, parsers, models and
// the lyric clock. The Android app depends on it, and a desktop app can too.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_11
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("javax.inject:javax.inject:1")
    api("net.sf.kxml:kxml2:2.3.0")
    if (!project.hasProperty("withoutJapaneseDictionary")) {
        runtimeOnly("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    }
    testImplementation("junit:junit:4.13.2")
}

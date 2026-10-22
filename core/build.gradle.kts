plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    maxHeapSize = "2g"
    // The decoder test uses a sample ORF supplied via -Pdarkhall.sample=/path/to/file.orf
    systemProperty("darkhall.sample", (project.findProperty("darkhall.sample") ?: "") as String)
    systemProperty("darkhall.sampleRef", (project.findProperty("darkhall.sampleRef") ?: "") as String)
    systemProperty("darkhall.outDir", (project.findProperty("darkhall.outDir") ?: "") as String)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

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
    // Desktop OpenGL ES for testing the GPU shaders (Mesa llvmpipe or ANGLE/SwiftShader).
    testImplementation(libs.lwjgl)
    testImplementation(libs.lwjgl.egl)
    testImplementation(libs.lwjgl.opengles)
    testRuntimeOnly(variantOf(libs.lwjgl) { classifier("natives-linux") })
    testRuntimeOnly(variantOf(libs.lwjgl.opengles) { classifier("natives-linux") })
}

tasks.withType<Test> {
    maxHeapSize = "3g"
    // The decoder test uses a sample ORF supplied via -Pdarkhall.sample=/path/to/file.orf
    systemProperty("darkhall.sample", (project.findProperty("darkhall.sample") ?: "") as String)
    systemProperty("darkhall.sampleRef", (project.findProperty("darkhall.sampleRef") ?: "") as String)
    systemProperty("darkhall.outDir", (project.findProperty("darkhall.outDir") ?: "") as String)
    // Multi-camera decode test: a directory of sample raws plus a LibRaw reference.tsv.
    systemProperty("darkhall.rawsDir", (project.findProperty("darkhall.rawsDir") ?: "") as String)
    systemProperty("darkhall.rawsFilter", (project.findProperty("darkhall.rawsFilter") ?: "") as String)
    systemProperty("darkhall.looksDir", rootProject.file("app/src/main/assets/looks").absolutePath)
    systemProperty("darkhall.hald", (project.findProperty("darkhall.hald") ?: "") as String)
    // GPU tests: -Pdarkhall.gles=mesa (default) or angle; skipped when no EGL is available.
    val gles = (project.findProperty("darkhall.gles") ?: "mesa") as String
    systemProperty("darkhall.gles", gles)
    systemProperty("darkhall.angleDir", (project.findProperty("darkhall.angleDir") ?: "") as String)
    if (gles == "angle") {
        val dir = (project.findProperty("darkhall.angleDir") ?: "") as String
        environment("VK_ICD_FILENAMES", "$dir/vk_swiftshader_icd.json")
        environment("LD_LIBRARY_PATH", dir)
        environment("ANGLE_DEFAULT_PLATFORM", "swiftshader")
    }
    if (gles == "mesa") {
        environment("EGL_PLATFORM", "surfaceless")
        environment("LIBGL_ALWAYS_SOFTWARE", "1")
    }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

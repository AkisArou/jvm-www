plugins {
    application
}

dependencies {
    implementation(project(":websocket-core"))
    implementation(project(":websocket-okhttp")) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
        exclude(group = "com.squareup.okhttp3", module = "okhttp-android")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
}

application {
    mainClass.set(
        "io.github.akisarou.jvmwww.web.websocket.okhttp.testkit.OkHttpWebSocketTransportConformance"
    )
}

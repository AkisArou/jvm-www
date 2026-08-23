plugins {
    application
}

dependencies {
    implementation(project(":runtime-core"))
    implementation(project(":runtime-testkit"))
    implementation(project(":web-events"))
    implementation(project(":web-fetch-core"))
    implementation(project(":web-fetch-okhttp")) {
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
        "io.github.akisarou.jvmwww.web.fetch.okhttp.testkit.OkHttpFetchTransportConformance"
    )
}

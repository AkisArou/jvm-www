plugins {
    application
}

dependencies {
    implementation(project(":runtime-core"))
    implementation(project(":runtime-testkit"))
    implementation(project(":web-encoding"))
    implementation(project(":web-url"))
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
    mainClass.set("io.github.akisarou.jvmwww.web.url.testkit.URLSearchParamsConformance")
}

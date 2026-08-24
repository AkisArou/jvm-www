plugins {
    `java-library`
}

dependencies {
    api(project(":runtime-core"))
    implementation(project(":web-encoding"))
    api(project(":web-url"))
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

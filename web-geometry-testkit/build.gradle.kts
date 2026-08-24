plugins {
    application
}

dependencies {
    implementation(project(":web-geometry"))
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
    mainClass.set("io.github.akisarou.jvmwww.web.geometry.testkit.GeometryConformance")
}

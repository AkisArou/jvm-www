plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":web-fetch-core"))
    api("com.squareup.okhttp3:okhttp:5.5.0")
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

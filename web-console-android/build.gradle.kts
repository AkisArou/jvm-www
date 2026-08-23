plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":web-console"))
    compileOnly("com.google.android:android:4.1.1.4") {
        isTransitive = false
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

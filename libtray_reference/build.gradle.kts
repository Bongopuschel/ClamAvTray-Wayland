// Lokaler ClamAV-Tray-Fork der Apache-2.0-lizenzierten libtray-Bibliothek.
// Nur fuer publishToMavenLocal; keine Signatur und kein Central-Upload.
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

group = "local.clamav"
version = "0.2.0-clamav1"

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
        freeCompilerArgs.add("-jvm-default=enable")
    }
}

dependencies {
    api(libs.slf4j.api)
}

publishing {
    publications {
        create<MavenPublication>("libtrayLocal") {
            from(components["java"])
        }
    }
}

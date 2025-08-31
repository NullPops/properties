import org.jreleaser.gradle.plugin.tasks.JReleaserDeployTask
import org.jreleaser.model.Active
import org.jreleaser.model.Signing

plugins {
    kotlin("jvm") version "2.2.0"
    `java-library`
    `maven-publish`
    id("org.jreleaser") version "1.19.0"
}

group = "io.github.nullpops"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.nullpops:eventbus:1.0.1")
    implementation("io.github.nullpops:logger:1.0.2")
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation(kotlin("test"))
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.withType<JReleaserDeployTask> {
    dependsOn("publish")
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri("${layout.buildDirectory.asFile.get().path }/staging-deploy")
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("properties")
                description.set("Atomic Kotlin properties")
                url.set("https://github.com/nullpops/properties")
                licenses {
                    license {
                        name.set("AGPL-3.0-only")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                        distribution.set("repo")
                    }
                    license {
                        name.set("NullPops Commercial License")
                        url.set("https://github.com/NullPops/properties/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("zeruth")
                        name.set("Tyler Bochard")
                        email.set("tylerbochard@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/nullpops/properties.git")
                    developerConnection.set("scm:git:ssh://github.com/nullpops/properties.git")
                    url.set("https://github.com/nullpops/properties")
                }
            }
        }
    }
}

jreleaser {
    signing {
        dryrun = false
        active.set(Active.ALWAYS)
        armored.set(true)
        mode = Signing.Mode.MEMORY
        providers.environmentVariable("JRELEASER_GPG_PUBLIC_KEY_PATH").orNull?.let {
            publicKey.set(file(it).readText())
        }
        providers.environmentVariable("JRELEASER_GPG_PRIVATE_KEY_PATH").orNull?.let {
            secretKey.set(file(it).readText())
        }

        passphrase.set(providers.environmentVariable("JRELEASER_GPG_PASSPHRASE"))
    }
    deploy {
        maven {
            mavenCentral {
                create("properties") {
                    active.set(Active.ALWAYS)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    stagingRepository("build/staging-deploy")
                }
            }
        }
    }
}
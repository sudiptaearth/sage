import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    kotlin("jvm")
    id("org.jetbrains.intellij") version "1.17.0"
}

group = "com.sage"
version = "0.1.3"

// IntelliJ Platform Plugin configuration
intellij {
    version.set("2024.2")
    type.set("IU") // IntelliJ IDEA Ultimate
    plugins.set(listOf("java"))
}

repositories {
    mavenCentral()
}

val nitriteVersion = "4.4.2" // Sync with sage-reader

dependencies {
    // Include sage-reader module
    implementation(project(":sage-reader"))
    
    // Nitrite and MVStore (same versions as reader)
    implementation("org.dizitart:nitrite:$nitriteVersion")
    implementation("org.dizitart:nitrite-mvstore-adapter:$nitriteVersion")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.test {
    useJUnitPlatform()
}

// Remove until-build restriction entirely so the plugin is compatible with all future IDE versions
tasks.patchPluginXml {
    untilBuild.set("")
}

// Signing (optional - only runs if certificate env vars are set)
tasks.signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
}

// Publishing - requires PUBLISH_TOKEN env var set locally, never hardcode the token here
tasks.publishPlugin {
    token.set(System.getenv("PUBLISH_TOKEN"))
}

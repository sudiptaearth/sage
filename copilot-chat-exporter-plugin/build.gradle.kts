import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    kotlin("jvm")
    id("org.jetbrains.intellij") version "1.17.0"
}

group = "com.copilotexport"
version = "0.1.0"

// IntelliJ Platform Plugin configuration
intellij {
    version.set("2024.2")
    type.set("IU") // IntelliJ IDEA Ultimate
    plugins.set(listOf("java"))
}

repositories {
    mavenCentral()
}

val nitriteVersion = "4.4.2" // Sync with copilot-chat-reader

dependencies {
    // Include copilot-chat-reader module
    implementation(project(":copilot-chat-reader"))
    
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

// Override patchPluginXml to prevent it from restricting until-build
tasks.patchPluginXml {
    untilBuild.set("999.*")
}

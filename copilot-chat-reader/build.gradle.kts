import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

group = "com.copilotexport.reader"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Pinned version, confirmed working against real GitHub Copilot .db files.
// Keep this in sync with copilot-chat-exporter-plugin's build.gradle.kts.
val nitriteVersion = "4.4.2"

dependencies {
    implementation("org.dizitart:nitrite:$nitriteVersion")
    implementation("org.dizitart:nitrite-mvstore-adapter:$nitriteVersion")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// NOTE: deliberately NOT using `kotlin { jvmToolchain(11) }` here.
// jvmToolchain() tells Gradle to go
// *find or download* a matching standalone JDK, which fails on machines
// without toolchain auto-provisioning configured ("Cannot find a Java
// installation... Toolchain download repositories have not been
// configured"). Setting the Kotlin compile task's jvmTarget directly instead
// just targets that bytecode level using whichever JDK is already running
// Gradle (IntelliJ's bundled JBR is JDK 17+, which is fine -- Nitrite and
// Kotlin both only require 11+).
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
    }
}

// The Kotlin Gradle plugin applies the Java plugin under the hood, so a
// `compileJava` task exists even though this project has no .java sources --
// and by default it targets whatever JDK is running Gradle (e.g. 21), which
// doesn't match compileKotlin's jvmTarget above. Gradle then fails with
// "Inconsistent JVM-target compatibility detected for tasks 'compileJava'
// (21) and 'compileKotlin' (11)". Pinning both to the same target (11) here
// -- via sourceCompatibility/targetCompatibility, not a toolchain block, for
// the same reason explained above -- keeps them consistent.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass.set("com.copilotexport.reader.CliKt")
}

tasks.test {
    useJUnitPlatform()
}

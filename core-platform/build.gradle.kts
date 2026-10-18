plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-model"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.processTestResources {
    from(rootProject.layout.projectDirectory.dir("platforms")) {
        include("mimir-platform-registry.v2.json")
        include("mimir-platform-certification.v1.json")
    }
}

val platformSupportGate by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies committed platform registry artifacts without regenerating source data."
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/platform-registry/verify-platform-registry.mjs", "--offline")
}

tasks.test {
    useJUnit()
}

tasks.named("check") {
    dependsOn(platformSupportGate)
}

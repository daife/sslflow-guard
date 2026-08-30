plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.sslflowguard"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.10.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

// Keep the ordinary JAR only as a diagnostic thin artifact.
tasks.jar {
    archiveBaseName.set("sslflow-guard")
    archiveClassifier.set("thin")
}

tasks.shadowJar {
    archiveBaseName.set("sslflow-guard")
    archiveClassifier.set("")

    // Avoid colliding with Forge/NeoForge/Fabric/other agents that also ship ASM.
    relocate("org.objectweb.asm", "dev.sslflowguard.internal.asm")

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    manifest {
        attributes(
            "Premain-Class" to "dev.sslflowguard.SSLFlowGuardAgent",
            "Agent-Class" to "dev.sslflowguard.SSLFlowGuardAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            "Implementation-Title" to "SSLFlow Guard",
            "Implementation-Version" to project.version
        )
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}


tasks.wrapper {
    gradleVersion = "9.7.1"
    distributionType = org.gradle.api.tasks.wrapper.Wrapper.DistributionType.BIN
    distributionSha256Sum = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
}

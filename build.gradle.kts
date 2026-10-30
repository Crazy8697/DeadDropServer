plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "net.darkrelay"
version = "0.5.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.6.0")
    implementation("io.ktor:ktor-server-cio-jvm:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    // libsodium (crypto_box, sealed boxes, secretstream) through lazysodium; the native library ships inside the jar
    implementation("com.goterl:lazysodium-java:5.2.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("ch.qos.logback:logback-classic:1.6.6")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("net.darkrelay.deaddropserver.MainKt")
    applicationName = "deaddrop-server"
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = false }
}

// Every component the image ships is covered in THIRD-PARTY-NOTICES.md (0.5.0): installDist refuses otherwise.
val licenseGate = tasks.register("licenseGate") {
    group = "verification"
    description = "Every shipped component is covered in THIRD-PARTY-NOTICES.md."
    val notices = project.file("THIRD-PARTY-NOTICES.md")
    val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    inputs.file(notices)
    doLast {
        val covered = Regex("`([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+)`").findAll(notices.readText())
            .map { it.groupValues[1] }.toSet()
        val shipped = sortedSetOf<String>()
        val seen = mutableSetOf<ResolvedComponentResult>()
        fun walk(c: ResolvedComponentResult) {
            if (!seen.add(c)) return
            (c.id as? ModuleComponentIdentifier)?.let { shipped += "${it.group}:${it.module}" }
            c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        walk(root.get())
        val missing = shipped - covered
        if (missing.isNotEmpty()) {
            throw GradleException("License check: these shipped components are not in THIRD-PARTY-NOTICES.md: " +
                missing.joinToString() + ". Add each one with its version, license and upstream URL.")
        }
        logger.lifecycle("License check: all ${shipped.size} shipped components are covered in THIRD-PARTY-NOTICES.md")
    }
}

// a plain list of the shipped components with versions, for keeping THIRD-PARTY-NOTICES.md current
tasks.register("shippedComponents") {
    group = "help"
    val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        val out = sortedSetOf<String>()
        val seen = mutableSetOf<ResolvedComponentResult>()
        fun walk(c: ResolvedComponentResult) {
            if (!seen.add(c)) return
            (c.id as? ModuleComponentIdentifier)?.let { out += "${it.group}:${it.module}:${it.version}" }
            c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        walk(root.get())
        out.forEach { println(it) }
    }
}

tasks.named("installDist") { dependsOn(licenseGate) }

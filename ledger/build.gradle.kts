plugins {
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
    implementation("org.jooq:jooq:3.21.9")
    compileOnly("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    runtimeOnly("org.postgresql:postgresql:42.7.8")
}

tasks.register<JavaExec>("runLedgerSettlements") {
    group = "application"
    description = "Runs the local ledger settlement consumer."
    mainClass.set("br.com.mb.ledger.LedgerSettlementApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runLedgerReconciliation") {
    group = "application"
    description = "Retries durable pending settlement instructions, independently of Kafka."
    mainClass.set("br.com.mb.ledger.LedgerReconciliationApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

// Native builds are optional; ordinary JVM tests do not require GraalVM.
graalvmNative {
    useArgFile.set(true)
    toolchainDetection.set(false)
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            mainClass.set("br.com.mb.ledger.LedgerSettlementApplication")
            imageName.set("mb-settlements")
            jvmArgs.add("-Xmx3000m")
            buildArgs.addAll("--no-fallback", "--gc=serial", "-Os", "--parallelism=2")
        }
    }
}

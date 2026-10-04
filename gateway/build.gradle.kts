plugins {
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ledger"))
    implementation(project(":command-log"))
}

tasks.register<JavaExec>("runGateway") {
    group = "application"
    description = "Runs the local gateway HTTP server."
    mainClass.set("br.com.mb.gateway.GatewayApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

// Native builds are optional; ordinary JVM tests do not require GraalVM.
graalvmNative {
    useArgFile.set(true)
    toolchainDetection.set(false)
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            mainClass.set("br.com.mb.gateway.GatewayApplication")
            imageName.set("mb-gateway")
            jvmArgs.add("-Xmx3000m")
            buildArgs.addAll("--no-fallback", "--gc=serial", "-Os", "--parallelism=2")
        }
    }
}

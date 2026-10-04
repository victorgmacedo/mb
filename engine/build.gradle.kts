plugins {
    id("org.graalvm.buildtools.native")
    id("com.google.protobuf")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
    implementation(project(":ledger"))
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("org.jooq:jooq:3.21.9")
    runtimeOnly("org.postgresql:postgresql:42.7.8")
    testRuntimeOnly("com.h2database:h2:2.4.240")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
}

tasks.register<JavaExec>("runEngine") {
    group = "application"
    description = "Runs the local engine command consumer."
    mainClass.set("br.com.mb.engine.EngineApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runBookSnapshots") {
    group = "application"
    description = "Periodically persists book snapshots from the durable journal."
    mainClass.set("br.com.mb.engine.BookSnapshotsApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

// Native builds are optional; ordinary JVM tests do not require GraalVM.
graalvmNative {
    toolchainDetection.set(false)
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            mainClass.set("br.com.mb.engine.EngineApplication")
            imageName.set("mb-engine")
            jvmArgs.add("-Xmx2300m")
            buildArgs.addAll("--no-fallback", "--gc=serial", "-Os", "--parallelism=2")
        }
        create("snapshots") {
            mainClass.set("br.com.mb.engine.BookSnapshotsApplication")
            imageName.set("mb-snapshots")
            classpath.from(sourceSets.main.get().runtimeClasspath)
            jvmArgs.add("-Xmx2300m")
            buildArgs.addAll("--no-fallback", "--gc=serial", "-Os", "--parallelism=2")
        }
    }
}

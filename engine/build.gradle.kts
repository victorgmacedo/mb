plugins {
    id("com.google.protobuf")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
    implementation(project(":ledger"))
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    runtimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.h2database:h2")
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

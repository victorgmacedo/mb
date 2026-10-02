plugins {
    id("com.google.protobuf")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
    implementation(project(":ledger"))
    implementation("com.google.protobuf:protobuf-java:4.36.2")
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

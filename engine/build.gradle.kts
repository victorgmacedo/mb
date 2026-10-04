dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
    implementation(project(":ledger"))
}

tasks.register<JavaExec>("runEngine") {
    group = "application"
    mainClass.set("br.com.mb.engine.EngineApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
}

tasks.register<JavaExec>("runEngine") {
    group = "application"
    description = "Runs the local engine command consumer."
    mainClass.set("br.com.mb.engine.EngineApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

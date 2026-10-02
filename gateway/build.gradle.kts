dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
}

tasks.register<JavaExec>("runGateway") {
    group = "application"
    description = "Runs the local gateway HTTP server."
    mainClass.set("br.com.mb.gateway.GatewayApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

plugins { java }

group = "br.com.mb"
version = "0.1.0-SNAPSHOT"

subprojects {
    apply(plugin = "java")
    group = rootProject.group
    version = rootProject.version
    java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.register("writeRuntimeClasspath") {
        dependsOn("classes")
        val output = layout.buildDirectory.file("runtime-classpath.txt")
        inputs.files(sourceSets.main.get().runtimeClasspath)
        outputs.file(output)
        doLast { output.get().asFile.writeText(sourceSets.main.get().runtimeClasspath.asPath) }
    }
    dependencies.add("testImplementation", dependencies.platform("org.junit:junit-bom:5.11.4"))
    dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter")
    dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

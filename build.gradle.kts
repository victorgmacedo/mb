plugins {
    java
    id("com.google.protobuf") version "0.10.0" apply false
}

group = "br.com.mb"
version = "0.1.0-SNAPSHOT"

val javaVersion = JavaLanguageVersion.of(27)

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    java {
        toolchain {
            languageVersion.set(javaVersion)
        }
        modularity.inferModulePath.set(true)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(27)
    }

    tasks.named<JavaCompile>("compileJava").configure {
        doFirst {
            options.compilerArgs.addAll(listOf("--module-path", classpath.asPath))
            classpath = files()
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies.add("testImplementation", dependencies.platform("org.junit:junit-bom:5.11.4"))
    dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter")
    dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

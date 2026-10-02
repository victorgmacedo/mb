plugins {
    java
    id("com.google.protobuf") version "0.10.0" apply false
}

group = "br.com.mb"
version = "0.1.0-SNAPSHOT"

val javaVersion = JavaLanguageVersion.of(27)
val slf4jVersion = "2.0.20"
val logbackVersion = "1.6.5"
val logstashLogbackEncoderVersion = "9.0"

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
    dependencies.add("implementation", "org.slf4j:slf4j-api:$slf4jVersion")
    dependencies.add("runtimeOnly", "ch.qos.logback:logback-classic:$logbackVersion")
    dependencies.add("runtimeOnly", "net.logstash.logback:logstash-logback-encoder:$logstashLogbackEncoderVersion")
}

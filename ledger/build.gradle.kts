dependencies {
    implementation(project(":shared"))
    implementation(project(":command-log"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
}

tasks.register<JavaExec>("runLedgerSettlements") {
    group = "application"
    description = "Runs the local ledger settlement consumer."
    mainClass.set("br.com.mb.ledger.LedgerSettlementApplication")
    classpath = sourceSets.main.get().runtimeClasspath
}

plugins {
    java
}

group = "br.rustland"
version = "1.4.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/")
}

dependencies {
    // Compila contra a API 1.21.4 (estavel). O plugin roda no Paper 26.x normalmente,
    // pois so usa classes basicas (evento de morte, config, comandos).
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // Para os placeholders do airdrop no scoreboard do TAB
    compileOnly("me.clip:placeholderapi:2.11.6")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("RustlandKillMoney")
    archiveVersion.set("")
}

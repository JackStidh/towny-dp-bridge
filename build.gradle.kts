plugins {
    java
}

group = "net.arcana"
version = "1.5.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.glaremasters.me/repository/towny/")   // Towny's own repo, per the Towny wiki (TownyAPI page)
}

// Set these to the versions your server runs (gradle.properties).
val paperVersion: String by project
val townyVersion: String by project

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("com.palmergames.bukkit.towny:towny:$townyVersion")
}

// Paper 26.1 and later need Java 25 (docs.papermc.io); a Java 25 toolchain needs Gradle 9.1 or newer
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

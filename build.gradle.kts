plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
}

group = "one.eim"
version = "1.1.1-26.2"
description = "Liberate your server from the RNG loving bourgeoisie! Paper plugin to enable RNG manipulation."

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(25)
    }

    runServer {
        minecraftVersion("26.2")
    }
}

bukkit {
    website = "https://github.com/Creeperuuu/RandomControl"
    authors = listOf("e-im", "lostmatter", "Creeperuuu")
    main = "one.eim.randomcontrol.RandomControl"
    apiVersion = "26.2"
}

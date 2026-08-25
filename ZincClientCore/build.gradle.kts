plugins {
    id("java")
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-releases")
    maven("https://repo.opencollab.dev/maven-snapshots")
}

dependencies {
    implementation("org.cloudburstmc.protocol:common")
    implementation("org.cloudburstmc.protocol:bedrock-codec")
    implementation("org.cloudburstmc.protocol:bedrock-connection")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
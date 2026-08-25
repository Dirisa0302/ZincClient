pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.opencollab.dev/maven-releases")
        maven("https://repo.opencollab.dev/maven-snapshots")
    }
}

rootProject.name = "ZincClientCore"

includeBuild("../CloudBurstProtocol/Protocol-3.0") {
    dependencySubstitution {
        substitute(module("org.cloudburstmc.protocol:common"))
            .using(project(":common"))
        substitute(module("org.cloudburstmc.protocol:bedrock-codec"))
            .using(project(":bedrock-codec"))
        substitute(module("org.cloudburstmc.protocol:bedrock-connection"))
            .using(project(":bedrock-connection"))
    }
}
plugins {
    id("zenithproxy.plugin.dev") version "1.0.1-SNAPSHOT"
}

group = "bot.reconnect"
version = "1.0.2"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

zenithProxyPlugin {
    templateProperties = mapOf(
        "version" to project.version,
        "mc_version" to "1.21.4",
        "plugin_id" to "fixed-reconnect-delay",
        "maven_group" to project.group,
    )
    javaReleaseVersion = JavaLanguageVersion.of(21)
}

repositories {
    maven("https://maven.2b2t.vc/releases")
    maven("https://maven.2b2t.vc/remote")
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:3.7.0+1.21.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("reconnect.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

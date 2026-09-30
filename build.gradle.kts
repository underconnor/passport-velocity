plugins { java }
group = "io.github.underconnor.passport"
version = "0.1.0-SNAPSHOT"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    manifest.attributes["Implementation-Version"] = project.version
}
dependencyLocking { lockAllConfigurations() }
// Velocity's API POM still names a mutable snapshot; pin its published timestamp.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.velocitypowered" && requested.name == "velocity-brigadier") {
            useVersion("1.0.0-20210613.082804-10")
            because("Keep the public plugin build reproducible")
        }
    }
}

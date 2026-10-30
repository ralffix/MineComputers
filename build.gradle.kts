plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.78"
}

group = "net.minecomputers"
version = "0.1.0"

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenCentral()
    // Create, Ponder and Flywheel (see https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1).
    maven {
        name = "Create"
        url = uri("https://maven.createmod.net")
    }
    // Registrate, which Create needs.
    maven {
        name = "Registrate"
        url = uri("https://maven.ithundxr.dev/snapshots")
    }
}

neoForge {
    version = property("neo_version") as String

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
            programArgument("--nogui")
        }
    }

    mods {
        create("minecomputers") {
            sourceSet(sourceSets.main.get())
        }
    }
}

val graalVersion = property("graal_version") as String
// Create for Minecraft 1.21.1 (versions from Create's own "Depending on Create" page).
val createVersion = "6.0.10-280"
val ponderVersion = "1.0.82"
val flywheelVersion = "1.0.6"
val registrateVersion = "MC1.21-1.3.0+67"

dependencies {
    // GraalJS runs the in-game JavaScript (milestone 2).
    // NeoForge needs the libraries on BOTH the compile classpath and the runtime classpath,
    // otherwise it compiles but throws NoClassDefFoundError at runtime.
    // We use the real jars (polyglot + js-language) and NOT org.graalvm.polyglot:js-community: that one is a
    // POM-only artifact, and its .pom file ended up on the game classpath, where FML tried to open it as a
    // jar and crashed with java.nio.file.ProviderNotFoundException (suspected cause, being tested).
    // js-language pulls in truffle-api, regex and the rest through its own dependencies.
    implementation("org.graalvm.polyglot:polyglot:$graalVersion")
    implementation("org.graalvm.js:js-language:$graalVersion")
    // String form: the plugin doesn't generate a type-safe Kotlin accessor for this configuration.
    "additionalRuntimeClasspath"("org.graalvm.polyglot:polyglot:$graalVersion")
    "additionalRuntimeClasspath"("org.graalvm.js:js-language:$graalVersion")

    // Create: the Servo is a Create kinetic block (it turns a shaft).
    implementation("com.simibubi.create:create-1.21.1:$createVersion:slim") {
        isTransitive = false
    }
    implementation("net.createmod.ponder:ponder-neoforge:$ponderVersion+mc1.21.1")
    compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:$flywheelVersion")
    runtimeOnly("dev.engine-room.flywheel:flywheel-neoforge-1.21.1:$flywheelVersion")
    implementation("com.tterrag.registrate:Registrate:$registrateVersion")
}

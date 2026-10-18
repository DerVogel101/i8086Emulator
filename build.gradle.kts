plugins {
    id("java")
}

// Host-independent rules shared with the Minecraft host.
sourceSets.main { java.srcDir("../emulator_i86_crafted/src/runtime/java") }

val launcher by sourceSets.creating {
    java.srcDir("../emulator_i86_crafted/src/debugger/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
    resources.srcDir("../emulator_i86_crafted/src/main/resources")
    resources.include("assets/emulator_i86_crafted/font/*")
}

configurations[launcher.implementationConfigurationName].extendsFrom(configurations.implementation.get())

sourceSets.test {
    compileClasspath += launcher.output
    runtimeClasspath += launcher.output
}

tasks.register<JavaExec>("runLauncher") {
    group = "application"
    description = "Run the Swing ROM launcher"
    classpath = launcher.runtimeClasspath
    mainClass = "dervogel101.de.launcher.LauncherMain"
}

group = "dervogel101.de"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains:annotations:15.0")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    val editorRom = providers.environmentVariable("KEYBOARD_EDITOR_ROM").orNull
    inputs.property("keyboardEditorRom", editorRom ?: "<unset>")
    if (!editorRom.isNullOrBlank()) inputs.file(file(editorRom)).withPropertyName("keyboardEditorRomImage")
    val keyboardRom = providers.environmentVariable("KEYBOARD_TEST_ROM").orNull
    inputs.property("keyboardTestRom", keyboardRom ?: "<unset>")
    if (!keyboardRom.isNullOrBlank()) inputs.file(file(keyboardRom)).withPropertyName("keyboardTestRomImage")
    val irqRom = providers.environmentVariable("IRQ_HANDLER_TEST_ROM").orNull
    inputs.property("irqHandlerTestRom", irqRom ?: "<unset>")
    if (!irqRom.isNullOrBlank()) {
        inputs.files(file(irqRom), file(irqRom).resolveSibling("rom.bin"))
            .withPropertyName("irqHandlerTestRomImages")
    }
}

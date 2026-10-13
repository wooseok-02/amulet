buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
}
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// Optional machine-local output directory avoids OneDrive locking generated files.
val localConfig = java.util.Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}
localConfig.getProperty("build.root")?.let { outputRoot ->
    allprojects { layout.buildDirectory.set(file("$outputRoot/${project.name}")) }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // KSP genera el código de Room (historial local).
    id("com.google.devtools.ksp") version "2.3.13" apply false
}


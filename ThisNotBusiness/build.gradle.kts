@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.konan.properties.Properties
// use an integer for version numbers
version = 2

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "THISNOTBUSINESS_PASSWORD", "\"${properties.getProperty("THISNOTBUSINESS_PASSWORD")}\"")
    }
}

cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

    description = "Live streams from the Thisnot.business"
    authors = listOf("AC")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://www.calciostreaming.cool/templates/calciostreaming1/images/icons/apple-touch-icon.png"
}
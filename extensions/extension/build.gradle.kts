extension {
    name = "extensions/spotify.mpe"
}

android {
    namespace = "app.spicetify.extension.spotify"
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 24
        val spicyKey = providers.gradleProperty("spicyLyrics.key").getOrElse("")
        buildConfigField("String", "SPICY_LYRICS_KEY", "\"${spicyKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    compileOnly("io.reactivex.rxjava3:rxjava:3.1.10")
    testImplementation("io.reactivex.rxjava3:rxjava:3.1.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

package app.spicetify.patches.spotify.clockdiag

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.spicetify.patches.spotify.extensions.extensionsPatch
import app.spicetify.patches.spotify.settings.settingsPatch
import app.spicetify.patches.spotify.spotifyCompatibility

private const val BRIDGE = "Lapp/spicetify/extension/spotify/extensions/SessionClockBridge;"
private const val BUILD = "9.1.80.2221"

@Suppress("unused")
val clockDiagPatch = bytecodePatch(
    name = "Spicy Lyrics",
    description = "Word-synced karaoke lyrics over Spotify's player: full-screen lyrics, " +
        "artwork background, tap to seek, and the native lyrics page for sharing. " +
        "Experimental; Spotify 9.1.80.2221.",
    default = false,
) {
    compatibleWith(spotifyCompatibility)
    dependsOn(settingsPatch, extensionsPatch)

    execute {
        val entry = mutableClassDefBy("Lp/khc0;").methods.singleOrNull {
            it.name == "k" && it.parameterTypes == listOf("Lp/gjc0;") && it.returnType == "V"
        } ?: throw PatchException(
            "Spicy Lyrics: Lp/khc0;.k(Lp/gjc0;)V not found. Use the verified Spotify $BUILD APK.")
        // Morphe compiles snippets with the target's register count; /range keeps p0/p1 valid.
        entry.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $BRIDGE->onKhc0(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }
}

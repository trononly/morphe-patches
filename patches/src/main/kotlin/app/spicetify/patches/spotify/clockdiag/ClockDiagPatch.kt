package app.spicetify.patches.spotify.clockdiag

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.spicetify.patches.spotify.extensions.extensionsPatch
import app.spicetify.patches.spotify.settings.settingsPatch
import app.spicetify.patches.spotify.spotifyCompatibility
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val DIAG = "Lapp/spicetify/extension/spotify/extensions/StateClockDiag;"
private const val BUILD = "9.1.80.2221"

/**
 * Throwaway diagnostic for the playback-clock investigation. Not a feature: it only logs.
 *
 * - [Lp/xic0;.b] is Spotify's media3 PlaybackStateCompat builder. Both of its `return-object`
 *   exits are logged after the state is built, so every constructed state is observed no matter
 *   which branch built it.
 * - [Lp/w9c0;.k] and [Lp/khc0;.k] are the two concrete `Lp/tj40;.k` implementations (media3 and
 *   support-v4 session managers). `tj40` itself is a `PUBLIC INTERFACE ABSTRACT`, so Morphe's
 *   callee-side injection cannot hook the interface method: abstract methods have no code item.
 *   Hooking both implementations instead shows which one actually executes at runtime.
 */
@Suppress("unused")
val clockDiagPatch = bytecodePatch(
    name = "Playback clock diagnostic",
    description = "Diagnostic only: logs every media3 PlaybackStateCompat built by Lp/xic0;.b " +
        "(state, position, speed, update time, thread) and every PlayerState delivered to the " +
        "w9c0/khc0 session managers. For clock verification on $BUILD. Also applies the Spicetify " +
        "extensions patch so the lyrics overlay mounts and the session clock can drive it.",
    default = false,
) {
    compatibleWith(spotifyCompatibility)
    // The overlay, the extensions list and extension startup all live behind the extensions
    // patch (InstalledPatches.extensions()): the PageActivity mount hook, enableSetting and the
    // player-bridge hook. Depending on it restores Spicy Lyrics visibility and mounting; without
    // it only the probes apply and the extension stays dormant.
    dependsOn(settingsPatch, extensionsPatch)

    execute {
        // 1. Lp/xic0;.b(Lp/fwn0;)PlaybackStateCompat: log after execution, on every exit.
        val stateBuilder = mutableClassDefBy("Lp/xic0;").methods.singleOrNull {
            it.name == "b" && it.parameterTypes == listOf("Lp/fwn0;") &&
                it.returnType == "Landroidx/media3/session/legacy/PlaybackStateCompat;"
        } ?: throw PatchException(
            "Playback clock diagnostic: Lp/xic0;.b(Lp/fwn0;) not found. Use the verified Spotify $BUILD APK.")
        val exits = stateBuilder.implementation!!.instructions.indices.filter { index ->
            stateBuilder.implementation!!.instructions[index].opcode == Opcode.RETURN_OBJECT
        }
        if (exits.size != 2) {
            throw PatchException(
                "Playback clock diagnostic: expected 2 return-object exits in Lp/xic0;.b, found ${exits.size}.")
        }
        // Descending so earlier indices stay valid while inserting.
        for (index in exits.sortedDescending()) {
            val register =
                (stateBuilder.implementation!!.instructions[index] as OneRegisterInstruction).registerA
            stateBuilder.addInstructions(index, """
                invoke-static {v$register}, ${DIAG}->onPlaybackState(Ljava/lang/Object;)V
            """.trimIndent())
        }

        // 2. Concrete Lp/tj40;.k implementations: log at entry (p1 is the Lp/gjc0; PlayerState).
        // NOTE: p-registers must be referenced with invoke-*/range here. Morphe compiles each
        // snippet as a dummy method body with the target's own register count, so p1 aliases a
        // high v-register in methods with more than 16 registers (w9c0.k has 18: p1 == v17),
        // which the 35c encoding (v0..v15) rejects. The failure surfaces only as the smali
        // parser dropping the instruction and compile() returning an empty collection.
        for ((owner, probe) in listOf("Lp/w9c0;" to "onW9c0", "Lp/khc0;" to "onKhc0")) {
            val entry = mutableClassDefBy(owner).methods.singleOrNull {
                it.name == "k" && it.parameterTypes == listOf("Lp/gjc0;") && it.returnType == "V"
            } ?: throw PatchException(
                "Playback clock diagnostic: $owner.k(Lp/gjc0;)V not found. Use the verified Spotify $BUILD APK.")
            entry.addInstructions(0, """
                invoke-static/range {p1 .. p1}, ${DIAG}->$probe(Ljava/lang/Object;)V
            """.trimIndent())
        }
    }
}

package com.neilturner.aerialviews.models.prefs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class SourceTogglesTest {
    // Reads the sources: Kotpref objects need an Android context, which JVM tests do not have.
    @Test
    fun `every stored source switch is covered`() {
        val files = File("src/main/java/com/neilturner/aerialviews/models/prefs").listFiles()!!
        // Stored switches are `enabled by booleanPref`; Sonos is music, not a media source.
        val declared =
            files.filter { it.name != "SonosPrefs.kt" }.flatMap { file ->
                var owner = ""
                file.readLines().mapNotNull { line ->
                    Regex("""^object (\w+)""").find(line)?.let { owner = it.groupValues[1] }
                    owner.takeIf { "var enabled by booleanPref(" in line }
                }
            }
        val covered =
            Regex(
                """(\w+)::enabled""",
            ).findAll(files.single { it.name == "SourceToggles.kt" }.readText()).map { it.groupValues[1] }
        assertEquals(12, declared.size)
        assertEquals(declared.sorted(), covered.sorted().toList())
    }
}

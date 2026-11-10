package com.neilturner.aerialviews.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PlaylistUpgradeTest {
    @TempDir lateinit var directory: File

    @Test
    fun `upgrade from early schemas retains offline playlist and state`() =
        runTest {
            for (version in listOf(1, 5)) {
                val filename = directory.resolve("playlist-$version.db").absolutePath
                val schemaFile =
                    listOf(
                        File("schemas/com.neilturner.aerialviews.data.AerialDatabase/$version.json"),
                        File("app/schemas/com.neilturner.aerialviews.data.AerialDatabase/$version.json"),
                    ).first { it.isFile }
                val schema =
                    Json
                        .parseToJsonElement(schemaFile.readText())
                        .jsonObject
                        .getValue("database")
                        .jsonObject
                BundledSQLiteDriver().open(filename).use { connection ->
                    schema.getValue("entities").jsonArray.forEach { entity ->
                        val obj = entity.jsonObject
                        val table = obj.getValue("tableName").jsonPrimitive.content
                        connection.execSQL(
                            obj
                                .getValue("createSql")
                                .jsonPrimitive.content
                                .replace("${'$'}{TABLE_NAME}", table),
                        )
                        obj["indices"]?.jsonArray?.forEach { index ->
                            connection.execSQL(
                                index.jsonObject
                                    .getValue("createSql")
                                    .jsonPrimitive.content
                                    .replace("${'$'}{TABLE_NAME}", table),
                            )
                        }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
                    connection.execSQL("PRAGMA user_version = $version")
                    connection.execSQL(
                        "INSERT INTO cached_media (playlistOrder, uri, type, source, shortDescription, pointsOfInterest, " +
                            "timeOfDay, scene, albumName, title) VALUES (0, 'file:///synthetic', 'IMAGE', 'LOCAL', '', '{}', " +
                            "'UNKNOWN', 'UNKNOWN', '', '')",
                    )
                    connection.execSQL("INSERT INTO playlist_state VALUES (1, 0, 0, 123, 'synthetic', 1, 0, 0, 0, 0)")
                }
                val db = Room.databaseBuilder<AerialDatabase>(name = filename).setDriver(BundledSQLiteDriver()).build()
                try {
                    val rows = db.playlistCacheDao().getAllMediaItemsOrdered()
                    assertEquals(listOf("file:///synthetic"), rows.map { it.uri })
                    assertNull(rows.single().pairPartner)
                    assertEquals(0, db.playlistCacheDao().getPlaylistState()?.mediaPosition)
                    assertEquals(1, db.playlistCacheDao().getPlaylistState()?.totalMediaItems)
                } finally {
                    db.close()
                }
            }
        }
}

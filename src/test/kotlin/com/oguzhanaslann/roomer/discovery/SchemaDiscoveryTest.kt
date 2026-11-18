package com.oguzhanaslann.roomer.discovery

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.common.timeoutRunBlocking
import com.oguzhanaslann.roomer.RoomErPlatformTestCase
import com.oguzhanaslann.roomer.TestSchemas

/** Discovery against a real VFS and project model: AC-01.x, AC-02.x. */
class SchemaDiscoveryTest : RoomErPlatformTestCase() {

    private fun discover(manualFolderUrl: String? = null): DiscoveryResult =
        timeoutRunBlocking { readAction { SchemaDiscovery(project).discover(manualFolderUrl) } }

    fun testLatestVersionOfDatabaseIsFound() {
        addFile("app/schemas/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))
        val latest = addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        addFile("app/schemas/com.acme.AppDb/notanumber.json", "{}")
        addFile("app/schemas/x/notanumber.json", "{}")

        val result = discover()
        val db = result.databases.single()
        assertEquals("com.acme.AppDb", db.name)
        assertEquals(2, db.latestVersion)
        assertEquals(latest.path, db.latestFilePath)
        assertEquals(latest, result.files[db.latestFilePath])
        assertNull(result.manualFolderPath)
    }

    fun testDatabasesInTwoModulesAreFound() {
        addFile("app/schemas/com.acme.AppDb/3.json", TestSchemas.schema(3, "users"))
        addFile("feature/data/schemas/com.acme.CacheDb/1.json", TestSchemas.schema(1, "cache"))
        addFile("feature/data/src/Main.java", "class Main {}")

        assertEquals(listOf("AppDb" to 3, "CacheDb" to 1), discover().databases.map { it.label to it.latestVersion })
    }

    fun testSameDatabaseNameInTwoModulesStaysDistinct() {
        addFile("app/schemas/com.acme.AppDb/3.json", TestSchemas.schema(3, "users"))
        addFile("lib/schemas/com.acme.AppDb/5.json", TestSchemas.schema(5, "users"))

        val databases = discover().databases
        assertEquals(2, databases.size)
        assertEquals(2, databases.map { it.id }.toSet().size)
        assertEquals(setOf("AppDb (app)", "AppDb (lib)"), databases.map { it.label }.toSet())
    }

    fun testPerVariantSchemaFoldersAreFound() {
        addFile("app/schemas/demoDebug/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))
        val debugLatest = addFile("app/schemas/demoDebug/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        addFile("app/schemas/fullRelease/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))
        addFile("app/schemas/notes/misc/1.json", "{}") // not a variant layout: "misc" is no qualified class name

        val result = discover()
        assertEquals(
            listOf("AppDb (app, demoDebug)" to 2, "AppDb (app, fullRelease)" to 1),
            result.databases.map { it.label to it.latestVersion },
        )
        assertEquals(debugLatest, result.files[result.databases.first().latestFilePath])
    }

    fun testBuildOutputIsSkipped() {
        addFile("app/build/intermediates/schemas/com.acme.AppDb/9.json", TestSchemas.schema(9, "users"))
        addFile("app/.hidden/schemas/com.acme.AppDb/8.json", TestSchemas.schema(8, "users"))
        assertTrue(discover().databases.isEmpty())
    }

    fun testManualFolderWithUnusualLayout() {
        val file = addFile("exported/room/com.acme.AppDb/4.json", TestSchemas.schema(4, "users"))
        assertTrue("not discoverable automatically", discover().databases.isEmpty())

        val databaseDir = file.parent
        for (folder in listOf(databaseDir, databaseDir.parent)) {
            val result = discover(folder.url)
            assertTrue(result.manualFolderHasSchemas)
            assertEquals(listOf("com.acme.AppDb" to 4), result.databases.map { it.name to it.latestVersion })
        }
        // An ancestor only matches the standard schemas/<db>/<n>.json layout.
        assertFalse(discover(databaseDir.parent.parent.url).manualFolderHasSchemas)
    }

    fun testManualFolderWithoutSchemas() {
        val folder = addFile("docs/readme.json", "{}").parent
        addFile("docs/x/notanumber.json", "{}")

        val result = discover(folder.url)
        assertFalse(result.manualFolderHasSchemas)
        assertTrue(result.databases.isEmpty())
        assertTrue(result.manualFolderPath!!.endsWith("docs"))

        val missing = discover(folder.url + "/does-not-exist")
        assertFalse(missing.manualFolderHasSchemas)
        assertTrue(missing.manualFolderPath!!.endsWith("does-not-exist"))
    }

    fun testManualFolderIsAddedToAutomaticResults() {
        addFile("app/schemas/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))
        val manual = addFile("exported/com.acme.OtherDb/2.json", TestSchemas.schema(2, "other")).parent.parent

        val result = discover(manual.url)
        assertEquals(listOf("AppDb", "OtherDb"), result.databases.map { it.label })
    }
}

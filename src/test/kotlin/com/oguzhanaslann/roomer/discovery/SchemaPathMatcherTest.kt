package com.oguzhanaslann.roomer.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure discovery logic: AC-01.1–01.3, FR-01–03. */
class SchemaPathMatcherTest {

    @Test
    fun `matches schemas - database - number json`() {
        val match = SchemaPathMatcher.match("/p/app/schemas/com.acme.AppDb/2.json")
        assertEquals(SchemaFileMatch("/p/app/schemas/com.acme.AppDb", "com.acme.AppDb", 2, "/p/app/schemas/com.acme.AppDb/2.json"), match)
    }

    @Test
    fun `non-numeric and misplaced files are ignored`() {
        assertNull(SchemaPathMatcher.match("/p/app/schemas/x/notanumber.json")) // AC-01.3
        assertNull(SchemaPathMatcher.match("/p/app/schemas/x/1.json.bak"))
        assertNull(SchemaPathMatcher.match("/p/app/schemas/x/-1.json"))
        assertNull(SchemaPathMatcher.match("/p/app/schemas/x/1.5.json"))
        assertNull(SchemaPathMatcher.match("/p/app/schemas/x/99999999999999.json"))
        assertNull(SchemaPathMatcher.match("/p/app/schemas/1.json"))
        assertNull(SchemaPathMatcher.match("/p/app/other/x/1.json"))
        assertNull(SchemaPathMatcher.match("/p/app/schemas/x/nested/1.json"))
        assertNull(SchemaPathMatcher.match("1.json"))
    }

    @Test
    fun `version of file name`() {
        assertEquals(12, SchemaPathMatcher.versionOf("12.json"))
        assertEquals(0, SchemaPathMatcher.versionOf("0.json"))
        assertNull(SchemaPathMatcher.versionOf(".json"))
        assertNull(SchemaPathMatcher.versionOf("12"))
        assertNull(SchemaPathMatcher.versionOf("v12.json"))
    }

    @Test
    fun `highest version wins`() {
        val files = listOf(1, 10, 2).map { "/p/app/schemas/com.acme.AppDb/$it.json" } + "/p/app/schemas/com.acme.AppDb/notanumber.json"
        val databases = SchemaPathMatcher.group(files.mapNotNull(SchemaPathMatcher::match), "/p")
        val db = databases.single()
        assertEquals("com.acme.AppDb", db.name)
        assertEquals("AppDb", db.simpleName)
        assertEquals(10, db.latestVersion) // numeric, not lexicographic
        assertEquals("/p/app/schemas/com.acme.AppDb/10.json", db.latestFilePath)
        assertEquals(listOf(1, 2, 10), db.versions)
        assertEquals("AppDb", db.label)
        assertEquals("com.acme.AppDb", db.stateKey)
    }

    @Test
    fun `databases in two modules are both found`() {
        val files = listOf("/p/app/schemas/com.acme.AppDb/1.json", "/p/feature/data/schemas/com.acme.CacheDb/3.json")
        val databases = SchemaPathMatcher.group(files.mapNotNull(SchemaPathMatcher::match), "/p")
        assertEquals(listOf("AppDb", "CacheDb"), databases.map { it.label })
        assertEquals(listOf(1, 3), databases.map { it.latestVersion })
    }

    @Test
    fun `same database name in two modules stays distinct and gets a module label`() {
        val files = listOf("/p/app/schemas/com.acme.AppDb/1.json", "/p/lib/core/schemas/com.acme.AppDb/4.json")
        val databases = SchemaPathMatcher.group(files.mapNotNull(SchemaPathMatcher::match), "/p")
        assertEquals(2, databases.size)
        assertEquals(setOf("/p/app/schemas/com.acme.AppDb", "/p/lib/core/schemas/com.acme.AppDb"), databases.map { it.id }.toSet())
        assertEquals(setOf("AppDb (app)", "AppDb (lib/core)"), databases.map { it.label }.toSet())
        assertEquals(2, databases.map { it.stateKey }.toSet().size)
    }

    @Test
    fun `same simple name with different packages is labelled with the full name`() {
        val files = listOf("/p/app/schemas/com.a.Db/1.json", "/p/app/schemas/com.b.Db/1.json")
        val databases = SchemaPathMatcher.group(files.mapNotNull(SchemaPathMatcher::match), "/p")
        assertEquals(listOf("com.a.Db", "com.b.Db"), databases.map { it.label })
        assertEquals(listOf("com.a.Db", "com.b.Db"), databases.map { it.stateKey })
    }

    @Test
    fun `manual folder may be the schemas dir, a database dir or an ancestor`() {
        // database dir, whatever its parent is called
        assertEquals("com.acme.AppDb", SchemaPathMatcher.matchInFolder("/x/exported/com.acme.AppDb", "/x/exported/com.acme.AppDb/3.json")?.databaseName)
        // schemas dir with an unusual name
        assertEquals("com.acme.AppDb", SchemaPathMatcher.matchInFolder("/x/exported", "/x/exported/com.acme.AppDb/3.json")?.databaseName)
        // ancestor: the normal rule applies below it
        assertNotNull(SchemaPathMatcher.matchInFolder("/x", "/x/a/b/schemas/com.acme.AppDb/3.json"))
        assertNull(SchemaPathMatcher.matchInFolder("/x", "/x/a/b/other/com.acme.AppDb/3.json"))
        assertNull(SchemaPathMatcher.matchInFolder("/x/exported", "/x/exported/com.acme.AppDb/readme.json"))
        assertNull(SchemaPathMatcher.matchInFolder("/x/exported", "/elsewhere/com.acme.AppDb/3.json"))
    }

    @Test
    fun `event path filters`() {
        assertTrue(SchemaPathMatcher.isUnderSchemasDir("/p/app/schemas"))
        assertTrue(SchemaPathMatcher.isUnderSchemasDir("/p/app/schemas/com.acme.AppDb/2.json"))
        assertFalse(SchemaPathMatcher.isUnderSchemasDir("/p/app/src/Main.kt"))
        assertFalse(SchemaPathMatcher.isUnderSchemasDir("/p/app/schemas2/x/1.json"))
        assertTrue(SchemaPathMatcher.isSameOrAncestor("/p/app", "/p/app/schemas/x"))
        assertTrue(SchemaPathMatcher.isSameOrAncestor("/p/app", "/p/app"))
        assertFalse(SchemaPathMatcher.isSameOrAncestor("/p/app", "/p/application/schemas"))
    }
}

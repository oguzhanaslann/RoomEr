package com.oguzhanaslann.roomer.navigation

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.oguzhanaslann.roomer.RoomErPlatformTestCase
import com.oguzhanaslann.roomer.TestSchemas
import com.oguzhanaslann.roomer.model.EntityKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.UiInterceptors
import com.intellij.util.ui.UIUtil
import javax.swing.JList

/** Go to source: AC-17.1–17.4. Room's annotations are added to the test project as sources. */
class EntityNavigationTest : RoomErPlatformTestCase() {
    private lateinit var navigator: EntityNavigator
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        navigator = EntityNavigator(project)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    private fun addRoomAnnotations() {
        addFile("androidx/room/Entity.java", "package androidx.room;\npublic @interface Entity { String tableName() default \"\"; }")
        addFile("androidx/room/Fts4.java", "package androidx.room;\npublic @interface Fts4 { }")
        addFile(
            "androidx/room/DatabaseView.java",
            "package androidx.room;\npublic @interface DatabaseView { String value() default \"\"; String viewName() default \"\"; }",
        )
    }

    private fun resolve(name: String, kind: EntityKind = EntityKind.TABLE, schemaFile: VirtualFile? = null): NavigationTarget =
        timeoutRunBlocking { navigator.resolveTarget(name, kind, schemaFile) }

    private fun NavigationTarget.classNames(): List<String> = (this as NavigationTarget.Classes).candidates.map { it.qualifiedName }

    fun testResolvesByTableNameAttribute() {
        addRoomAnnotations()
        val file = addFile(
            "com/acme/UserEntity.java",
            "package com.acme;\nimport androidx.room.Entity;\n@Entity(tableName = \"users\")\npublic class UserEntity { }",
        )
        // A class that merely has the table's name must not win over the tableName attribute.
        addFile("com/acme/other/users.java", "package com.acme.other;\n@androidx.room.Entity\npublic class users { }")
        addFile("com/acme/Plain.java", "package com.acme;\npublic class Plain { }")

        val target = resolve("users") as NavigationTarget.Classes // AC-17.1
        val candidate = target.candidates.single()
        assertEquals("com.acme.UserEntity", candidate.qualifiedName)
        assertEquals(file, candidate.file)
        val text = String(file.contentsToByteArray())
        assertTrue(text.substring(candidate.offset).startsWith("UserEntity"))
    }

    fun testTableNameMayBeAConstant() {
        addRoomAnnotations()
        // The light test project has no JDK, so `String` does not resolve and PsiFieldImpl.computeConstantValue
        // refuses to evaluate a field whose type is not java.lang.String (verified: canonical type text was
        // "String", computeConstantValue() == null, while the initializer alone evaluates to "orders").
        // Real projects always have a JDK; a one-class stand-in is enough here.
        addFile("java/lang/String.java", "package java.lang;\npublic final class String { }")
        addFile(
            "com/acme/OrderEntity.java",
            "package com.acme;\nimport androidx.room.Entity;\n@Entity(tableName = OrderEntity.TABLE)\npublic class OrderEntity { static final String TABLE = \"or\" + \"ders\"; }",
        )
        assertEquals(listOf("com.acme.OrderEntity"), resolve("orders").classNames())
    }

    fun testResolvesByClassNameWhenNoTableNameIsDeclared() {
        addRoomAnnotations()
        addFile("com/acme/Invoice.java", "package com.acme;\n@androidx.room.Entity\npublic class Invoice { }")
        addFile("com/acme/Renamed.java", "package com.acme;\n@androidx.room.Entity(tableName = \"something_else\")\npublic class Renamed { }")
        addFile("com/acme/NotAnEntity.java", "package com.acme;\npublic class NotAnEntity { }")

        assertEquals(listOf("com.acme.Invoice"), resolve("Invoice").classNames()) // AC-17.2
        assertEquals("case-insensitive as a last resort", listOf("com.acme.Invoice"), resolve("invoice").classNames())
        assertTrue("explicit tableName excludes the class-name rule", resolve("Renamed") is NavigationTarget.Nothing)
        assertTrue("classes without @Entity never match", resolve("NotAnEntity") is NavigationTarget.Nothing)
    }

    fun testFtsTablesAndViewsResolve() {
        addRoomAnnotations()
        addFile(
            "com/acme/NoteFts.java",
            "package com.acme;\nimport androidx.room.*;\n@Fts4\n@Entity(tableName = \"notes_fts\")\npublic class NoteFts { }",
        )
        addFile(
            "com/acme/ActiveUser.java",
            "package com.acme;\nimport androidx.room.DatabaseView;\n@DatabaseView(value = \"SELECT 1\", viewName = \"active_users\")\npublic class ActiveUser { }",
        )
        addFile("com/acme/Summary.java", "package com.acme;\n@androidx.room.DatabaseView(\"SELECT 1\")\npublic class Summary { }")

        assertEquals(listOf("com.acme.NoteFts"), resolve("notes_fts", EntityKind.FTS_TABLE).classNames())
        assertEquals(listOf("com.acme.ActiveUser"), resolve("active_users", EntityKind.VIEW).classNames())
        assertEquals(listOf("com.acme.Summary"), resolve("Summary", EntityKind.VIEW).classNames())
        assertTrue("an entity is not a view", resolve("notes_fts", EntityKind.VIEW) is NavigationTarget.Nothing)
    }

    fun testResolvesRoom3Annotations() {
        addFile("androidx/room3/Entity.java", "package androidx.room3;\npublic @interface Entity { String tableName() default \"\"; }")
        addFile(
            "androidx/room3/DatabaseView.java",
            "package androidx.room3;\npublic @interface DatabaseView { String value() default \"\"; String viewName() default \"\"; }",
        )
        addFile("com/acme/Song.java", "package com.acme;\nimport androidx.room3.Entity;\n@Entity(tableName = \"songs\")\npublic class Song { }")
        addFile("com/acme/Album.java", "package com.acme;\n@androidx.room3.Entity\npublic class Album { }")
        addFile("com/acme/Top.java", "package com.acme;\n@androidx.room3.DatabaseView(value = \"SELECT 1\", viewName = \"top_songs\")\npublic class Top { }")

        assertEquals(listOf("com.acme.Song"), resolve("songs").classNames())
        assertEquals(listOf("com.acme.Album"), resolve("Album").classNames())
        assertEquals(listOf("com.acme.Top"), resolve("top_songs", EntityKind.VIEW).classNames())
    }

    fun testRoom2AndRoom3EntitiesResolveSideBySide() {
        addRoomAnnotations()
        addFile("androidx/room3/Entity.java", "package androidx.room3;\npublic @interface Entity { String tableName() default \"\"; }")
        addFile("com/acme/legacy/User.java", "package com.acme.legacy;\n@androidx.room.Entity(tableName = \"users\")\npublic class User { }")
        addFile("com/acme/next/User.java", "package com.acme.next;\n@androidx.room3.Entity(tableName = \"users\")\npublic class User { }")

        assertEquals(listOf("com.acme.legacy.User", "com.acme.next.User"), resolve("users").classNames())
    }

    fun testMultipleCandidatesAreAllOffered() {
        addRoomAnnotations()
        addFile("com/acme/a/UserEntity.java", "package com.acme.a;\n@androidx.room.Entity(tableName = \"users\")\npublic class UserEntity { }")
        addFile("com/acme/b/UserEntity.java", "package com.acme.b;\n@androidx.room.Entity(tableName = \"users\")\npublic class UserEntity { }")

        assertEquals(listOf("com.acme.a.UserEntity", "com.acme.b.UserEntity"), resolve("users").classNames()) // AC-17.4
    }

    fun testFallsBackToSchemaJsonWhenNoClassMatches() {
        addRoomAnnotations()
        val json = TestSchemas.schema(1, "users", "ghost")
        val schema = addFile("app/schemas/com.acme.AppDb/1.json", json)

        val target = resolve("ghost", schemaFile = schema) as NavigationTarget.SchemaJson // AC-17.3
        assertEquals(schema, target.file)
        assertEquals(FallbackReason.NO_MATCHING_CLASS, target.reason)
        assertTrue(json.substring(target.offset).startsWith("\"tableName\": \"ghost\""))
    }

    fun testFallsBackToSchemaJsonWhenRoomIsNotOnTheClasspath() {
        addFile("com/acme/users.java", "package com.acme;\npublic class users { }")
        val schema = addFile("app/schemas/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))

        val target = resolve("users", schemaFile = schema) as NavigationTarget.SchemaJson
        assertEquals(FallbackReason.ROOM_NOT_AVAILABLE, target.reason)
    }

    fun testGoToSourceOpensTheEntityClass() {
        addRoomAnnotations()
        val file = addFile("com/acme/UserEntity.java", "package com.acme;\n@androidx.room.Entity(tableName = \"users\")\npublic class UserEntity { }")

        timeoutRunBlocking { navigator.goToSource("users", EntityKind.TABLE, null, null) }

        val (openFile, caret) = selectedEditorState()
        assertEquals(file, openFile)
        assertTrue(String(file.contentsToByteArray()).substring(caret).startsWith("UserEntity"))
        assertTrue("no notification when the class is found", notifications.isEmpty())
    }

    fun testGoToSourceOpensJsonAtEntityAndExplainsWhy() {
        addRoomAnnotations()
        val json = TestSchemas.schema(1, "users", "ghost")
        val schema = addFile("app/schemas/com.acme.AppDb/1.json", json)

        timeoutRunBlocking { navigator.goToSource("ghost", EntityKind.TABLE, schema, null) }

        val (openFile, caret) = selectedEditorState()
        assertEquals(schema, openFile)
        assertTrue(json.substring(caret).startsWith("\"tableName\": \"ghost\""))
        val notification = notifications.single()
        assertTrue(notification.content, notification.content.contains("ghost") && notification.content.contains("@Entity"))
    }

    fun testSeveralCandidatesOpenTheChooser() {
        addRoomAnnotations()
        addFile("com/acme/a/UserEntity.java", "package com.acme.a;\n@androidx.room.Entity(tableName = \"users\")\npublic class UserEntity { }")
        addFile("com/acme/b/UserEntity.java", "package com.acme.b;\n@androidx.room.Entity(tableName = \"users\")\npublic class UserEntity { }")
        val shown = CopyOnWriteArrayList<JBPopup>()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<JBPopup>(JBPopup::class.java) {
            override fun doIntercept(component: JBPopup) {
                shown += component
            }
        })
        try {
            timeoutRunBlocking { navigator.goToSource("users", EntityKind.TABLE, null, null) } // AC-17.4
        } finally {
            UiInterceptors.clear()
        }

        val popup = shown.single()
        timeoutRunBlocking {
            withContext(Dispatchers.EDT) {
                val list = UIUtil.findComponentOfType(popup.content, JList::class.java)!!
                val items = (0 until list.model.size).map { (list.model.getElementAt(it) as EntityCandidate).qualifiedName }
                assertEquals(listOf("com.acme.a.UserEntity", "com.acme.b.UserEntity"), items)
                // Choosing an entry (mouse/Enter on a shown popup) cannot be simulated on an intercepted popup.
                assertNull("nothing is opened before the user chooses", FileEditorManager.getInstance(project).selectedEditor?.file)
                if (!popup.isDisposed) Disposer.dispose(popup)
            }
        }
        assertTrue("no notification for a found class", notifications.isEmpty())
    }

    private fun selectedEditorState(): Pair<VirtualFile?, Int> = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).selectedEditor
            editor?.file to ((editor as? TextEditor)?.editor?.caretModel?.offset ?: -1)
        }
    }
}

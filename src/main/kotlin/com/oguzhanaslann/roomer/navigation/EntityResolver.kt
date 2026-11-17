package com.oguzhanaslann.roomer.navigation

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.oguzhanaslann.roomer.model.EntityKind

/** A class that may declare a table or view; plain data, safe to use outside a read action. */
data class EntityCandidate(
    val qualifiedName: String,
    val file: VirtualFile,
    /** Offset of the class name in [file]. */
    val offset: Int,
)

sealed interface EntityResolution {
    /** One or more classes matched; more than one means the user has to choose (AC-17.4). */
    data class Found(val candidates: List<EntityCandidate>) : EntityResolution

    /** `androidx.room.Entity` / `DatabaseView` cannot be resolved: Room is not on the project classpath. */
    data object RoomNotAvailable : EntityResolution

    /** Room is available but no annotated class in the project matches the name. */
    data object NoMatch : EntityResolution
}

/**
 * Table/view name → declaring class (spec FR-33, risk R1). Works on Java PSI only; Kotlin classes are
 * seen through their light classes when the Kotlin plugin is installed, so no Kotlin dependency is needed.
 */
object EntityResolver {
    const val ENTITY_ANNOTATION: String = "androidx.room.Entity"
    const val VIEW_ANNOTATION: String = "androidx.room.DatabaseView"

    /**
     * Must be called in a read action in smart mode, off the EDT.
     *
     * Order: (1) the annotation's `tableName` / `viewName` equals [entityName]; (2) a class without an
     * explicit name whose simple name equals [entityName] — Room's default — exactly, then ignoring case.
     */
    fun resolve(project: Project, entityName: String, kind: EntityKind): EntityResolution {
        val annotationName = if (kind == EntityKind.VIEW) VIEW_ANNOTATION else ENTITY_ANNOTATION
        val nameAttribute = if (kind == EntityKind.VIEW) "viewName" else "tableName"
        val annotationClass = JavaPsiFacade.getInstance(project)
            .findClass(annotationName, GlobalSearchScope.allScope(project))
            ?: return EntityResolution.RoomNotAvailable

        val evaluator = JavaPsiFacade.getInstance(project).constantEvaluationHelper
        val byDeclaredName = ArrayList<PsiClass>()
        val bySimpleName = ArrayList<PsiClass>()
        val bySimpleNameIgnoringCase = ArrayList<PsiClass>()
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.projectScope(project))
            .forEach { psiClass ->
                ProgressManager.checkCanceled()
                val annotation = psiClass.getAnnotation(annotationName)
                val declaredName = annotation?.let {
                    // Evaluates literals as well as references to constants (`tableName = TABLE`).
                    AnnotationUtil.getStringAttributeValue(it, nameAttribute)
                        ?: evaluator.computeConstantExpression(it.findAttributeValue(nameAttribute)) as? String
                }
                val simpleName = psiClass.name
                when {
                    !declaredName.isNullOrEmpty() -> if (declaredName == entityName) byDeclaredName += psiClass
                    simpleName == entityName -> bySimpleName += psiClass
                    simpleName.equals(entityName, ignoreCase = true) -> bySimpleNameIgnoringCase += psiClass
                }
            }

        val matches = listOf(byDeclaredName, bySimpleName, bySimpleNameIgnoringCase).firstOrNull { it.isNotEmpty() }
            ?: return EntityResolution.NoMatch
        val candidates = matches.mapNotNull(::toCandidate).distinct().sortedBy { it.qualifiedName }
        return if (candidates.isEmpty()) EntityResolution.NoMatch else EntityResolution.Found(candidates)
    }

    private fun toCandidate(psiClass: PsiClass): EntityCandidate? {
        // For Kotlin light classes the navigation element is the declaration in the .kt file.
        val target = psiClass.navigationElement
        val file = target.containingFile?.virtualFile ?: return null
        val offset = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: target.textOffset
        return EntityCandidate(psiClass.qualifiedName ?: psiClass.name ?: return null, file, offset.coerceAtLeast(0))
    }
}

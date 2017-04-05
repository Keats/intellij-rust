@file:Suppress("LoopToCallChain")

package org.rust.lang.core.resolve

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.project.workspace.cargoWorkspace
import org.rust.cargo.util.getPsiFor
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.indexes.RsImplIndex
import org.rust.lang.core.resolve.ref.RsReference
import org.rust.lang.core.types.RustType
import org.rust.lang.core.types.stripAllRefsIfAny
import org.rust.lang.core.types.type
import org.rust.lang.core.types.types.RustStructType

fun processResolveVariants(fieldExpr: RsFieldExpr, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    val receiverType = fieldExpr.expr.type.stripAllRefsIfAny()

    val struct = (receiverType as? RustStructType)?.item
    if (struct != null && processFields(struct, processor)) return true

    if (isCompletion) {
        processMethods(fieldExpr.project, receiverType, processor)
    }

    return false
}

fun processResolveVariants(field: RsStructExprField, processor: RsResolveProcessor): Boolean {
    val structOrEnumVariant = field.parentStructExpr.path.reference.resolve() as? RsFieldsOwner ?: return false
    return processFields(structOrEnumVariant, processor)
}

fun processResolveVariants(callExpr: RsMethodCallExpr, processor: RsResolveProcessor): Boolean {
    val receiverType = callExpr.expr.type
    return processMethods(callExpr.project, receiverType, processor)
}

fun processResolveVariants(glob: RsUseGlob, processor: RsResolveProcessor): Boolean {
    val useItem = glob.parentUseItem
    val basePath = useItem.path
    val baseItem = (if (basePath != null)
        basePath.reference.resolve()
    else
    // `use ::{foo, bar}`
        glob.crateRoot) ?: return false

    if (processor("self", baseItem)) return true

    return processDeclarations(baseItem, TYPES_N_VALUES, processor)
}

/**
 * Looks-up file corresponding to particular module designated by `mod-declaration-item`:
 *
 *  ```
 *  // foo.rs
 *  pub mod bar; // looks up `bar.rs` or `bar/mod.rs` in the same dir
 *
 *  pub mod nested {
 *      pub mod baz; // looks up `nested/baz.rs` or `nested/baz/mod.rs`
 *  }
 *
 *  ```
 *
 *  | A module without a body is loaded from an external file, by default with the same name as the module,
 *  | plus the '.rs' extension. When a nested sub-module is loaded from an external file, it is loaded
 *  | from a subdirectory path that mirrors the module hierarchy.
 *
 * Reference:
 *      https://github.com/rust-lang/rust/blob/master/src/doc/reference.md#modules
 */
fun processResolveVariants(modDecl: RsModDeclItem, processor: RsResolveProcessor): Boolean {
    val dir = modDecl.containingMod.ownedDirectory ?: return false

    val explicitPath = modDecl.pathAttribute
    if (explicitPath != null) {
        val vFile = dir.virtualFile.findFileByRelativePath(explicitPath) ?: return false
        val mod = PsiManager.getInstance(modDecl.project).findFile(vFile)?.rustMod ?: return false

        val name = modDecl.name ?: return false
        return processor(name, mod)
    }
    if (modDecl.isLocal) return false

    for (file in dir.files) {
        if (file == modDecl.containingFile.originalFile || file.name == RsMod.MOD_RS) continue
        val mod = file.rustMod ?: continue
        val fileName = FileUtil.getNameWithoutExtension(file.name)
        val modName = modDecl.name
        // Case-insensitive fs
        val name = if (modName != null && modName.toLowerCase() == fileName.toLowerCase()) modName else fileName
        if (processor(name, mod)) return true
    }

    for (d in dir.subdirectories) {
        val mod = d.findFile(RsMod.MOD_RS)?.rustMod ?: continue
        if (processor(d.name, mod)) return true
    }

    return false
}

fun processResolveVariants(crate: RsExternCrateItem, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    val module = crate.module ?: return false
    val pkg = crate.containingCargoPackage ?: return false
    fun processPackage(pkg: CargoWorkspace.Package): Boolean {
        if (isCompletion && pkg.origin != PackageOrigin.DEPENDENCY) return false
        val libTarget = pkg.libTarget ?: return false
        return processor.lazy(libTarget.normName) {
            module.project.getPsiFor(libTarget.crateRoot)?.rustMod
        }
    }

    if (processPackage(pkg)) return true
    for (p in pkg.dependencies) {
        if (processPackage(p)) return true
    }
    return false
}

fun processResolveVariants(path: RsPath, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    val qualifier = path.path
    val parent = path.parent
    val ns = when (parent) {
        is RsPath, is RsTypeReference -> TYPES
        is RsUseItem -> if (parent.isStarImport) TYPES else TYPES_N_VALUES
        is RsPathExpr -> if (isCompletion) TYPES_N_VALUES else VALUES
        else -> TYPES_N_VALUES
    }

    if (qualifier != null) {
        val base = qualifier.reference.resolve() ?: return false
        if (base is RsMod) {
            val s = base.`super`
            if (s != null && processor("super", s)) return true
        }
        if (processDeclarations(base, ns, processor)) return true
        if (base is RsTypeBearingItemElement && parent !is RsUseItem) {
            if (processAssociatedFunctions(base.project, base.type, processor)) return true
        }
        return false
    }

    val containigMod = path.containingMod
    val crateRoot = path.crateRoot
    if (!path.isCrateRelative) {
        if (Namespace.Types in ns && containigMod != null) {
            if (processor("self", containigMod)) return true
            val superMod = containigMod.`super`
            if (superMod != null) {
                if (processor("super", superMod)) return true
            }
        }
    }

    // Paths in use items are implicitly global.
    if (path.isCrateRelative || path.parentOfType<RsUseItem>() != null) {
        if (crateRoot != null) {
            if (processDeclarations(crateRoot, ns, processor)) return true
        }
        return false
    }

    val prevScope = mutableSetOf<String>()
    walkUp(path, { it is RsMod }) { cameFrom, scope ->
        val currScope = mutableListOf<String>()
        val shadowingProcessor = { e: ScopeEntry ->
            e.name !in prevScope && run {
                currScope += e.name
                processor(e)
            }
        }
        if (processLexicalDeclarations(scope, cameFrom, ns, shadowingProcessor)) return@walkUp true
        prevScope.addAll(currScope)
        false
    }

    val preludeFile = path.containingCargoPackage?.findCrateByName("std")?.crateRoot
        ?.findFileByRelativePath("../prelude/v1.rs")
    val prelude = path.project.getPsiFor(preludeFile)?.rustMod
    if (prelude != null && processDeclarations(prelude, false, ns, { v -> v.name !in prevScope && processor(v) })) return true

    return false
}

fun processResolveVariants(label: RsLabel, processor: RsResolveProcessor): Boolean {
    for (scope in label.ancestors) {
        if (scope is RsLambdaExpr || scope is RsFunction) return false
        if (scope is RsLabeledExpression) {
            val lableDecl = scope.labelDecl ?: continue
            if (processor(lableDecl)) return true
        }
    }
    return false
}

fun processResolveVariants(lifetime: RsLifetime, processor: RsResolveProcessor): Boolean {
    if (lifetime.isPredefined) return false
    loop@ for (scope in lifetime.ancestors) {
        val lifetimeParameters = when (scope) {
            is RsGenericDeclaration -> scope.typeParameterList?.lifetimeParameterList
            is RsForInType -> scope.forLifetimes.lifetimeParameterList
            is RsPolybound -> scope.forLifetimes?.lifetimeParameterList
            else -> continue@loop
        }
        for (l in lifetimeParameters.orEmpty()) {
            if (processor(l.lifetimeDecl)) return true
        }
    }
    return false
}

fun processLocalVariables(place: RsCompositeElement, processor: (RsPatBinding) -> Unit) {
    walkUp(place, { it is RsItemElement }) { cameFrom, scope ->
        processLexicalDeclarations(scope, cameFrom, VALUES) { v ->
            val el = v.element
            if (el is RsPatBinding) processor(el)
            true
        }
    }
}

/**
 * Resolves an absolute path.
 */
fun resolveStringPath(path: String, module: Module): Pair<RsNamedElement, CargoWorkspace.Package>? {
    val parts = path.split("::", limit = 2)
    if (parts.size != 2) return null
    val workspace = module.cargoWorkspace ?: return null
    val pkg = workspace.findPackage(parts[0]) ?: return null

    val el = pkg.targets.asSequence()
        .mapNotNull { RsCodeFragmentFactory(module.project).createCrateRelativePath("::${parts[1]}", it) }
        .mapNotNull { it.path.reference.resolve() }
        .filterIsInstance<RsNamedElement>()
        .firstOrNull() ?: return null
    return el to pkg
}


private fun processFields(struct: RsFieldsOwner, processor: RsResolveProcessor): Boolean {
    if (processAll(struct.namedFields, processor)) return true

    for ((idx, field) in struct.positionalFields.withIndex()) {
        if (processor(idx.toString(), field)) return true
    }
    return false
}

private fun processMethods(project: Project, receiver: RustType, processor: RsResolveProcessor): Boolean {
    val methods = receiver.getMethodsIn(project)
    return processFnsWithInherentPriority(methods, processor)
}

private fun processAssociatedFunctions(project: Project, type: RustType, processor: RsResolveProcessor): Boolean {
    val methodsAndFns = RsImplIndex.findMethodsAndAssociatedFunctionsFor(type, project)
    return processFnsWithInherentPriority(methodsAndFns, processor)
}

private fun processFnsWithInherentPriority(fns: Sequence<RsFunction>, processor: RsResolveProcessor): Boolean {
    val (inherent, nonInherent) = fns.partition { it is RsFunction && it.isInherentImpl }
    if (processAll(inherent, processor)) return true

    val inherentNames = inherent.mapNotNull { it.name }.toHashSet()
    for (fn in nonInherent) {
        if (fn.name in inherentNames) continue
        if (processor(fn)) return true
    }
    return false
}

private fun processDeclarations(scope: RsCompositeElement, ns: Set<Namespace>, processor: RsResolveProcessor): Boolean {
    when (scope) {
        is RsEnumItem -> {
            if (processAll(scope.enumBody.enumVariantList, processor)) return true
        }
        is RsMod -> {
            if (processDeclarations(scope, false, ns, processor)) return true
        }
    }

    return false
}

private fun processDeclarations(scope: RsItemsOwner, withPrivateImports: Boolean, ns: Set<Namespace>, originalProcessor: RsResolveProcessor): Boolean {
    val (starImports, itemImports) = scope.useItemList
        .filter { it.isPublic || withPrivateImports }
        .partition { it.isStarImport }

    // Handle shadowing of `use::*`, but only if star imports are present
    val directlyDeclaredNames = mutableSetOf<String>()
    val processor = if (starImports.isEmpty()) {
        originalProcessor
    } else {
        { e: ScopeEntry ->
            directlyDeclaredNames += e.name
            originalProcessor(e)
        }
    }


    // Unit like structs are both types and values
    for (struct in scope.structItemList) {
        if (struct.namespaces.intersect(ns).isNotEmpty() && processor(struct)) {
            return true
        }
    }

    if (Namespace.Types in ns) {
        for (modDecl in scope.modDeclItemList) {
            val name = modDecl.name ?: continue
            val mod = modDecl.reference.resolve() ?: continue
            if (processor(name, mod)) return true
        }

        if (processAll(scope.enumItemList, processor)
            || processAll(scope.modItemList, processor)
            || processAll(scope.traitItemList, processor)
            || processAll(scope.typeAliasList, processor)) {
            return true
        }

        if (scope is RsFile && scope.isCrateRoot) {
            val pkg = scope.containingCargoPackage
            val module = scope.module

            if (pkg != null && module != null) {
                val finsStdMod = { name: String ->
                    val crate = pkg.findCrateByName(name)?.crateRoot
                    module.project.getPsiFor(crate)?.rustMod
                }

                // Rust injects implicit `extern crate std` in every crate root module unless it is
                // a `#![no_std]` crate, in which case `extern crate core` is injected. However, if
                // there is a (unstable?) `#![no_core]` attribute, nothing is injected.
                //
                // https://doc.rust-lang.org/book/using-rust-without-the-standard-library.html
                // The stdlib lib itself is `#![no_std]`, and the core is `#![no_core]`
                when (scope.attributes) {
                    RsFile.Attributes.NONE -> {
                        if (processor.lazy("std") { finsStdMod("std") }) {
                            return true
                        }
                    }
                    RsFile.Attributes.NO_STD -> {
                        if (processor.lazy("core") { finsStdMod("core") }) {
                            return true
                        }
                    }
                    RsFile.Attributes.NO_CORE -> {
                    }
                }
            }
        }

    }

    if (Namespace.Values in ns) {
        if (processAll(scope.functionList, processor)
            || processAll(scope.constantList, processor)) {
            return true
        }
    }

    for (fmod in scope.foreignModItemList) {
        if (processAll(fmod.functionList, processor)) return true
        if (processAll(fmod.constantList, processor)) return true
    }

    for (crate in scope.externCrateItemList) {
        val name = crate.alias?.name ?: crate.name ?: continue
        val mod = crate.reference.resolve() ?: continue
        if (processor(name, mod)) return true
    }

    fun resolveWithNs(ref: RsReference): RsCompositeElement? =
        ref.multiResolve().find { element ->
            element is RsNamedElement && ns.intersect(element.namespaces).isNotEmpty()
        }

    for (use in itemImports) {
        val globList = use.useGlobList
        if (globList == null) {
            val path = use.path ?: continue
            val name = use.alias?.name ?: path.referenceName ?: continue
            if (processor.lazy(name, { resolveWithNs(path.reference) })) {
                return true
            }
        } else {
            for (glob in globList.useGlobList) {
                val name = glob.alias?.name
                    ?: (if (glob.isSelf) use.path?.referenceName else null)
                    ?: glob.referenceName
                    ?: continue
                if (processor.lazy(name, { resolveWithNs(glob.reference) })) return true
            }
        }
    }

    for (use in starImports) {
        val mod = use.path?.reference?.resolve() ?: continue
//        val newCtx = context.copy(visitedStarImports = context.visitedStarImports + this)
        if (processDeclarations(mod, ns, { v ->
            v.name !in directlyDeclaredNames && originalProcessor(v)
        })) return true
    }

    return false
}

private fun processLexicalDeclarations(scope: RsCompositeElement, cameFrom: RsCompositeElement, ns: Set<Namespace>, processor: RsResolveProcessor): Boolean {
    check(cameFrom.parent == scope || cameFrom.getUserData(RS_CODE_FRAGMENT_CONTEXT) == scope)

    fun processPattern(pattern: RsPat, processor: RsResolveProcessor): Boolean {
        val boundNames = PsiTreeUtil.findChildrenOfType(pattern, RsPatBinding::class.java)
        return processAll(boundNames, processor)
    }

    fun processCondition(condition: RsCondition?, processor: RsResolveProcessor): Boolean {
        if (condition == null || condition == cameFrom) return false
        val pat = condition.pat
        if (pat != null && processPattern(pat, processor)) return true
        return false
    }

    when (scope) {
        is RsMod -> {
            if (processDeclarations(scope, true, ns, processor)) return true
        }

        is RsStructItem,
        is RsEnumItem,
        is RsTypeAlias -> {
            scope as RsGenericDeclaration
            if (processAll(scope.typeParameters, processor)) return true
        }

        is RsTraitItem -> {
            if (processAll(scope.typeParameters, processor)) return true
            if (processor("Self", scope)) return true
        }

        is RsImplItem -> {
            if (processAll(scope.typeParameters, processor)) return true
            //TODO: handle types which are not `NamedElements` (e.g. tuples)
            val selfType = (scope.typeReference as? RsBaseType)?.path?.reference?.resolve()
            if (selfType != null && processor("Self", selfType)) return true
        }

        is RsFunction -> {
            if (Namespace.Types in ns) {
                if (processAll(scope.typeParameters, processor)) return true
            }
            if (Namespace.Values in ns) {
                val selfParam = scope.selfParameter
                if (selfParam != null && processor("self", selfParam)) return true

                for (parameter in scope.valueParameters) {
                    val pat = parameter.pat ?: continue
                    if (processPattern(pat, processor)) return true
                }
            }
        }

        is RsBlock -> {
            // We want to filter out
            // all non strictly preceding let declarations.
            //
            // ```
            // let x = 92; // visible
            // let x = x;  // not visible
            //         ^ context.place
            // let x = 62; // not visible
            // ```
            val visited = mutableSetOf<String>()
            if (Namespace.Values in ns) {
                val shadowingProcessor = { e: ScopeEntry ->
                    (e.name !in visited) && run {
                        visited += e.name
                        processor(e)
                    }
                }

                for (stmt in scope.stmtList.asReversed()) {
                    val pat = (stmt as? RsLetDecl)?.pat ?: continue
                    if (PsiUtilCore.compareElementsByPosition(cameFrom, stmt) < 0) continue
                    if (stmt == cameFrom) continue
                    if (processPattern(pat, shadowingProcessor)) return true
                }
            }

            return processDeclarations(scope, true, ns, processor)
        }

        is RsForExpr -> {
            if (scope.expr == cameFrom) return false
            val pat = scope.pat
            if (pat != null && processPattern(pat, processor)) return true
        }

        is RsIfExpr -> return processCondition(scope.condition, processor)
        is RsWhileExpr -> return processCondition(scope.condition, processor)

        is RsLambdaExpr -> {
            for (parameter in scope.valueParameterList.valueParameterList) {
                val pat = parameter.pat
                if (pat != null && processPattern(pat, processor)) return true
            }
        }

        is RsMatchArm -> {
            // Rust allows to defined several patterns in the single match arm,
            // but they all must bind the same variables, hence we can inspect
            // only the first one.
            val pat = scope.patList.firstOrNull()
            if (pat != null && processPattern(pat, processor)) return true

        }
    }
    return false
}


// There's already similar functions in TreeUtils, should use it
private fun walkUp(
    start: RsCompositeElement,
    stopAfter: (RsCompositeElement) -> Boolean,
    processor: (cameFrom: RsCompositeElement, scope: RsCompositeElement) -> Boolean
): Boolean {

    var cameFrom: RsCompositeElement = start
    var scope = start.parent as RsCompositeElement?
    while (scope != null) {
        if (processor(cameFrom, scope)) return true
        if (stopAfter(scope)) break
        cameFrom = scope
        scope = scope.getUserData(RS_CODE_FRAGMENT_CONTEXT) ?: (scope.parent as RsCompositeElement?)
    }

    return false
}

private operator fun RsResolveProcessor.invoke(name: String, e: RsCompositeElement): Boolean {
    return this(SimpleScopeEntry(name, e))
}

private fun RsResolveProcessor.lazy(name: String, e: () -> RsCompositeElement?): Boolean {
    return this(LazyScopeEntry(name, lazy(e)))
}

private operator fun RsResolveProcessor.invoke(e: RsNamedElement): Boolean {
    val name = e.name ?: return false
    return this(name, e)
}

private fun processAll(elements: Collection<RsNamedElement>, processor: RsResolveProcessor): Boolean {
    for (e in elements) {
        if (processor(e)) return true
    }
    return false
}

private data class SimpleScopeEntry(override val name: String, override val element: RsCompositeElement) : ScopeEntry

private class LazyScopeEntry(
    override val name: String,
    thunk: Lazy<RsCompositeElement?>
) : ScopeEntry {
    override val element: RsCompositeElement? by thunk

    override fun toString(): String = "LazyScopeEntry($name, $element)"
}

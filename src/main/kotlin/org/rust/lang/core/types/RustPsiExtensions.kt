package org.rust.lang.core.types

import com.intellij.openapi.util.Computable
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.rust.ide.utils.recursionGuard
import org.rust.lang.core.psi.RsExpr
import org.rust.lang.core.psi.RsTypeBearingItemElement
import org.rust.lang.core.psi.RsTypeReference
import org.rust.lang.core.types.types.RustUnknownType

val RsExpr.type: RustType
    get() = CachedValuesManager.getCachedValue(this, CachedValueProvider {
        CachedValueProvider.Result.create(RustTypificationEngine.typifyExpr(this), PsiModificationTracker.MODIFICATION_COUNT)
    })

val RsTypeReference.type: RustType
    get() = recursionGuard(this, Computable {
        RustTypificationEngine.typifyType(this)
    }) ?: RustUnknownType

val RsTypeBearingItemElement.type: RustType
    get() = CachedValuesManager.getCachedValue(this, CachedValueProvider {
        CachedValueProvider.Result.create(RustTypificationEngine.typify(this), PsiModificationTracker.MODIFICATION_COUNT)
    })

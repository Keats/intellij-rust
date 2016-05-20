package org.rust.ide.formatter.impl

import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Indent
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Key
import org.rust.ide.formatter.RustFmtBlock
import org.rust.lang.core.psi.RustCompositeElementTypes.*
import org.rust.lang.core.psi.RustExpr
import org.rust.lang.core.psi.RustTokenElementTypes.LBRACE

val INDENT_INSIDE_FLAT_BLOCK: Key<Boolean> = Key.create("INDENT_BETWEEN_FLAT_BLOCK_BRACES")

fun newChildIndent(block: ASTBlock, childIndex: Int): Indent? {
    // MOD_ITEM and FOREIGN_MOD_ITEM do not have separate PSI node for contents
    // blocks so we have to manually decide whether new child is before (no indent)
    // or after (normal indent) left brace node.
    if (block.node.isModItem) {
        val lbraceIndex = block.subBlocks.indexOfFirst { it is ASTBlock && it.node.elementType == LBRACE }
        if (lbraceIndex != -1 && lbraceIndex < childIndex) {
            return Indent.getNormalIndent()
        }
    }

    // We are inside some kind of {...}, [...], (...) or <...> block
    if (block.node.isDelimitedBlock) {
        return Indent.getNormalIndent()
    }

    // Otherwise we don't want any indentation (null means continuation indent)
    return Indent.getNoneIndent()
}

fun computeIndent(block: RustFmtBlock, child: ASTNode): Indent? {
    val parentType = block.node.elementType
    val childType = child.elementType
    val childPsi = child.psi
    return when {
        block.node.isDelimitedBlock -> getIndentIfNotDelim(child, block.node)

        (block.node.isFlatBlock || parentType == PAT_ENUM) && block.getUserData(INDENT_INSIDE_FLAT_BLOCK) == true ->
            getIndentIfNotDelim(child, block.node)

        parentType == MATCH_ARM && childPsi is RustExpr -> Indent.getNormalIndent()

        childType == RET_TYPE || childType == WHERE_CLAUSE -> Indent.getNormalIndent()

        else -> Indent.getNoneIndent()
    }
}

private fun getIndentIfNotDelim(child: ASTNode, parent: ASTNode): Indent =
    if (child.isBlockDelim(parent)) {
        Indent.getNoneIndent()
    } else {
        Indent.getNormalIndent()
    }

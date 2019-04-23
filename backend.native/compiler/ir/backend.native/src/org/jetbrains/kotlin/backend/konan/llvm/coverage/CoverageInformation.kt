/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.konan.llvm.column
import org.jetbrains.kotlin.backend.konan.llvm.line
import org.jetbrains.kotlin.backend.konan.llvm.symbolName
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.name

/**
 * The most important class in the coverage package.
 * It describes textual region of the code that is associated with IrElement.
 * Besides the obvious [file] and line/column borders, it has [RegionKind] which is described later.
 */
class Region(
        val file: IrFile,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
        val kind: RegionKind
) {

    companion object {
        fun fromIr(irElement: IrElement, irFile: IrFile, kind: RegionKind) =
                fromOffset(irElement.startOffset, irElement.endOffset, irFile, kind)

        fun fromOffset(startOffset: Int, endOffset: Int, irFile: IrFile, kind: RegionKind) =
                Region(
                        irFile,
                        irFile.fileEntry.line(startOffset),
                        irFile.fileEntry.column(startOffset),
                        irFile.fileEntry.line(endOffset),
                        irFile.fileEntry.column(endOffset),
                        kind
                )
    }

    override fun toString(): String {
        val expansion = (kind as? RegionKind.Expansion)?.let { " expand to " + it.expandedFile.name } ?: ""
        return "${file.name}$expansion: ${kind::class.simpleName} $startLine, $startColumn -> $endLine, $endColumn"
    }
}

/**
 * Describes what is the given code region.
 * Based on llvm::coverage::CounterMappingRegion.
 * Currently only [RegionKind.Code] is used.
 */
sealed class RegionKind {
    /**
     * Regular peace of code.
     */
    object Code : RegionKind()
    /**
     * Empty line.
     */
    object Gap : RegionKind()
    /**
     * Region of code that is an expansion of another source file.
     * Used for inline function.
     */
    class Expansion(val expandedFile: IrFile) : RegionKind()
}

/**
 * "Regional" description of the [function].
 */
class FunctionRegions(
        val function: IrFunction,
        val regions: Map<IrElement, Region>
) {
    // Enumeration is required for serialization and instrumentation calls.
    val regionEnumeration = regions.values.mapIndexed { index, region -> region to index }.toMap()
    // Actually, it should be computed.
    // But since we don't support PGO structural hash doesn't really matter for now.
    val structuralHash: Long = 0

    override fun toString(): String = buildString {
        appendln("${function.symbolName} regions:")
        regions.forEach { (irElem, region) -> appendln("${ir2string(irElem)} -> ($region)") }
    }
}

/**
 * Since file is the biggest unit in terms of the code coverage
 * we aggregate [FunctionRegions] per [file].
 */
class FileRegionInfo(
        val file: IrFile,
        val functions: List<FunctionRegions>
)
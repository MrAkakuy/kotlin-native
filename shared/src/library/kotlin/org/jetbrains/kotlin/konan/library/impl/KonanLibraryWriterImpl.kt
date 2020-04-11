/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.konan.library.impl

import org.jetbrains.kotlin.konan.library.BitcodeWriter
import org.jetbrains.kotlin.konan.library.KonanLibraryWriter
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.KonanLibrary
import org.jetbrains.kotlin.konan.library.KonanLibraryLayout
import org.jetbrains.kotlin.konan.properties.Properties
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.library.*
import org.jetbrains.kotlin.library.impl.*

class KonanLibraryLayoutForWriter(
    override val libDir: File,
    override val target: KonanTarget
) : KonanLibraryLayout, KotlinLibraryLayoutForWriter(libDir)

/**
 * Requires non-null [target].
 */
class KonanLibraryWriterImpl(
        libDir: File,
        moduleName: String,
        versions: KotlinLibraryVersioning,
        target: KonanTarget,
        builtInsPlatform: BuiltInsPlatform,
        nopack: Boolean = false,
        shortName: String? = null,

        val layout: KonanLibraryLayoutForWriter = KonanLibraryLayoutForWriter(libDir, target),

        base: BaseWriter = BaseWriterImpl(layout, moduleName, versions, builtInsPlatform, nopack, shortName),
        bitcode: BitcodeWriter = BitcodeWriterImpl(layout),
        metadata: MetadataWriter = MetadataWriterImpl(layout),
        ir: IrWriter = IrMonoliticWriterImpl(layout)

) : BaseWriter by base, BitcodeWriter by bitcode, MetadataWriter by metadata, IrWriter by ir, KonanLibraryWriter

fun buildLibrary(
    natives: List<String>,
    included: List<String>,
    linkDependencies: List<KonanLibrary>,
    metadata: SerializedMetadata,
    ir: SerializedIrModule?,
    versions: KotlinLibraryVersioning,
    target: KonanTarget,
    output: String,
    moduleName: String,
    nopack: Boolean,
    shortName: String?,
    manifestProperties: Properties?,
    dataFlowGraph: ByteArray?
): KonanLibraryLayout {

    val library = KonanLibraryWriterImpl(
            File(output),
            moduleName,
            versions,
            target,
            BuiltInsPlatform.NATIVE,
            nopack,
            shortName
    )

    library.addMetadata(metadata)
    if (ir != null) {
        library.addIr(ir)
    }

    natives.forEach {
        library.addNativeBitcode(it)
    }
    included.forEach {
        library.addIncludedBinary(it)
    }
    manifestProperties?.let { library.addManifestAddend(it) }
    library.addLinkDependencies(linkDependencies)
    dataFlowGraph?.let { library.addDataFlowGraph(it) }

    library.commit()
    return library.layout
}

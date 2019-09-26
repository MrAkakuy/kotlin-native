/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.native.interop.gen

import org.jetbrains.kotlin.native.interop.gen.jvm.InteropConfiguration
import org.jetbrains.kotlin.native.interop.gen.jvm.KotlinPlatform
import org.jetbrains.kotlin.native.interop.indexer.*

/**
 * Additional components that are required to generate bridges.
 */
interface BridgeGenerationComponents {

    class GlobalSetterBridgeInfo(
            val cGlobalName: String,
            val typeInfo: TypeInfo
    )

    class GlobalGetterBridgeInfo(
            val cGlobalName: String,
            val typeInfo: TypeInfo,
            val isArray: Boolean
    )

    val setterToBridgeInfo: Map<PropertyAccessor.Setter, GlobalSetterBridgeInfo>

    val getterToBridgeInfo: Map<PropertyAccessor.Getter, GlobalGetterBridgeInfo>

    val enumToTypeMirror: Map<ClassStub.Enum, TypeMirror>

    val wCStringParameters: Set<FunctionParameterStub>

    val cStringParameters: Set<FunctionParameterStub>
}

class BridgeGenerationComponentsBuilder(
        val getterToBridgeInfo: MutableMap<PropertyAccessor.Getter, BridgeGenerationComponents.GlobalGetterBridgeInfo> = mutableMapOf(),
        val setterToBridgeInfo: MutableMap<PropertyAccessor.Setter, BridgeGenerationComponents.GlobalSetterBridgeInfo> = mutableMapOf(),
        val enumToTypeMirror: MutableMap<ClassStub.Enum, TypeMirror> = mutableMapOf(),
        val wCStringParameters: MutableSet<FunctionParameterStub> = mutableSetOf(),
        val cStringParameters: MutableSet<FunctionParameterStub> = mutableSetOf()
) {
    fun build(): BridgeGenerationComponents = object : BridgeGenerationComponents {
        override val getterToBridgeInfo =
                this@BridgeGenerationComponentsBuilder.getterToBridgeInfo.toMap()

        override val setterToBridgeInfo =
                this@BridgeGenerationComponentsBuilder.setterToBridgeInfo.toMap()

        override val enumToTypeMirror =
                this@BridgeGenerationComponentsBuilder.enumToTypeMirror.toMap()

        override val wCStringParameters: Set<FunctionParameterStub> =
                this@BridgeGenerationComponentsBuilder.wCStringParameters.toSet()

        override val cStringParameters: Set<FunctionParameterStub> =
                this@BridgeGenerationComponentsBuilder.cStringParameters.toSet()
    }
}

/**
 * Common part of all [StubIrBuilder] implementations.
 */
interface StubsBuildingContext {
    val configuration: InteropConfiguration

    fun mirror(type: Type): TypeMirror

    val declarationMapper: DeclarationMapper

    fun generateNextUniqueId(prefix: String): String

    val generatedObjCCategoriesMembers: MutableMap<ObjCClass, GeneratedObjCCategoriesMembers>

    val platform: KotlinPlatform

    fun isStrictEnum(enumDef: EnumDef): Boolean

    val macroConstantsByName: Map<String, MacroDef>

    fun tryCreateIntegralStub(type: Type, value: Long): IntegralConstantStub?

    fun tryCreateDoubleStub(type: Type, value: Double): DoubleConstantStub?

    val bridgeComponentsBuilder: BridgeGenerationComponentsBuilder

    fun getKotlinClassFor(objCClassOrProtocol: ObjCClassOrProtocol, isMeta: Boolean = false): Classifier

    fun getKotlinClassForPointed(structDecl: StructDecl): Classifier

    fun getKotlinName(funcDecl: FunctionDecl): Classifier

    fun getKotlinName(globalDecl: GlobalDecl): Classifier
}

/**
 *
 */
internal interface StubElementBuilder {
    val context: StubsBuildingContext

    fun build(): List<StubIrElement>
}

class StubsBuildingContextImpl(
        private val stubIrContext: StubIrContext
) : StubsBuildingContext {

    override val configuration: InteropConfiguration = stubIrContext.configuration
    override val platform: KotlinPlatform = stubIrContext.platform
    val imports: Imports = stubIrContext.imports
    private val nativeIndex: NativeIndex = stubIrContext.nativeIndex

    private var theCounter = 0

    override fun generateNextUniqueId(prefix: String) =
            prefix + pkgName.replace('.', '_') + theCounter++

    override fun mirror(type: Type): TypeMirror = mirror(declarationMapper, type)

    /**
     * Indicates whether this enum should be represented as Kotlin enum.
     */

    override fun isStrictEnum(enumDef: EnumDef): Boolean = with(enumDef) {
        if (this.isAnonymous) {
            return false
        }

        val name = this.kotlinName

        if (name in configuration.strictEnums) {
            return true
        }

        if (name in configuration.nonStrictEnums) {
            return false
        }

        // Let the simple heuristic decide:
        return !this.constants.any { it.isExplicitlyDefined }
    }

    override val generatedObjCCategoriesMembers = mutableMapOf<ObjCClass, GeneratedObjCCategoriesMembers>()

    override val declarationMapper = object : DeclarationMapper {
        override fun getKotlinClassForPointed(structDecl: StructDecl): Classifier {
            val baseName = structDecl.kotlinName
            val pkg = when (platform) {
                KotlinPlatform.JVM -> pkgName
                KotlinPlatform.NATIVE -> if (structDecl.def == null) {
                    cnamesStructsPackageName // to be imported as forward declaration.
                } else {
                    getPackageFor(structDecl)
                }
            }
            return Classifier.topLevel(pkg, baseName)
        }

        override fun isMappedToStrict(enumDef: EnumDef): Boolean = isStrictEnum(enumDef)

        override fun getKotlinNameForValue(enumDef: EnumDef): String = enumDef.kotlinName

        override fun getKotlinName(funcDecl: FunctionDecl): Classifier {
            val baseName = funcDecl.kotlinName
            val pkg = when (platform) {
                KotlinPlatform.JVM -> pkgName
                KotlinPlatform.NATIVE -> getPackageFor(funcDecl)
            }
            return Classifier.topLevel(pkg, baseName)
        }

        override fun getKotlinName(globalDecl: GlobalDecl): Classifier {
            val baseName = globalDecl.kotlinName
            val pkg = pkgName // TODO: is it ok or not
            return Classifier.topLevel(pkg, baseName)
        }

        override fun getPackageFor(declaration: TypeDeclaration): String {
            return imports.getPackage(declaration.location) ?: pkgName
        }

        override val useUnsignedTypes: Boolean
            get() = when (platform) {
                KotlinPlatform.JVM -> false
                KotlinPlatform.NATIVE -> true
            }
    }

    override val macroConstantsByName: Map<String, MacroDef> =
            (nativeIndex.macroConstants + nativeIndex.wrappedMacros).associateBy { it.name }

    /**
     * The name to be used for this enum in Kotlin
     */
    val EnumDef.kotlinName: String
        get() = if (spelling.startsWith("enum ")) {
            spelling.substringAfter(' ')
        } else {
            assert (!isAnonymous)
            spelling
        }


    private val pkgName: String
        get() = configuration.pkgName

    /**
     * The name to be used for this struct in Kotlin
     */
    val StructDecl.kotlinName: String
        get() = stubIrContext.getKotlinName(this)

    val FunctionDecl.kotlinName: String
        get() = stubIrContext.getKotlinName(this)

    val GlobalDecl.kotlinName: String
        get() = stubIrContext.getKotlinName(this)

    override fun tryCreateIntegralStub(type: Type, value: Long): IntegralConstantStub? {
        val integerType = type.unwrapTypedefs() as? IntegerType ?: return null
        val size = integerType.size
        if (size != 1 && size != 2 && size != 4 && size != 8) return null
        return IntegralConstantStub(value, size, declarationMapper.isMappedToSigned(integerType))
    }

    override fun tryCreateDoubleStub(type: Type, value: Double): DoubleConstantStub? {
        val unwrappedType = type.unwrapTypedefs() as? FloatingType ?: return null
        val size = unwrappedType.size
        if (size != 4 && size != 8) return null
        return DoubleConstantStub(value, size)
    }

    override val bridgeComponentsBuilder = BridgeGenerationComponentsBuilder()

    override fun getKotlinClassFor(objCClassOrProtocol: ObjCClassOrProtocol, isMeta: Boolean): Classifier {
        return declarationMapper.getKotlinClassFor(objCClassOrProtocol, isMeta)
    }

    override fun getKotlinClassForPointed(structDecl: StructDecl): Classifier {
        val classifier = declarationMapper.getKotlinClassForPointed(structDecl)
        return classifier
    }

    override fun getKotlinName(funcDecl: FunctionDecl): Classifier {
        val classifier = declarationMapper.getKotlinName(funcDecl)
        return classifier
    }

    override fun getKotlinName(globalDecl: GlobalDecl): Classifier {
        val classifier = declarationMapper.getKotlinName(globalDecl)
        return classifier
    }
}

data class StubIrBuilderResult(
        val stubs: SimpleStubContainer,
        val declarationMapper: DeclarationMapper,
        val bridgeGenerationComponents: BridgeGenerationComponents
)

/**
 * Produces [StubIrBuilderResult] for given [KotlinPlatform] using [InteropConfiguration].
 */
class StubIrBuilder(private val context: StubIrContext) {

    private val configuration = context.configuration
    private val nativeIndex: NativeIndex = context.nativeIndex

    private val classes = mutableListOf<ClassStub>()
    private val functions = mutableListOf<FunctionStub>()
    private val globals = mutableListOf<PropertyStub>()
    private val typealiases = mutableListOf<TypealiasStub>()
    private val containers = mutableListOf<SimpleStubContainer>()

    private fun addStubs(stubs: List<StubIrElement>) = stubs.forEach(this::addStub)

    private fun addStub(stub: StubIrElement) {
        when(stub) {
            is ClassStub -> classes += stub
            is FunctionStub -> functions += stub
            is PropertyStub -> globals += stub
            is TypealiasStub -> typealiases += stub
            is SimpleStubContainer -> containers += stub
            else -> error("Unexpected stub: $stub")
        }
    }

    private val excludedFunctions: Set<String>
        get() = configuration.excludedFunctions

    private val excludedMacros: Set<String>
        get() = configuration.excludedMacros

    private val buildingContext = StubsBuildingContextImpl(context)

    fun build(): StubIrBuilderResult {
        nativeIndex.objCProtocols.filter { !it.isForwardDeclaration }.forEach { generateStubsForObjCProtocol(it) }
        nativeIndex.objCClasses.filter { !it.isForwardDeclaration && !it.isNSStringSubclass()} .forEach { generateStubsForObjCClass(it) }
        nativeIndex.objCCategories.filter { !it.clazz.isNSStringSubclass() }.forEach { generateStubsForObjCCategory(it) }
        nativeIndex.structs.forEach { generateStubsForStruct(it) }
        nativeIndex.enums.forEach { generateStubsForEnum(it) }
        nativeIndex.cxxClasses.forEach { generateStubsForCxxClass(it) }
        nativeIndex.functions.filter { it.name !in excludedFunctions }.forEach { generateStubsForFunction(it) }
        nativeIndex.typedefs.forEach { generateStubsForTypedef(it) }
        nativeIndex.globals.filter { it.name !in excludedFunctions }.forEach { generateStubsForGlobal(it) }
        nativeIndex.macroConstants.filter { it.name !in excludedMacros }.forEach { generateStubsForMacroConstant(it) }
        nativeIndex.wrappedMacros.filter { it.name !in excludedMacros }.forEach { generateStubsForWrappedMacro(it) }

        val meta = StubContainerMeta()
        val stubs = SimpleStubContainer(
                meta,
                classes.toList(),
                functions.toList(),
                globals.toList(),
                typealiases.toList(),
                containers.toList()
        )
        return StubIrBuilderResult(
                stubs,
                buildingContext.declarationMapper,
                buildingContext.bridgeComponentsBuilder.build()
        )
    }

    private fun generateStubsForWrappedMacro(macro: WrappedMacroDef) {
        try {
            generateStubsForGlobal(GlobalDecl(macro.name, macro.type, isConst = true))
        } catch (e: Throwable) {
            context.log("Warning: cannot generate stubs for macro ${macro.name}")
        }
    }

    private fun generateStubsForMacroConstant(constant: ConstantDef) {
        try {
            addStubs(MacroConstantStubBuilder(buildingContext, constant).build())
        } catch (e: Throwable) {
            context.log("Warning: cannot generate stubs for constant ${constant.name}")
        }
    }

    private fun generateStubsForEnum(enumDef: EnumDef) {
        try {
            addStubs(EnumStubBuilder(buildingContext, enumDef).build())
        } catch (e: Throwable) {
            context.log("Warning: cannot generate definition for enum ${enumDef.spelling}")
        }
    }

    private fun generateStubsForFunction(func: FunctionDecl) {
        try {
            addStubs(FunctionStubBuilder(buildingContext, func).build())
        } catch (e: Throwable) {
            context.log("Warning: cannot generate stubs for function ${func.name}")
        }
    }

    private fun generateStubsForStruct(decl: StructDecl) {
        try {
            addStubs(StructStubBuilder(buildingContext, decl).build())
        } catch (e: Throwable) {
            context.log("Warning: cannot generate definition for struct ${decl.spelling}")
        }
    }

    private fun generateStubsForCxxClass(decl: CxxClassDecl) {
        try {
            addStubs(CxxClassStubBuilder(buildingContext, decl).build())
        } catch (e: Throwable) {
            context.log("Warning: cannot generate definition for struct ${decl.spelling}")
        }
    }

    private fun generateStubsForTypedef(typedefDef: TypedefDef) {
        try {
            addStubs(TypedefStubBuilder(buildingContext, typedefDef).build())
        } catch (e: Throwable) {
            context.log("Warning: cannot generate typedef ${typedefDef.name}")
        }
    }

    private fun generateStubsForGlobal(global: GlobalDecl) {
        try {
            addStubs(GlobalStubBuilder(buildingContext, global).build())
        } catch (e: Throwable) {
            context.log("Warning: cannot generate stubs for global ${global.name}")
        }
    }

    private fun generateStubsForObjCProtocol(objCProtocol: ObjCProtocol) {
        addStubs(ObjCProtocolStubBuilder(buildingContext, objCProtocol).build())
    }

    private fun generateStubsForObjCClass(objCClass: ObjCClass) {
        addStubs(ObjCClassStubBuilder(buildingContext, objCClass).build())
    }

    private fun generateStubsForObjCCategory(objCCategory: ObjCCategory) {
        addStubs(ObjCCategoryStubBuilder(buildingContext, objCCategory).build())
    }
}
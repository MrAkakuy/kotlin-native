/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.native.interop.gen

import org.jetbrains.kotlin.native.interop.indexer.*

internal data class CCalleeWrapper(val lines: List<String>)

/**
 * Some functions don't have an address (e.g. macros-based or builtins).
 * To solve this problem we generate a wrapper function.
 */
internal class CWrappersGenerator(private val context: StubIrContext) {

    private var currentFunctionWrapperId = 0

    private val packageName =
            context.configuration.pkgName.replace(INVALID_CLANG_IDENTIFIER_REGEX, "_")

    private fun generateFunctionWrapperName(functionName: String): String {
        val validFunctionName = functionName.replace(INVALID_CLANG_IDENTIFIER_REGEX, "_")
        return "${packageName}_${validFunctionName}_wrapper${currentFunctionWrapperId++}"
    }

    private fun bindSymbolToFunction(symbol: String, function: String): List<String> = listOf(
            "const void* $symbol __asm(${symbol.quoteAsKotlinLiteral()}) = (const void*) &$function;"
    )

    private data class Parameter(val type: String, val name: String)

    private fun createWrapper(
            symbolName: String,
            wrapperName: String,
            returnType: String,
            parameters: List<Parameter>,
            body: String
    ): List<String> = listOf(
            "__attribute__((always_inline))",
            "$returnType $wrapperName(${parameters.joinToString { "${it.type} ${it.name}" }}) {",
            body,
            "}",
            *bindSymbolToFunction(symbolName, wrapperName).toTypedArray()
    )

    fun generateCCalleeWrapper(function: FunctionDecl, symbolName: String): CCalleeWrapper =
            if (function.isVararg) {
                CCalleeWrapper(bindSymbolToFunction(symbolName, function.name))
            } else {
                val wrapperName = generateFunctionWrapperName(function.name)
                val owner = (function as? CxxClassFunctionDecl)?.owner
                val returnType = function.returnType.getStringRepresentation()

                val originParameters = if (owner != null && !function.isStatic)
                    listOf(Parameter(
                            "ptr",
                            CxxClassPointerType(CxxClassType(owner)),
                            false)
                    ) + function.parameters
                else
                    function.parameters

                val parameters = originParameters.mapIndexed { index, parameter ->
                    Parameter(parameter.type.getStringRepresentation(), "p$index")
                }
                val callExpressionParameters = originParameters.mapIndexed { index, parameter ->
                    when (parameter.type) {
                        is CxxClassType, is CxxClassLValueRefType -> "*p$index"
                        else -> "p$index"
                    }
                }

                val callExpression = when {
                    owner != null -> {
                        if (function.isStatic)
                            "${owner.spelling}::${function.name}(${callExpressionParameters.joinToString()})"
                        else
                            "((${owner.spelling} *) ${callExpressionParameters.first()})->${function.name}(${callExpressionParameters.drop(1).joinToString()})"
                    }
                    else -> "${function.name}(${callExpressionParameters.joinToString()})"
                }
                val wrapperBody = if (function.returnType.unwrapTypedefs() is VoidType) {
                    "$callExpression;"
                } else {
                    if (function.returnType is CxxClassLValueRefType || function.returnType is CxxClassType || function.returnType is LValueRefType)
                        "return &($callExpression);"
                    else
                        "return $callExpression;"
                }
                val wrapper = createWrapper(symbolName, wrapperName, returnType, parameters, wrapperBody)
                CCalleeWrapper(wrapper)
            }

    fun generateCGlobalGetter(globalDecl: GlobalDecl, symbolName: String): CCalleeWrapper {
        val wrapperName = generateFunctionWrapperName("${globalDecl.name}_getter")
        val returnType = globalDecl.type.getStringRepresentation()
        val wrapperBody = "return ${globalDecl.name};"
        val wrapper = createWrapper(symbolName, wrapperName, returnType, emptyList(), wrapperBody)
        return CCalleeWrapper(wrapper)
    }

    fun generateCGlobalByPointerGetter(globalDecl: GlobalDecl, symbolName: String): CCalleeWrapper {
        val wrapperName = generateFunctionWrapperName("${globalDecl.name}_getter")
        val returnType = "void*"
        val wrapperBody = "return &${globalDecl.name};"
        val wrapper = createWrapper(symbolName, wrapperName, returnType, emptyList(), wrapperBody)
        return CCalleeWrapper(wrapper)
    }

    fun generateCGlobalSetter(globalDecl: GlobalDecl, symbolName: String): CCalleeWrapper {
        val wrapperName = generateFunctionWrapperName("${globalDecl.name}_setter")
        val globalType = globalDecl.type.getStringRepresentation()
        val parameter = Parameter(globalType, "p1")
        val wrapperBody = "${globalDecl.name} = ${parameter.name};"
        val wrapper = createWrapper(symbolName, wrapperName, "void", listOf(parameter), wrapperBody)
        return CCalleeWrapper(wrapper)
    }
}

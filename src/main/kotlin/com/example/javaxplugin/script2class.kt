package com.example.javaxplugin

import kotlin.text.RegexOption.COMMENTS

data class VariableInfo(
        val type: String,
        val name: String,
        val argument: String,
        val lineNumber: Int,
        val argExpr: String
)

class CodeConverter(
        private val className: String = "GeneratedClass",
        private val packageName: String? = null
) {
    fun convert(code: String): String {
        val variables = parseSpecialJavaExpressions(code)
        val (businessLogic, _) = splitCode(code, variables)
        return buildString {
            appendPackage()
            appendImports()
            appendClassHeader()
            appendMainMethod(variables)
            appendRunMethod(variables, businessLogic)
            appendHelperMethods()
            appendClassFooter()
        }
    }

    fun parseSpecialJavaExpressions(code: String): List<VariableInfo> {
        val pattern = Regex(pattern =
        """
                ^
                (?!\s*//)           # 排除单行注释
                \s*
                ([\w.$<>,\s]+?)     # 类型部分
                \s+
                (\w+)               # 变量名
                \s*=\s*
                .*?\*\*(\w+)        # 捕获参数
                .*?;
                \s*${'$'}
        """.trimIndent(),
                option = COMMENTS
        )


        // 需要跟踪块注释状态
        var inBlockComment = false

        return code.lineSequence()
                .mapIndexedNotNull { index, line ->
                    val trimmedLine = line.trim()
                    when {
                        trimmedLine.startsWith("/*") -> {
                            inBlockComment = true
                            null
                        }

                        trimmedLine.endsWith("*/") -> {
                            inBlockComment = false
                            null
                        }

                        inBlockComment -> null
                        else -> pattern.matchEntire(trimmedLine)?.run {
                            VariableInfo(
                                    type = groupValues[1].replace("\\s+".toRegex(), " "),
                                    name = groupValues[2],
                                    argument = groupValues[3],
                                    lineNumber = index + 1,
                                    argExpr = line
                            )
                        }
                    }
                }
                .toList()
    }

    private fun splitCode(code: String, vars: List<VariableInfo>): Pair<List<String>, Set<Int>> {
        val varLines = vars.map { it.lineNumber }.toSet()
        return code.lineSequence()
                .mapIndexed { index, line -> index + 1 to line }  // 保留原始行内容
                .filter { (num, _) -> num !in varLines }          // 仅过滤变量声明行
                .map { (_, line) -> line }                        // 保留包括分号和空格的原始内容
                .toList() to varLines
    }

    private fun StringBuilder.appendPackage() {
        packageName?.takeIf { it.isNotEmpty() }?.let {
            appendLine("package $it;")
            appendLine()
        }
    }

    private fun StringBuilder.appendImports() {
        appendLine("""
            import com.alibaba.fastjson.*;
            import java.nio.file.*;
            import java.io.*;
            import java.util.*;
        """.trimIndent())
        appendLine()
    }

    private fun StringBuilder.appendClassHeader() {
        appendLine("public class $className {")
    }

    private fun StringBuilder.appendMainMethod(vars: List<VariableInfo>) {
        appendLine("    public static void main(String[] args) throws IOException {")
        appendInitializationCode(vars)
        appendDebugOutput(vars)
        appendLine("        // 业务逻辑")
        if (vars.isNotEmpty()) {
            appendLine("        run(${vars.joinToString(", ") { it.name }});")
        } else {
            appendLine("        run();")
        }
        appendLine("    }\n")
    }

    private fun StringBuilder.appendInitializationCode(vars: List<VariableInfo>) {
        vars.forEach { varInfo ->
            appendLine("        // Line ${varInfo.lineNumber}")
            append("        ${varInfo.type} ${varInfo.name} = ")
            appendResolvedExpression(varInfo)
            appendLine(";")
        }
    }

    private fun StringBuilder.appendResolvedExpression(varInfo: VariableInfo) {
        val filePath = "Paths.get(\"inputs\", \"${varInfo.argument}.json\")"
        when {
            varInfo.type.startsWith("List<") -> {
                append("parseList($filePath, new TypeReference<${varInfo.type}>(){})")
            }

            varInfo.type.startsWith("Map<") -> {
                append("parseMap($filePath, new TypeReference<${varInfo.type}>(){})")
            }

            else -> append("parseObject($filePath, ${varInfo.type}.class)")
        }
    }

    private fun StringBuilder.appendDebugOutput(vars: List<VariableInfo>) {
        appendLine("        System.out.println(\"=== 运行参数 Values ===>\");")
        vars.forEach { varInfo ->
            appendLine("        System.out.println(\"${varInfo.name} (${varInfo.type}): \" + ${varInfo.name});")
        }
        appendLine("        System.out.println(\"<=== 运行参数 Values ===\");")
        appendLine("        System.out.println();\n")
    }

    private fun StringBuilder.appendRunMethod(vars: List<VariableInfo>, businessLogic: List<String>) {
        if (businessLogic.isEmpty()) return
        appendLine("    public static void run(")
        if (vars.isNotEmpty()) {
            append("        ")
            append(
                    vars.withIndex().joinToString("\n        ") { (index, varInfo) ->
                        val comment = " // ${varInfo.argExpr}"
                        // 最后一个参数不加逗号，其他参数在注释前加逗号
                        if (index == vars.lastIndex) "${varInfo.type} ${varInfo.name}$comment"
                        else "${varInfo.type} ${varInfo.name},$comment"
                    }
            )
        }
        appendLine("\n    ) {")  // 确保右括号换行对齐
        businessLogic.forEach { line ->
            appendLine("        $line")
        }
        appendLine("    }")
        appendLine()
    }

    private fun StringBuilder.appendHelperMethods() {
        appendLine("""
            // 通用文件读取
            private static String readFile(Path path) throws IOException {
                // return new String(Files.readAllBytes(path));
                try(InputStream in = dsl.class.getResourceAsStream(path.toString())) {
                    if (in == null) {
                        throw new RuntimeException(String.format("文件%s不存在", path));
                    }
                    return new String(in.readAllBytes());   
                }
            }

            // 集合类型解析
            private static <T> java.util.List<T> parseList(
                Path path, TypeReference<java.util.List<T>> typeRef
            ) throws IOException {
                return JSON.parseObject(readFile(path), typeRef);
            }

            private static <K, V> java.util.Map<K, V> parseMap(
                Path path, TypeReference<java.util.Map<K, V>> typeRef
            ) throws IOException {
                return JSON.parseObject(readFile(path), typeRef);
            }

            // 普通对象解析
            private static <T> T parseObject(Path path, Class<T> clazz) throws IOException {
                return JSON.parseObject(readFile(path), clazz);
            }
        """.trimIndent().prependIndent("    "))
    }

    private fun StringBuilder.appendClassFooter() {
        appendLine("}")
    }
}


fun main() {
    val code = """
        // 用户配置
        String username = **user_info;
        List<Integer> scores = **score_list;
        Map<String, Double> prices = **price_map;
        boolean isAdmin = **admin_flag;

        System.out.println(username);
        // 用户配置
        System.out.println(scores);
        System.out.println(prices);
        System.out.println(isAdmin);
    """.trimIndent()

    println(CodeConverter(packageName = "").convert(code))
}

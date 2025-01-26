package com.example.javaxplugin

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.comments.Comment

data class RunMethodInfo(
        val parameters: List<ParameterInfo>,
        val methodBody: String
) {
    override fun toString(): String {
        return """
Parameters:
${parameters.joinToString("\n") { "  ${it.type} ${it.name} // ${it.comment}" }}
            
Method Body:
$methodBody
        """.trimIndent()
    }
}

data class ParameterInfo(
        val type: String,
        val name: String,
        val comment: String = ""
)

object JavaCodeParser {

    /**
     * 解析 Java 代码并提取 run 方法信息
     * @param javaCode Java 源代码字符串
     * @return RunMethodInfo 或 null（如果未找到 run 方法）
     */
    fun parseRunMethod(javaCode: String): RunMethodInfo? {
        return try {
            val cu: CompilationUnit = StaticJavaParser.parse(javaCode)
            cu.findAll(MethodDeclaration::class.java)
                    .firstOrNull { isValidRunMethod(it) }
                    ?.let { convertToRunMethodInfo(it) }
        } catch (e: Exception) {
            System.err.println("代码解析失败: ${e.message}")
            null
        }
    }

    private fun isValidRunMethod(method: MethodDeclaration): Boolean {
        return method.nameAsString == "run" &&
                method.isStatic &&
                method.isPublic
    }

    private fun convertToRunMethodInfo(method: MethodDeclaration): RunMethodInfo {
        return RunMethodInfo(
                parameters = extractParameters(method),
                methodBody = extractMethodBody(method)
        )
    }

    private fun extractParameters(method: MethodDeclaration): List<ParameterInfo> {
        return method.parameters.map { param ->
            ParameterInfo(
                    type = param.typeAsString.trim(),
                    name = param.nameAsString.trim(),
                    comment = extractParamComment(param)
            )
        }
    }

    private fun extractParamComment(param: Parameter): String {
        return param.comment
                .map(Comment::getContent)
                .map { it.replace("""^\s*[/\*]+|[/\*]+\s*${'$'}""".toRegex(), "") }
                .orElse("")
    }

    private fun extractMethodBody(method: MethodDeclaration): String {
        return method.body
                .map { body ->
                    body.toString()
                            .removeSurrounding("{", "}")
                            .trimIndent()
//                            .lines()
//                            .joinToString("\n") { it.trimEnd() }
                }
                .orElse("")
    }
}

fun main() {
    val testCode = """
        public class Demo {
            /**
             * 示例 run 方法
             * @param count 循环次数
             */
            public static void run(
               int count, // 测试注释
                String message // 
            ) {
                // 方法逻辑
                /** 
                * xxxxx
                */
                System.out.println("计数: " + count);
                System.out.println(message);
            }
        }
    """.trimIndent()

    val result = JavaCodeParser.parseRunMethod(testCode)
    println(result ?: "未找到有效的 run 方法")
}
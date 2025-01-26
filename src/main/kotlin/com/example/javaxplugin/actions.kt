package com.example.javaxplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager

class ScriptToClassAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: run {
            Messages.showInfoMessage(project, "请打开一个有效文件", "提示")
            return
        }
        val virtualFile = psiFile.virtualFile

        // 获取源码根目录
        val sourceRoot = ProjectRootManager.getInstance(project)
                .fileIndex
                .getSourceRootForFile(virtualFile) ?: run {
            Messages.showErrorDialog(project, "文件不在任何源码根目录中", "错误")
            return
        }

        // 正确获取文件名（不含扩展名）
        val originalName = virtualFile.nameWithoutExtension()

        // 使用自定义扩展
        // 计算包名
        val packageName = FileUtil.getRelativePath(
                sourceRoot.path,
                virtualFile.parent.path,
                '/'
        )?.replace('/', '.') ?: ""

        // 构建完整代码
        val converter = CodeConverter(packageName = packageName, className = originalName)
        val codeContent = converter.convert(psiFile.text)
        val argList = converter.parseSpecialJavaExpressions(psiFile.text)

        WriteCommandAction.runWriteCommandAction(project) {
            // 创建目标目录
            val targetDir = packageName.split('.')
                    .fold(
                            PsiManager.getInstance(project).findDirectory(sourceRoot)!!
                    ) { dir, subDir ->
                        dir.findSubdirectory(subDir) ?: dir.createSubdirectory(subDir)
                    }

            targetDir.findSubdirectory("inputs") ?: targetDir.createSubdirectory("inputs")

            // 创建各个参数json文件
            argList.forEach { arg ->
                val argFile = "${arg.argument}.json"
                targetDir.findSubdirectory("inputs")?.findFile(argFile)
                        ?: targetDir.findSubdirectory("inputs")?.createFile(argFile)
            }

            // 检查文件是否已存在
            val newFileName = "$originalName.java"
            val existingFile = targetDir.findFile(newFileName)

            // 如果存在则先删除
            existingFile?.delete()

            // 创建新文件
            PsiFileFactory.getInstance(project)
                    .createFileFromText(newFileName, psiFile.fileType, codeContent)
                    .also { targetDir.add(it) }
        }

        Messages.showInfoMessage(
                project,
                "",
                "操作成功"
        )
    }

    // 自定义 VirtualFile 扩展方法
    private fun VirtualFile.nameWithoutExtension(): String = name.substringBeforeLast('.')
}

class ClassToScriptAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: run {
            Messages.showInfoMessage(project, "请打开一个有效Java文件", "提示")
            return
        }
        val virtualFile = psiFile.virtualFile

        // 获取当前文件所在目录
        val parentDir = PsiManager.getInstance(project).findDirectory(virtualFile.parent) ?: run {
            Messages.showErrorDialog(project, "无法获取文件所在目录", "错误")
            return
        }

        // 构建新文件名
        val originalName = virtualFile.nameWithoutExtension
        val newFileName = "$originalName.javax"

        // 转换代码内容
        val codeContent = Class2ScriptConverter.convert(psiFile.text)

        WriteCommandAction.runWriteCommandAction(project) {
            try {
                // 创建/获取 javax 子目录
                val javaxSubDir = WriteCommandAction.runWriteCommandAction<PsiDirectory>(project) {
                    parentDir.findSubdirectory("javax") ?: parentDir.createSubdirectory("javax")
                }

                // 检查并删除已存在文件
                javaxSubDir.findFile(newFileName)?.delete()

                // 创建新文件
                val newFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(
                                newFileName,
                                FileTypeManager.getInstance().getFileTypeByExtension("javax"),
                                codeContent
                        )

                // 添加文件到子目录
                javaxSubDir.add(newFile)

                Messages.showInfoMessage(
                        project,
                        "",
                        "操作成功"
                )
            } catch (e: Exception) {
                Messages.showErrorDialog(
                        project,
                        "文件生成失败: ${e.message}",
                        "错误"
                )
            }
        }
    }

}
package com.moyue.app.util

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** 墨阅能直接入库的图书格式 */
val BOOK_EXTS = setOf("epub", "txt", "pdf")

/** 网盘书库里可点击处理的条目（zip 会在本地解开再挑书） */
val DAV_HANDLED_EXTS = BOOK_EXTS + "zip"

fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** 用 UTF-8 打开 zip；中文名乱码时改用 GBK（老 zip 常见），两者都失败返回 null */
fun openZipSmart(file: File): ZipFile? {
    runCatching { ZipFile(file, Charsets.UTF_8) }.getOrNull()?.let { z ->
        val garbled = runCatching { z.entries().asSequence().any { it.name.contains('\uFFFD') } }
            .getOrDefault(false)
        if (!garbled) return z
        runCatching { z.close() }
    }
    return runCatching { ZipFile(file, Charset.forName("GBK")) }.getOrNull()
}

/** 解压 zip 并挑出里面的电子书（epub/txt/pdf）。返回 (解压目录, 书文件列表) */
fun extractBooksFromZip(context: Context, zipFile: File): Pair<File, List<File>> {
    val outDir = File(context.cacheDir, "zip_books_${System.currentTimeMillis()}")
    outDir.mkdirs()
    val found = mutableListOf<File>()
    val z = openZipSmart(zipFile) ?: return Pair(outDir, found)
    runCatching {
        z.use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                if (extOf(e.name) !in BOOK_EXTS) continue
                val safeName = e.name.substringAfterLast('/')
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                if (safeName.isBlank()) continue
                val out = File(outDir, safeName)
                runCatching {
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                if (out.exists() && out.length() > 0) found.add(out)
            }
        }
    }
    return Pair(outDir, found)
}

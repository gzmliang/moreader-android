package com.moyue.app.localai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Local dictionary engine using pre-packaged ECDICT SQLite database + Chinese character dictionary.
 * Provides millisecond-speed offline word/character lookup.
 * 
 * Architecture:
 * - English dictionary (dictionary.db): 770k English words → Chinese definitions
 * - Chinese character dictionary (hanzi_dict.db): 8000 common characters → pinyin + radical + strokes
 * - Queries use exact match + case-insensitive match for English, direct match for Chinese
 * - Falls back to null if not found (caller should then use AI translation)
 */
object DictionaryEngine {
    private const val EN_DB_NAME = "dictionary.db"
    private const val CN_DB_NAME = "hanzi_dict.db"
    
    @Volatile
    private var enDatabase: SQLiteDatabase? = null
    @Volatile
    private var cnDatabase: SQLiteDatabase? = null
    
    // Debug log for troubleshooting
    private val debugLog = StringBuilder()
    fun getDebugLog(): String = debugLog.toString()
    fun clearDebugLog() { debugLog.clear() }
    private fun dlog(msg: String) {
        debugLog.append("[${System.currentTimeMillis() % 10000}] $msg\n")
        android.util.Log.d("DictionaryEngine", msg)
    }
    
    /** Initialize: copy DBs from assets if needed, then open */
    fun init(context: Context): Boolean {
        dlog("=== init called ===")
        var success = true
        
        // Initialize English dictionary
        if (enDatabase?.isOpen != true) {
            try {
                val dbPath = context.getDatabasePath(EN_DB_NAME).parent ?: "null"
                val dbFile = File(dbPath, EN_DB_NAME)
                dlog("EN dict path: ${dbFile.absolutePath}")
                dlog("EN dict exists: ${dbFile.exists()}, size: ${if (dbFile.exists()) dbFile.length() / 1024 / 1024 else 0}MB")
                
                if (!dbFile.exists()) {
                    dlog("EN dict not found, copying from assets...")
                    val assetList = context.assets.list("")?.toList() ?: emptyList()
                    dlog("Assets contains .db: ${assetList.filter { it.endsWith(".db") }}")
                    
                    try {
                        context.assets.open(EN_DB_NAME).use { input ->
                            FileOutputStream(dbFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        dlog("EN dict copied, size: ${dbFile.length() / 1024 / 1024}MB")
                    } catch (e: Exception) {
                        dlog("EN dict copy FAILED: ${e.message}")
                    }
                }
                
                enDatabase = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
                dlog("EN dict opened: isOpen=${enDatabase?.isOpen}")
                
                // Check if schema matches
                try {
                    enDatabase?.rawQuery("SELECT phonetic FROM dictionary LIMIT 1", null)?.close()
                    dlog("EN dict schema OK")
                } catch (e: Exception) {
                    dlog("EN dict schema mismatch, recreating from assets...")
                    enDatabase?.close()
                    enDatabase = null
                    dbFile.delete()
                    context.assets.open(EN_DB_NAME).use { input ->
                        FileOutputStream(dbFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    dlog("EN dict replaced, size: ${dbFile.length() / 1024 / 1024}MB")
                    enDatabase = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )
                }
                
                // Verify by counting
                val cursor = enDatabase?.rawQuery("SELECT COUNT(*) FROM dictionary", null)
                if (cursor?.moveToFirst() == true) {
                    dlog("EN dict entries: ${cursor.getInt(0)}")
                    cursor.close()
                }
            } catch (e: Exception) {
                dlog("EN dict init FAILED: ${e.message}")
                android.util.Log.e("DictionaryEngine", "Failed to init EN dict: ${e.message}")
                success = false
            }
        } else {
            dlog("EN dict already open")
        }
        
        // Initialize Chinese character dictionary
        val dbPath = context.getDatabasePath(CN_DB_NAME).parent ?: "null"
        val dbFile = File(dbPath, CN_DB_NAME)
        
        dlog("CN dict path: ${dbFile.absolutePath}")
        dlog("CN dict exists: ${dbFile.exists()}, size: ${if (dbFile.exists()) dbFile.length() / 1024 else 0}KB")
        
        // Force close existing connection
        cnDatabase?.close()
        cnDatabase = null
        
        // Only copy from assets if file doesn't exist
        if (!dbFile.exists()) {
            dlog("CN dict not found, copying from assets...")
            try {
                context.assets.open(CN_DB_NAME).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
                dlog("CN dict copied from assets, size=${dbFile.length() / 1024}KB")
            } catch (e: Exception) {
                dlog("CN dict copy from assets FAILED: ${e.message}")
                android.util.Log.e("DictionaryEngine", "CN copy failed", e)
                success = false
                return@init false
            }
        } else {
            dlog("CN dict already exists, skipping copy")
        }
        
        if (cnDatabase?.isOpen != true) {
            try {
                cnDatabase = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
                dlog("CN dict opened: isOpen=${cnDatabase?.isOpen}")
                
                // Check if schema matches (old version may not have word_groups column)
                try {
                    cnDatabase?.rawQuery("SELECT word_groups FROM hanzi_dict LIMIT 1", null)?.close()
                    dlog("CN dict schema OK")
                } catch (e: Exception) {
                    dlog("CN dict schema mismatch, recreating from assets...")
                    cnDatabase?.close()
                    cnDatabase = null
                    dbFile.delete()
                    context.assets.open(CN_DB_NAME).use { input ->
                        FileOutputStream(dbFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    dlog("CN dict replaced, size: ${dbFile.length() / 1024}KB")
                    cnDatabase = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )
                }
                
                // Verify
                val cursor = cnDatabase?.rawQuery("SELECT COUNT(*) FROM hanzi_dict", null)
                if (cursor?.moveToFirst() == true) {
                    dlog("CN dict entries: ${cursor.getInt(0)}")
                    cursor.close()
                }
            } catch (e: Exception) {
                dlog("CN dict init FAILED: ${e.message}")
                android.util.Log.e("DictionaryEngine", "Failed to init CN dict: ${e.message}")
                success = false
            }
        } else {
            dlog("CN dict already open")
        }
        
        dlog("init result: success=$success")
        return success
    }

    /** Check if either dictionary is ready */
    val isReady: Boolean
        get() = enDatabase?.isOpen == true || cnDatabase?.isOpen == true

    /**
     * Query a word or Chinese character.
     * Auto-detects: if input contains Chinese chars, query hanzi dict first, then English dict.
     * Otherwise query English dict first.
     */
    fun query(input: String): com.moyue.app.data.models.DictionaryResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return com.moyue.app.data.models.DictionaryResult.NotFound
        
        val hasChinese = trimmed.any { it in '\u4e00'..'\u9fff' }
        dlog("query: input='$trimmed' hasChinese=$hasChinese")
        
        val result = if (hasChinese) {
            val hanziRes = queryHanzi(trimmed)
            dlog("queryHanzi result: ${if (hanziRes is com.moyue.app.data.models.DictionaryResult.Found) "Found" else "null"}")
            if (hanziRes != null) hanziRes
            else {
                val enRes = queryEnglish(trimmed)
                dlog("queryEnglish fallback result: ${if (enRes is com.moyue.app.data.models.DictionaryResult.Found) "Found" else "null"}")
                enRes ?: com.moyue.app.data.models.DictionaryResult.NotFound
            }
        } else {
            val enRes = queryEnglish(trimmed)
            dlog("queryEnglish result: ${if (enRes is com.moyue.app.data.models.DictionaryResult.Found) "Found" else "null"}")
            if (enRes != null) enRes
            else {
                val hanziRes = queryHanzi(trimmed)
                dlog("queryHanzi fallback result: ${if (hanziRes is com.moyue.app.data.models.DictionaryResult.Found) "Found" else "null"}")
                hanziRes ?: com.moyue.app.data.models.DictionaryResult.NotFound
            }
        }
        
        return result
    }

    /** Query English word from ECDICT */
    private fun queryEnglish(word: String): com.moyue.app.data.models.DictionaryResult? {
        val db = enDatabase
        dlog("queryEnglish: db=${if (db == null) "null" else "open=${db.isOpen}"}")
        if (db == null || !db.isOpen) return null
        
        try {
            var cursor = db.rawQuery(
                "SELECT * FROM dictionary WHERE word = ? LIMIT 1",
                arrayOf(word)
            )
            
            if (cursor.moveToFirst()) {
                val found = cursorToEnglishEntry(cursor)
                dlog("queryEnglish EXACT found: ${found.word}")
                cursor.close()
                return com.moyue.app.data.models.DictionaryResult.Found(found)
            }
            cursor.close()
            
            cursor = db.rawQuery(
                "SELECT * FROM dictionary WHERE LOWER(word) = LOWER(?) LIMIT 1",
                arrayOf(word)
            )
            
            if (cursor.moveToFirst()) {
                val found = cursorToEnglishEntry(cursor)
                dlog("queryEnglish LOWER found: ${found.word}")
                cursor.close()
                return com.moyue.app.data.models.DictionaryResult.Found(found)
            }
            cursor.close()
            
            dlog("queryEnglish not found for: $word")
            return null
        } catch (e: Exception) {
            dlog("queryEnglish ERROR: ${e.message}")
            android.util.Log.e("DictionaryEngine", "EN query failed: ${e.message}")
            return null
        }
    }

    /** Query Chinese character from hanzi_dict */
    private fun queryHanzi(char: String): com.moyue.app.data.models.DictionaryResult? {
        val db = cnDatabase
        dlog("queryHanzi: db=${if (db == null) "null" else "open=${db.isOpen}"} char=$char len=${char.length}")
        if (db == null || !db.isOpen) return null
        
        if (char.length != 1) return null
        
        try {
            val cursor = db.rawQuery(
                "SELECT * FROM hanzi_dict WHERE character = ? LIMIT 1",
                arrayOf(char)
            )
            
            if (cursor.moveToFirst()) {
                val entry = cursorToHanziEntry(cursor)
                dlog("queryHanzi found: ${entry.word}")
                cursor.close()
                return com.moyue.app.data.models.DictionaryResult.Found(entry)
            }
            cursor.close()
            dlog("queryHanzi not found for: $char")
            return null
        } catch (e: Exception) {
            dlog("queryHanzi ERROR: ${e.message}")
            android.util.Log.e("DictionaryEngine", "CN query failed: ${e.message}")
            return null
        }
    }

    private fun cursorToEnglishEntry(cursor: android.database.Cursor): com.moyue.app.data.models.DictionaryEntry {
        return com.moyue.app.data.models.DictionaryEntry(
            word = cursor.getString(cursor.getColumnIndexOrThrow("word")),
            phonetic = cursor.getString(cursor.getColumnIndexOrThrow("phonetic"))
                ?.takeIf { it.isNotBlank() },
            translation = cursor.getString(cursor.getColumnIndexOrThrow("translation")),
            pos = cursor.getString(cursor.getColumnIndexOrThrow("pos"))
                ?.takeIf { it.isNotBlank() },
            collins = cursor.getInt(cursor.getColumnIndexOrThrow("collins")),
            oxford = cursor.getInt(cursor.getColumnIndexOrThrow("oxford")),
            bnc = cursor.getInt(cursor.getColumnIndexOrThrow("bnc")),
            frq = cursor.getInt(cursor.getColumnIndexOrThrow("frq")),
            exchangeJson = cursor.getString(cursor.getColumnIndexOrThrow("exchange"))
                ?.takeIf { it.isNotBlank() },
            detailJson = cursor.getString(cursor.getColumnIndexOrThrow("detail"))
                ?.takeIf { it.isNotBlank() },
        )
    }

    private fun cursorToHanziEntry(cursor: android.database.Cursor): com.moyue.app.data.models.DictionaryEntry {
        val char = cursor.getString(cursor.getColumnIndexOrThrow("character"))
        val pinyin = cursor.getString(cursor.getColumnIndexOrThrow("pinyin"))
        val radical = cursor.getString(cursor.getColumnIndexOrThrow("radical"))
        val strokes = cursor.getInt(cursor.getColumnIndexOrThrow("total_strokes"))
        val definition = cursor.getString(cursor.getColumnIndexOrThrow("definition"))
        val wordGroupsJson = cursor.getString(cursor.getColumnIndexOrThrow("word_groups"))
        
        // Format for display: 语文书规范风格
        val displayText = buildString {
            append(char)
            if (!pinyin.isNullOrBlank()) {
                // Convert pinyin numbers to tone marks: "gan1 gan4" -> "gān gàn"
                val tonedPinyin = convertPinyinNumbers(pinyin)
                append("  【${tonedPinyin}】")
            }
            append("\n")
            if (!radical.isNullOrBlank()) append("部首：${radical}    ")
            if (strokes > 0) append("笔画：${strokes}")
            append("\n\n")
            
            if (!definition.isNullOrBlank()) {
                var cleaned = convertPinyinNumbers(definition)
                // Strip CC-CEDICT English interference but KEEP Chinese chars
                cleaned = Regex("variant of ", RegexOption.IGNORE_CASE).replace(cleaned, "")
                cleaned = Regex("\\(variant of[^)]*\\)", RegexOption.IGNORE_CASE).replace(cleaned, "")
                cleaned = Regex("\\(see[^)]*\\)", RegexOption.IGNORE_CASE).replace(cleaned, "")
                cleaned = Regex("\\[.*?\\]").replace(cleaned, "")  // Remove [pinyin]
                cleaned = Regex("abbr\\.?\\s*").replace(cleaned, "")
                cleaned = cleaned.replace("|", " / ")  // CEDICT uses | as separator
                cleaned = cleaned.trim()
                // If cleaned is empty or too short, show placeholder
                if (cleaned.length > 1) {
                    append(cleaned)
                } else {
                    append("（释义待补充）")
                }
            } else {
                append("（释义待补充）")
            }
            
            // 组词
            if (!wordGroupsJson.isNullOrBlank()) {
                try {
                    val groups = org.json.JSONArray(wordGroupsJson)
                    if (groups.length() > 0) {
                        append("\n\n📝 组词：\n")
                        for (i in 0 until groups.length()) {
                            val pair = groups.getJSONArray(i)
                            val word = pair.getString(0)
                            var def = if (pair.length() > 1) pair.getString(1) else ""
                            // Convert pinyin numbers in word group definitions too
                            def = convertPinyinNumbers(def).trim()
                            if (def.isNotEmpty()) {
                                append("  • $word  $def\n")
                            } else {
                                append("  • $word\n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore JSON parse errors
                }
            }
        }
        
        return com.moyue.app.data.models.DictionaryEntry(
            word = char,
            phonetic = pinyin?.takeIf { it.isNotBlank() },
            translation = displayText,
            pos = radical?.takeIf { it.isNotBlank() },
            collins = 0,
            oxford = 0,
            bnc = 0,
            frq = 0,
            exchangeJson = null,
            detailJson = null,
        )
    }

    /**
     * Convert pinyin number notation to proper tone marks.
     * Handles: bare pinyin "gan1 gan4" → "gān gàn"
     * Handles: bracketed "[fen1 zi3]" → "[fēn zǐ]"
     * Also strips English interference: "variant of...", "(see...)" etc.
     */
    private fun convertPinyinNumbers(text: String): String {
        var result = text
        // Strip English interference patterns
        result = Regex("\\(variant of[^)]*\\)", RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("\\(see[^)]*\\)", RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("variant of [^\\[]*", RegexOption.IGNORE_CASE).replace(result, "")
        // Convert bracketed pinyin
        result = Regex("\\[([^\\]]+)\\]").replace(result) { match ->
            val content = match.groupValues[1]
            val converted = content.split(" ").joinToString(" ") { convertSyllable(it) }
            "[$converted]"
        }
        // Convert bare pinyin (not inside brackets): space-separated syllables with trailing digits
        // e.g. "gan1 gan4" → "gān gàn"
        result = result.split(" ").joinToString(" ") { part ->
            if (Regex("^[a-züv]+[1-5]$").matches(part)) convertSyllable(part) else part
        }
        return result.trim()
    }

    private fun convertSyllable(syllable: String): String {
        val toneMatch = Regex("(.*?)([1-5])$").matchEntire(syllable) ?: return syllable
        val base = toneMatch.groupValues[1]
        val tone = toneMatch.groupValues[2].toIntOrNull() ?: return syllable
        if (base.isEmpty() || tone < 1 || tone > 5) return syllable

        // Find the vowel that should carry the tone mark
        // Priority: a > o > e > iu->u > ui->i > i > u > ü/v
        val idx = when {
            'a' in base -> base.lastIndexOf('a')
            'o' in base -> base.lastIndexOf('o')
            'e' in base -> base.lastIndexOf('e')
            "iu" in base -> base.indexOf('u')
            "ui" in base -> base.indexOf('i')
            'i' in base -> base.lastIndexOf('i')
            'u' in base -> base.lastIndexOf('u')
            'ü' in base || 'v' in base -> base.indexOf('ü').takeIf { it >= 0 } ?: base.indexOf('v')
            else -> -1
        }

        if (idx >= 0) {
            val original = base[idx]
            val toned = toneMap[original]?.getOrNull(tone - 1)
            if (toned != null) {
                return base.substring(0, idx) + toned + base.substring(idx + 1)
            }
        }
        return base
    }

    private val toneMap = mapOf(
        'a' to "āáǎàā", 'o' to "ōóǒòō", 'e' to "ēéěèē",
        'i' to "īíǐìī", 'u' to "ūúǔùū", 'ü' to "ǖǘǚǜǖ", 'v' to "ǖǘǚǜǖ"
    )

    /** Release database resources */
    fun release() {
        enDatabase?.close()
        enDatabase = null
        cnDatabase?.close()
        cnDatabase = null
    }
}

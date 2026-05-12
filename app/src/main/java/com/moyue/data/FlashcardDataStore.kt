package com.moyue.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 闪卡数据存储在独立 JSON 文件中，与主数据库完全解耦。
 * 文件路径：context.filesDir/flashcards.json
 * 所有操作通过 Mutex 保证线程安全。
 */
class FlashcardDataStore(context: Context) {

    private val flashcardFile = File(context.filesDir, "flashcards.json")
    private val plansFile = File(context.filesDir, "flashcard_plans.json")
    private val mutex = Mutex()

    data class Flashcard(
        val id: Long,
        val word: String,
        val pronunciation: String? = null,
        val partOfSpeech: String? = null,
        val chineseDef: String? = null,
        val englishDef: String? = null,
        val exampleText: String? = null,
        val exampleTranslation: String? = null,
        val intervalMinutes: Long = 0,
        val repetition: Int = 0,
        val dueDate: Long = 0,
        val createdAt: Long = System.currentTimeMillis(),
        val plan: String = DEFAULT_PLAN,
    )

    companion object {
        const val DEFAULT_PLAN = "默认"
        // SM-2 inspired intervals (minutes): 1 → 10 → 1day → 3days → 7days → 15days → 30days
        val INTERVALS = listOf(1L, 10L, 1440L, 4320L, 10080L, 21600L, 43200L)
    }

    suspend fun getAllFlashcards(): List<Flashcard> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!flashcardFile.exists()) return@withContext emptyList()
            try {
                val raw = flashcardFile.readText()
                if (raw.isBlank()) return@withContext emptyList()
                val json = JSONArray(raw)
                val cards = (0 until json.length()).map { i ->
                    val obj = json.getJSONObject(i)
                    Flashcard(
                        id = obj.getLong("id"),
                        word = obj.getString("word"),
                        pronunciation = obj.optString("pronunciation", null),
                        partOfSpeech = obj.optString("partOfSpeech", null),
                        chineseDef = obj.optString("chineseDef", null),
                        englishDef = obj.optString("englishDef", null),
                        exampleText = obj.optString("exampleText", null),
                        exampleTranslation = obj.optString("exampleTranslation", null),
                        intervalMinutes = obj.optLong("intervalMinutes", 0),
                        repetition = obj.optInt("repetition", 0),
                        dueDate = obj.optLong("dueDate", 0),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        plan = obj.optString("plan", DEFAULT_PLAN),
                    )
                }
                // Deduplicate by word — keep first occurrence, reassign IDs to duplicates
                val seen = mutableSetOf<String>()
                val deduped = cards.map { card ->
                    val key = card.word.lowercase()
                    if (seen.contains(key)) {
                        // Duplicate word — give it a unique ID so LazyColumn doesn't crash
                        card.copy(id = generateId())
                    } else {
                        seen.add(key)
                        card
                    }
                }
                deduped
            } catch (e: Exception) {
                Log.e("FlashcardDataStore", "Failed to read flashcards", e)
                emptyList()
            }
        }
    }

    suspend fun getDueFlashcards(now: Long = System.currentTimeMillis()): List<Flashcard> {
        return getAllFlashcards().filter { it.dueDate <= now }.sortedBy { it.dueDate }
    }

    suspend fun addFlashcard(card: Flashcard): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAllUnsafe()
            if (all.any { it.word.equals(card.word, ignoreCase = true) }) return@withContext false
            all.toMutableList().apply { add(card) }.let { writeAllUnsafe(it) }
            return@withContext true
        }
    }

    suspend fun updateFlashcard(card: Flashcard) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAllUnsafe().toMutableList()
            val index = all.indexOfFirst { it.id == card.id }
            if (index >= 0) {
                all[index] = card
                writeAllUnsafe(all)
            }
        }
    }

    suspend fun deleteFlashcard(id: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAllUnsafe().filter { it.id != id }
            writeAllUnsafe(all)
        }
    }

    suspend fun isWordExists(word: String): Boolean {
        return getAllFlashcards().any { it.word.equals(word, ignoreCase = true) }
    }

    suspend fun getPlanNames(): List<String> {
        val filePlans = loadPlansFromFile()
        // Merge with plans from existing cards (for backward compatibility)
        val cardPlans = getAllFlashcards().map { it.plan }.toSet()
        return (filePlans.toSet() + cardPlans).sorted()
    }

    private fun loadPlansFromFile(): List<String> {
        return try {
            if (plansFile.exists()) {
                val raw = plansFile.readText()
                if (raw.isNotBlank()) {
                    JSONArray(raw).let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }
                } else emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePlansToFile(plans: List<String>) {
        try {
            val json = JSONArray()
            plans.forEach { json.put(it) }
            plansFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e("FlashcardDataStore", "savePlansToFile error", e)
        }
    }

    suspend fun createPlan(name: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = loadPlansFromFile()
            if (existing.contains(name)) return@withContext
            savePlansToFile(existing + name)
        }
    }

    suspend fun deletePlan(planName: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Remove cards for this plan
            val remainingCards = readAllUnsafe().filter { it.plan != planName }
            writeAllUnsafe(remainingCards)
            // Remove plan name from plans file
            val existing = loadPlansFromFile()
            savePlansToFile(existing - planName)
        }
    }

    suspend fun renamePlan(oldName: String, newName: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAllUnsafe().map { card ->
                if (card.plan == oldName) card.copy(plan = newName) else card
            }
            writeAllUnsafe(all)
        }
    }

    // Thread-safe counter — avoids duplicate IDs during batch import
    private val idCounter = AtomicLong(System.currentTimeMillis() * 1000)
    fun generateId(): Long = idCounter.incrementAndGet()

    // Internal unsafe operations — must only be called inside mutex.withLock
    private fun readAllUnsafe(): List<Flashcard> {
        if (!flashcardFile.exists()) return emptyList()
        try {
            val raw = flashcardFile.readText()
            if (raw.isBlank()) return emptyList()
            val json = JSONArray(raw)
                return (0 until json.length()).map { i ->
                    val obj = json.getJSONObject(i)
                    Flashcard(
                        id = obj.getLong("id"),
                        word = obj.getString("word"),
                        pronunciation = obj.optString("pronunciation", null),
                        partOfSpeech = obj.optString("partOfSpeech", null),
                        chineseDef = obj.optString("chineseDef", null),
                        englishDef = obj.optString("englishDef", null),
                        exampleText = obj.optString("exampleText", null),
                        exampleTranslation = obj.optString("exampleTranslation", null),
                        intervalMinutes = obj.optLong("intervalMinutes", 0),
                        repetition = obj.optInt("repetition", 0),
                        dueDate = obj.optLong("dueDate", 0),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        plan = obj.optString("plan", DEFAULT_PLAN),
                    )
                }
        } catch (e: Exception) {
            Log.e("FlashcardDataStore", "readAllUnsafe error", e)
            return emptyList()
        }
    }

    private fun writeAllUnsafe(cards: List<Flashcard>) {
        try {
            val json = JSONArray()
            cards.forEach { card ->
                json.put(JSONObject().apply {
                    put("id", card.id)
                    put("word", card.word)
                    card.pronunciation?.let { put("pronunciation", it) }
                    card.partOfSpeech?.let { put("partOfSpeech", it) }
                    card.chineseDef?.let { put("chineseDef", it) }
                    card.englishDef?.let { put("englishDef", it) }
                    card.exampleText?.let { put("exampleText", it) }
                    card.exampleTranslation?.let { put("exampleTranslation", it) }
                    put("intervalMinutes", card.intervalMinutes)
                    put("repetition", card.repetition)
                    put("dueDate", card.dueDate)
                    put("createdAt", card.createdAt)
                    put("plan", card.plan)
                })
            }
            // Atomic write: write to temp file then rename
            val tempFile = File(flashcardFile.parentFile, flashcardFile.name + ".tmp")
            tempFile.writeText(json.toString(2))
            tempFile.renameTo(flashcardFile)
        } catch (e: Exception) {
            Log.e("FlashcardDataStore", "writeAllUnsafe error", e)
        }
    }
}

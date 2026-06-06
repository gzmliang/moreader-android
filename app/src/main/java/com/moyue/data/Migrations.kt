package com.moyue.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create bookmarks table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL,
                chapterTitle TEXT,
                progress REAL NOT NULL,
                cfi TEXT,
                createdAt INTEGER NOT NULL
            )
        """)
        
        // Create highlights table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS highlights (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL,
                startParagraph INTEGER NOT NULL,
                startOffset INTEGER NOT NULL,
                endParagraph INTEGER NOT NULL,
                endOffset INTEGER NOT NULL,
                text TEXT NOT NULL,
                note TEXT,
                color INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """)
        
        // Create vocabulary table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS vocabulary (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                word TEXT NOT NULL,
                pronunciation TEXT,
                partOfSpeech TEXT,
                definition TEXT,
                example TEXT,
                bookId INTEGER,
                chapterIndex INTEGER,
                createdAt INTEGER NOT NULL
            )
        """)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add paragraph-based precision to bookmarks
        database.execSQL("ALTER TABLE bookmarks ADD COLUMN paragraphIndex INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE bookmarks ADD COLUMN paragraphText TEXT")
        database.execSQL("ALTER TABLE bookmarks DROP COLUMN cfi")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add structured vocabulary fields
        database.execSQL("ALTER TABLE vocabulary ADD COLUMN chineseDef TEXT")
        database.execSQL("ALTER TABLE vocabulary ADD COLUMN englishDef TEXT")
        database.execSQL("ALTER TABLE vocabulary ADD COLUMN wordForms TEXT")
        database.execSQL("ALTER TABLE vocabulary ADD COLUMN exampleJson TEXT")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add paragraph index and theme to books table for persistent state
        database.execSQL("ALTER TABLE books ADD COLUMN currentParagraphIndex INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE books ADD COLUMN themeId TEXT NOT NULL DEFAULT 'default'")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add font size to books table for persistent state
        database.execSQL("ALTER TABLE books ADD COLUMN fontSize INTEGER NOT NULL DEFAULT 18")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add per-book TTS config to books table
        database.execSQL("ALTER TABLE books ADD COLUMN ttsProvider TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE books ADD COLUMN ttsVoice TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE books ADD COLUMN ttsSpeed REAL NOT NULL DEFAULT 1.0")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add plan/notebook column to vocabulary for multi-notebook support
        database.execSQL("ALTER TABLE vocabulary ADD COLUMN plan TEXT NOT NULL DEFAULT '默认'")
    }
}

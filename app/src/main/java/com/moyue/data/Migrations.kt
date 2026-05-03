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
        // Migration from 2 to 3 (if needed)
        // Already has all tables from 1->2 migration
    }
}

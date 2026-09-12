package com.moyue.ai.model

data class StructuredRelation(
    val category: String = "other", // "parent", "spouse", "child", "sibling", "ally", "rival", "other"
    val label: String = "", // e.g. "Father", "Spouse", "Son", "Ally", "Enemy"
    val target: String = "" // e.g. "Robb Stark", "Catelyn Tully"
)

data class CharacterCard(
    val nameOriginal: String,
    val nameTranslation: String,
    val faction: String,
    val role: String,
    val relationships: List<String> = emptyList(),
    val bioOriginal: String = "",
    val bioTranslation: String = "",
    val structuredRelations: List<StructuredRelation> = emptyList()
) {
    /**
     * Resolves categorized relations, seamlessly supporting both new structured relations
     * and auto-parsed legacy relationship strings.
     */
    fun getResolvedRelations(): List<StructuredRelation> {
        if (structuredRelations.isNotEmpty()) return structuredRelations

        // Auto-parse legacy text relations like "Father to Robb, Sansa", "Hand of the King to Robert"
        return relationships.map { relText ->
            val lower = relText.lowercase()
            val category = when {
                lower.contains("father to") || lower.contains("mother to") || lower.contains("parent to") ||
                        lower.contains("children") || lower.contains("has children") -> "child"

                lower.contains("father of") || lower.contains("mother of") || lower.contains("son of") ||
                        lower.contains("daughter of") || lower.contains("child of") -> "parent"

                lower.contains("wife") || lower.contains("husband") || lower.contains("spouse") ||
                        lower.contains("married to") || lower.contains("consort") || lower.contains("partner") -> "spouse"

                lower.contains("brother") || lower.contains("sister") || lower.contains("sibling") ||
                        lower.contains("twin") -> "sibling"

                lower.contains("rival") || lower.contains("enemy") || lower.contains("opponent") ||
                        lower.contains("betrayed by") || lower.contains("nemesis") -> "rival"

                lower.contains("ally") || lower.contains("friend") || lower.contains("hand of the king") ||
                        lower.contains("sworn to") || lower.contains("servant to") || lower.contains("loyal to") ||
                        lower.contains("vassal to") || lower.contains("mentor") || lower.contains("protector") -> "ally"

                else -> "other"
            }

            // Extract cleaner label & target if possible
            val parts = relText.split(" to ", " of ", " with ", ": ", limit = 2)
            if (parts.size == 2) {
                StructuredRelation(
                    category = category,
                    label = parts[0].trim(),
                    target = parts[1].trim()
                )
            } else {
                StructuredRelation(
                    category = category,
                    label = "",
                    target = relText.trim()
                )
            }
        }
    }
}

data class PlotStage(
    val stage: String, // e.g. "Opening / 起因"
    val eventOriginal: String,
    val eventTranslation: String
)

data class AiPlotResult(
    val bookId: String,
    val chapterIndex: Int,
    val scope: String,
    val coreDynamicsOriginal: String = "",
    val coreDynamicsTranslation: String = "",
    val characters: List<CharacterCard> = emptyList(),
    val timeline: List<PlotStage> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

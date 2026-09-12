package com.moyue.ai.model

data class StructuredRelation(
    val category: String = "other", // "parent", "spouse", "child", "sibling", "ally", "rival", "other"
    val label: String = "", // e.g. "Father", "Spouse", "Son", "Ally", "Enemy"
    val target: String = "" // e.g. "Robb Stark", "Catelyn Tully"
)

data class CharacterCard(
    val nameOriginal: String = "",
    val nameTranslation: String = "",
    val faction: String = "",
    val role: String = "",
    val relationships: List<String> = emptyList(),
    val bioOriginal: String = "",
    val bioTranslation: String = "",
    val structuredRelations: List<StructuredRelation> = emptyList()
) {
    /**
     * Resolves categorized relations, seamlessly supporting both new structured relations
     * and auto-parsed legacy relationship strings.
     * Automatically expands multi-person targets (e.g. "Robb, Sansa, Arya, Bran, and Rickon")
     * into discrete individual relation nodes.
     */
    fun getResolvedRelations(): List<StructuredRelation> {
        val nonNullStructured = (structuredRelations ?: emptyList()).filterNotNull()
        val nonNullRelationships = (relationships ?: emptyList()).filterNotNull()

        val rawList = if (nonNullStructured.isNotEmpty()) {
            nonNullStructured
        } else {
            nonNullRelationships.mapNotNull { relText ->
                if (relText.isBlank()) return@mapNotNull null
                val lower = relText.lowercase().trim()
                val category = when {
                    // 1. Spouse / Partner
                    lower.contains("wife") || lower.contains("husband") || lower.contains("spouse") ||
                            lower.contains("married") || lower.contains("consort") || lower.contains("partner") ||
                            lower.contains("queen to") || lower.contains("king to") ||
                            lower.contains("妻") || lower.contains("夫") || lower.contains("配偶") -> "spouse"

                    // 2. Parents (Ancestors / Ascendants): "Son of Rickard", "Daughter of Hoster"
                    lower.contains("son of") || lower.contains("daughter of") || lower.contains("child of") ||
                            lower.startsWith("parents:") || lower.startsWith("parent:") || lower.contains("born to") ||
                            lower.contains("生父") || lower.contains("生母") -> "parent"

                    // 3. Children (Descendants): "Father to...", "Father: Robb", "Mother to...", "Children:..."
                    lower.startsWith("father:") || lower.contains("father to") || lower.contains("father of") ||
                            lower.startsWith("mother:") || lower.contains("mother to") || lower.contains("mother of") ||
                            lower.startsWith("son:") || lower.startsWith("daughter:") || lower.startsWith("children:") ||
                            lower.contains("children to") || lower.contains("has children") || lower.contains("has son") ||
                            lower.contains("has daughter") || lower.contains("father") || lower.contains("mother") ||
                            lower.contains("长子") || lower.contains("幼女") || lower.contains("儿子") || lower.contains("女儿") -> "child"

                    // 4. Siblings
                    lower.contains("brother") || lower.contains("sister") || lower.contains("sibling") ||
                            lower.contains("twin") || lower.contains("兄") || lower.contains("弟") || lower.contains("姐") || lower.contains("妹") -> "sibling"

                    // 5. Allies / Lieges
                    lower.contains("ally") || lower.contains("friend") || lower.contains("hand of the king") ||
                            lower.contains("sworn to") || lower.contains("servant to") || lower.contains("loyal to") ||
                            lower.contains("vassal to") || lower.contains("mentor") || lower.contains("protector") ||
                            lower.contains("king") || lower.contains("lord") ||
                            lower.contains("臣") || lower.contains("盟友") || lower.contains("挚友") || lower.contains("封臣") -> "ally"

                    // 6. Rivals / Foes
                    lower.contains("rival") || lower.contains("enemy") || lower.contains("opponent") ||
                            lower.contains("betrayed by") || lower.contains("nemesis") || lower.contains("foe") ||
                            lower.contains("仇") || lower.contains("敌") || lower.contains("宿敌") -> "rival"

                    else -> "other"
                }

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

        // Expand multi-person targets like "Robb, Sansa, Arya, Bran, and Rickon"
        val expanded = mutableListOf<StructuredRelation>()
        for (rel in rawList) {
            val target = rel.target ?: ""
            val category = rel.category ?: "other"
            val label = rel.label ?: ""
            val safeRel = rel.copy(category = category, label = label, target = target)

            val targets = target.split(",", " and ", "、", "，")
                .map { it.trim().removePrefix("and ").trim() }
                .filter { it.isNotBlank() && it.length > 1 }

            if (targets.size > 1) {
                targets.forEach { singleTarget ->
                    expanded.add(safeRel.copy(target = singleTarget))
                }
            } else {
                expanded.add(safeRel)
            }
        }
        return expanded
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

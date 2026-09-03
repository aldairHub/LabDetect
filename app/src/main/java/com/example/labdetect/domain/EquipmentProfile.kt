package com.example.labdetect.domain

data class EquipmentVariant(
    val id: String,
    val displayName: String,
    val manufacturer: String?,
    val model: String?,
    val manualStatus: String,
    val manualTitle: String?,
    val manualUrl: String?,
    val generalReferenceAvailable: Boolean
) {
    fun assistantContext(baseName: String): String = buildString {
        append(baseName)
        append(" del laboratorio de Bromatología")
        append(", variante: ").append(displayName)
        manufacturer?.let { append(", fabricante: ").append(it) }
        model?.let { append(", modelo: ").append(it) }
        append(", estado de documentación: ").append(manualStatus)
        manualTitle?.let { append(", documento vinculado: ").append(it) }
    }
}

data class EquipmentProfile(
    val id: String,
    val displayName: String,
    val variants: List<EquipmentVariant>
) {
    fun assistantContext(): String = when (variants.size) {
        0 -> "$displayName del laboratorio de Bromatología, sin variante documentada"
        1 -> variants.first().assistantContext(displayName)
        else -> buildString {
            append(displayName)
            append(" del laboratorio de Bromatología. Hay varias variantes físicas y la cámara todavía no distingue el submodelo: ")
            append(variants.joinToString("; ") { it.assistantContext(displayName) })
            append(". Antes de dar instrucciones específicas, pregunta cuál variante tiene delante.")
        }
    }
}

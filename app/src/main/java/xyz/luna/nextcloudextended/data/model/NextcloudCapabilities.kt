package xyz.luna.nextcloudextended.data.model

data class NextcloudCapabilities(
    val discovered: Boolean,
    val serverVersion: String?,
    val availableKeys: Set<String>
) {
    fun has(key: String): Boolean = availableKeys.contains(key.lowercase())

    companion object {
        fun unavailable() = NextcloudCapabilities(false, null, emptySet())
    }
}

package dev.sixbridge.manager.app

private const val MAX_APK_BASE_NAME_CODE_POINTS = 96
private val unsafeFileNameCharacters = Regex("""[\\/:*?"<>|\p{Cc}]""")

internal fun suggestedWrapperApkName(
    extensionName: String,
    extensionVersion: String,
    extensionId: String,
    wrapperVersionCode: Int,
): String {
    val safeName = extensionName.safeFileNameSegment().ifBlank { extensionId }
    val safeVersion = extensionVersion.safeFileNameSegment().ifBlank {
        wrapperVersionCode.toString()
    }
    val shortId = extensionId.takeCodePoints(6)
    val suffix = "-$shortId"
    val prefix = "$safeName-$safeVersion"
    val maxPrefixCodePoints =
        MAX_APK_BASE_NAME_CODE_POINTS - suffix.codePointCount()
    return "${prefix.takeCodePoints(maxPrefixCodePoints)}$suffix.apk"
}

private fun String.safeFileNameSegment(): String =
    replace(unsafeFileNameCharacters, "_")
        .trim('.', ' ')

private fun String.codePointCount(): Int = codePointCount(0, length)

private fun String.takeCodePoints(maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    if (codePointCount() <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}

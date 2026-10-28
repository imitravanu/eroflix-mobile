package tech.imitravanu.eroflix.model

import java.nio.ByteBuffer
import java.security.MessageDigest

@JvmInline value class TitleId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class SourceId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class GenerationId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class JobId(val value: String) { init { require(value.isNotBlank()) } }

/** Length-prefixed UTF-8 fields avoid ambiguous concatenation and preserve exact IDs/URLs. */
fun stableDigest(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sourceId(title: TitleId, provider: String, kind: StoredSourceKind, exactOriginalUrl: String) =
    SourceId(stableDigest(title.value, provider, kind.name, exactOriginalUrl))

enum class ErrorCode { INVALID_QUERY, CANCELED, DATABASE_BUSY, MISSING_CATALOG, CORRUPT_CATALOG,
    INCOMPATIBLE_CATALOG, STORAGE_FULL, MIGRATION_FAILED, NETWORK, EXPIRED, NOT_FOUND,
    UNSUPPORTED, AUTH_REQUIRED, PROVIDER_CHANGED, LOCAL_ONLY, EXPORT_FAILED }
data class AppError(val code: ErrorCode, val message: String, val retryable: Boolean = false)
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

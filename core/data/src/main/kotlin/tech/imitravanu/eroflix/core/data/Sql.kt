package tech.imitravanu.eroflix.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CancellationException
import tech.imitravanu.eroflix.model.*
import java.text.Normalizer
import java.util.Locale

internal fun SQLiteStatement.bind(values: List<Any?>) { values.forEachIndexed { i, v -> when(v) {
    null -> bindNull(i+1); is String -> bindText(i+1,v); is Boolean -> bindLong(i+1,if(v) 1 else 0)
    is Number -> bindLong(i+1,v.toLong()); else -> error("Unsupported SQL parameter")
} } }
internal fun SQLiteConnection.exec(sql: String, vararg values: Any?) { prepare(sql).use { it.bind(values.toList()); it.step() } }
internal fun <T> SQLiteConnection.rows(sql: String, values: List<Any?> = emptyList(), read: (SQLiteStatement)->T): List<T> = prepare(sql).use { s ->
    s.bind(values); buildList { while(s.step()) { add(read(s)) } }
}
internal fun SQLiteConnection.long(sql: String, values: List<Any?> = emptyList()): Long = rows(sql, values) { it.getLong(0) }.first()
internal fun SQLiteStatement.textOrNull(i: Int) = if(isNull(i)) null else getText(i)
internal fun SQLiteStatement.longOrNull(i: Int) = if(isNull(i)) null else getLong(i)
internal fun SQLiteStatement.summary(start: Int = 0) = TitleSummary(TitleId(getText(start)),getText(start+1),longOrNull(start+2)?.toInt(),longOrNull(start+3),textOrNull(start+4))
internal fun SQLiteStatement.personal(start: Int = 0) = PersonalState(getLong(start)!=0L,getLong(start+1)!=0L,getLong(start+2)!=0L,longOrNull(start+3),getLong(start+4),longOrNull(start+5),longOrNull(start+6))
internal fun fold(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT),Normalizer.Form.NFKD).replace(Regex("\\p{Mn}+"),"")
internal fun isCjk(cp: Int) = cp in 0x3400..0x9fff || cp in 0x3040..0x30ff || cp in 0xac00..0xd7af || cp in 0x20000..0x3134f
internal fun errorFor(e: Exception): AppError {
    if(e is CancellationException) throw e
    val m=e.message.orEmpty()
    return when {
        e is DataFailure -> e.error
        m.contains("full",true) || m.contains("ENOSPC",true) -> AppError(ErrorCode.STORAGE_FULL,"There is not enough storage for this operation.",true)
        m.contains("busy",true) || m.contains("locked",true) -> AppError(ErrorCode.DATABASE_BUSY,"Storage is busy. Try again.",true)
        else -> AppError(ErrorCode.CORRUPT_CATALOG,"Catalog storage could not be read. Retry or restore a validated catalog.",true)
    }
}
internal class DataFailure(val error: AppError): Exception(error.message)
internal suspend fun <T> outcome(block: suspend ()->T): Outcome<T> = try { Outcome.Success(block()) } catch(e: Exception) { Outcome.Failure(errorFor(e)) }
internal fun requireData(condition: Boolean, code: ErrorCode, message: String) { if(!condition) throw DataFailure(AppError(code,message,true)) }


/** Count little-endian membership bytes without allocating another bitmap per option. */
internal fun countMembership(bytes: ByteArray,visible: LongArray): Long {
    val input=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var index=0;var count=0L
    while(input.remaining()>=8 && index<visible.size) { count+=java.lang.Long.bitCount(input.long and visible[index]);index++ }
    if(input.hasRemaining() && index<visible.size) {
        var tail=0L;var shift=0
        while(input.hasRemaining() && shift<64) { tail=tail or ((input.get().toLong() and 255L) shl shift);shift+=8 }
        count+=java.lang.Long.bitCount(tail and visible[index])
    }
    return count
}

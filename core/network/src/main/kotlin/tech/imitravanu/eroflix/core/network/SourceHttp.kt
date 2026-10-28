package tech.imitravanu.eroflix.core.network

import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resumeWithException

class SourceHttp(private val base: OkHttpClient = createSourceHttpClient()) {
    /** Long media bodies are bounded by Media3 ownership/cancellation, not the resolver's call deadline. */
    fun mediaClient(policy: SourcePolicy): OkHttpClient = client(policy).newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.MILLISECONDS).build()
    fun client(policy: SourcePolicy): OkHttpClient = base.newBuilder()
        .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).addInterceptor(ScopedSourceInterceptor(policy)).build()
    suspend fun fetch(url: String,policy: SourcePolicy,maxBytes: Int = 8192,range: Boolean = true): ProbeResponse {
        require(maxBytes in 1..(1024*1024))
        val request=Request.Builder().url(url).header("Accept-Encoding","identity").apply { if(range) header("Range","bytes=0-${maxBytes-1}") }.build()
        for(attempt in 0..1) {
            val response=client(policy).newCall(request).probe(maxBytes)
            if(response.status !in setOf(429,500,502,503,504) || attempt==1) return response
            delay(150)
        }
        error("No source response")
    }
}
data class ProbeResponse(val url: String,val status: Int,val headers: Map<String,List<String>>,val bytes: ByteArray,val truncated: Boolean = false) {
    fun header(name: String)=headers[name.lowercase()]?.firstOrNull()
    val text get()=bytes.toString(Charsets.UTF_8)
}
private suspend fun Call.probe(maxBytes: Int): ProbeResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object: Callback {
        override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call,response: Response) {
            try {
                val result=response.use { r ->
                    val bytes=r.body?.byteStream()?.use { input ->
                        val out=java.io.ByteArrayOutputStream(minOf(maxBytes,8192));val buffer=ByteArray(minOf(maxBytes,8192));var left=maxBytes+1
                        while(left>0) {
                            if(!continuation.isActive) throw CancellationException("Source read canceled")
                            val n=input.read(buffer,0,minOf(left,buffer.size));if(n<0) break
                            out.write(buffer,0,n);left-=n
                        };out.toByteArray()
                    } ?: byteArrayOf()
                    ProbeResponse(r.request.url.toString(),r.code,r.headers.toMultimap().mapKeys { it.key.lowercase() },if(bytes.size>maxBytes) bytes.copyOf(maxBytes) else bytes,bytes.size>maxBytes)
                }
                if(continuation.isActive) continuation.resumeWith(Result.success(result))
            } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
        }
    })
}

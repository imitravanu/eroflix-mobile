package tech.imitravanu.eroflix.core.network
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
/** P03 validates redirect destinations before forwarding scoped credentials. */
fun createSourceHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply { maxRequests=8;maxRequestsPerHost=3 })
    .connectionPool(ConnectionPool(8,30,TimeUnit.SECONDS))
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

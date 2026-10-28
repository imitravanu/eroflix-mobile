package tech.imitravanu.eroflix.core.media

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import tech.imitravanu.eroflix.core.network.SourceHttp
import tech.imitravanu.eroflix.core.network.SourcePolicy
import tech.imitravanu.eroflix.model.Resolution

/** Same immutable source policy for manifest/variant/segment/init/key requests. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun scopedMediaDataSource(media: Resolution.NativeMedia,http: SourceHttp = SourceHttp(),fixtureLoopback: Boolean = false): DataSource.Factory {
    val hosts=media.requestScopes.flatMap { it.allowedHosts }.toSet()
    require(hosts.isNotEmpty())
    val policy=SourcePolicy(hosts,media.requestScopes,fixtureLoopback)
    return OkHttpDataSource.Factory(http.mediaClient(policy))
}

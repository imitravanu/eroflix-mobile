package tech.imitravanu.eroflix.core.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.*
import org.junit.Assert.*
import tech.imitravanu.eroflix.model.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ResolverTest {
 private lateinit var server: HttpServer
 private lateinit var workers: java.util.concurrent.ExecutorService
 private lateinit var http: SourceHttp
 private val calls=AtomicInteger()
 private val requests=java.util.Collections.synchronizedList(mutableListOf<Pair<String,Map<String,String>>>())
 @Volatile private var action: (String)->Pair<Int,ByteArray> = { 404 to byteArrayOf() }
 private lateinit var origin: String
 private val mp4=byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()+ByteArray(64)
 @Before fun setup() {
  server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);workers=Executors.newFixedThreadPool(4);server.executor=workers
  server.createContext("/") { ex ->
   calls.incrementAndGet();requests+=ex.requestURI.path to ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }
   try {
    val (status,body)=action(ex.requestURI.path)
    if(status in listOf(301,302,307)) ex.responseHeaders.add("Location",body.toString(Charsets.UTF_8))
    ex.responseHeaders.add("ETag","\"fixture-v1\"");ex.responseHeaders.add("Accept-Ranges","bytes")
    ex.responseHeaders.add("Content-Type",if(ex.requestURI.path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream")
    ex.sendResponseHeaders(status,body.size.toLong());ex.responseBody.use { it.write(body) }
   } catch(_: Exception) { ex.close() }
  }
  server.start();origin="http://127.0.0.1:${server.address.port}"
  http=SourceHttp(createSourceHttpClient().newBuilder().callTimeout(3,java.util.concurrent.TimeUnit.SECONDS).build())
 }
 @After fun close() { server.stop(0);workers.shutdownNow() }
 private fun policy(scopes: List<RequestScope> = emptyList())=SourcePolicy(setOf("127.0.0.1"),scopes,true)
 private fun handle(path: String="/video.mp4",kind: StoredSourceKind=StoredSourceKind.FILE)=SourceHandle(TitleId("neutral"),SourceId("source"),"fixture",origin+path,kind)
 private fun resolver(p: SourcePolicy=policy())=CatalogSourceResolver(http,{p})
 @Test fun progressiveRequiresBytesAndRetainsExactSignedUrl()=runBlocking {
  action={200 to mp4};val h=handle("/video.mp4?token=exact%2Bvalue")
  val r=resolver().resolve(h,ResolvePurpose.DOWNLOAD) as Resolution.NativeMedia
  assertEquals(h.originalUrl,r.uri);assertEquals("video/mp4",r.mimeType);assertTrue(r.capabilities.downloadable);assertFalse(r.capabilities.exportable)
  assertEquals("\"fixture-v1\"",r.validators?.etag);assertEquals(mp4.size.toLong(),r.validators?.lengthBytes)
  assertEquals(1,calls.get())
  assertEquals("bytes=0-8191",requests.single().second["range"])
 }
 @Test fun htmlIsNeverPromotedByExtensionOrContentType()=runBlocking {
  action={200 to "<!doctype html><html>Login</html>".toByteArray()}
  val r=resolver().resolve(handle(),ResolvePurpose.PLAYBACK) as Resolution.Unavailable
  assertEquals(ErrorCode.PROVIDER_CHANGED,r.error.code)
 }
 @Test fun redirectsReapplyOnlyThisSourcesScopedCredentials()=runBlocking {
  action={path -> if(path=="/start") 302 to "/video.mp4".toByteArray() else 200 to mp4}
  val p=policy(listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://example.invalid/"),mapOf("session" to "one"))))
  val r=resolver(p).resolve(handle("/start"),ResolvePurpose.PLAYBACK);assertTrue(r is Resolution.NativeMedia)
  assertEquals(2,requests.size);requests.forEach { assertEquals("session=one",it.second["cookie"]);assertEquals("https://example.invalid/",it.second["referer"]) }
 }
 @Test fun crossHostAndSchemeRedirectsAreBlockedBeforeRequest()=runBlocking {
  action={302 to "https://other.invalid/private".toByteArray()}
  val r=resolver().resolve(handle(),ResolvePurpose.PLAYBACK) as Resolution.Unavailable
  assertEquals(ErrorCode.PROVIDER_CHANGED,r.error.code);assertEquals(1,calls.get())
 }
 @Test fun callerAuthorizationCannotLeakIntoRedirectOrAnotherJob()=runBlocking {
  action={200 to mp4}
  http.client(policy()).newCall(Request.Builder().url(origin+"/video.mp4").header("Authorization","Bearer unsafe").header("Cookie","unscoped=bad").build()).execute().close()
  assertNull(requests.single().second["authorization"]);assertNull(requests.single().second["cookie"])
  coroutineScope { listOf("first","second").map { secret -> async {
   val p=policy(listOf(RequestScope(setOf("127.0.0.1"),cookies=mapOf("job" to secret))))
   resolver(p).resolve(handle("/$secret.mp4"),ResolvePurpose.DOWNLOAD)
  } }.awaitAll() }
  assertEquals("job=first",requests.first { it.first=="/first.mp4" }.second["cookie"])
  assertEquals("job=second",requests.first { it.first=="/second.mp4" }.second["cookie"])
 }
 @Test fun hlsManifestAndVariantReceiveSameHeadersAndExposeOnlyRealTracks()=runBlocking {
  action={path -> 200 to if(path=="/master.m3u8") "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=320x180\nchild.m3u8\n".toByteArray() else "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\nseg.m4s\n#EXT-X-ENDLIST\n".toByteArray() }
  val p=policy(listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))))
  val r=resolver(p).resolve(handle("/master.m3u8",StoredSourceKind.HLS),ResolvePurpose.DOWNLOAD) as Resolution.NativeMedia
  assertEquals(180,r.tracks.single().height);assertEquals(2,calls.get());assertTrue(requests.all { it.second["referer"]=="https://fixture.invalid/" })
  // Later segment/init/key reads use exactly this call factory; no global defaults.
  action={200 to ByteArray(16)}
  for(path in listOf("/seg.m4s","/init.mp4","/key.bin")) http.fetch(origin+path,p)
  assertTrue(requests.takeLast(3).all { it.second["referer"]=="https://fixture.invalid/" })
 }
 @Test fun hlsRejectsDrmOutOfScopeResourcesAndUnboundedManifests()=runBlocking {
  for(text in listOf("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\",URI=\"skd://key\"\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST", "#EXTM3U\n#EXTINF:1,\nhttps://other.invalid/seg.ts\n#EXT-X-ENDLIST")) {
   action={200 to text.toByteArray()};assertTrue(resolver().resolve(handle("/x.m3u8",StoredSourceKind.HLS),ResolvePurpose.DOWNLOAD) is Resolution.Unavailable)
  }
 }
 @Test fun livePlaybackDoesNotBecomeFiniteDownload()=runBlocking {
  action={200 to "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nseg.ts\n".toByteArray()}
  val r=resolver().resolve(handle("/live.m3u8",StoredSourceKind.HLS),ResolvePurpose.PLAYBACK) as Resolution.NativeMedia
  assertFalse(r.capabilities.downloadable);assertTrue(resolver().resolve(handle("/live.m3u8",StoredSourceKind.HLS),ResolvePurpose.DOWNLOAD) is Resolution.Unavailable)
 }
 @Test fun retriesAreBoundedAndAuthNotFoundExpiryDiffer()=runBlocking {
  action={503 to "Unavailable".toByteArray()};val r=resolver().resolve(handle(),ResolvePurpose.PLAYBACK) as Resolution.Unavailable
  assertEquals(ErrorCode.NETWORK,r.error.code);assertEquals(2,calls.get())
  for((status,code) in listOf(401 to ErrorCode.AUTH_REQUIRED,403 to ErrorCode.EXPIRED,404 to ErrorCode.NOT_FOUND)) {
   action={status to byteArrayOf(1)};assertEquals(code,(resolver().resolve(handle(),ResolvePurpose.PLAYBACK) as Resolution.Unavailable).error.code)
  }
 }
 @Test fun cancellationStopsPendingCallAndDoesNotBecomeNetworkFailure()=runBlocking {
  action={Thread.sleep(2000);200 to mp4}
  val task=async { resolver().resolve(handle(),ResolvePurpose.PLAYBACK) };delay(100);task.cancel()
  val start=System.nanoTime();try { task.await();fail("Expected cancellation") } catch(_: CancellationException) { }
  assertTrue((System.nanoTime()-start)/1_000_000<1000)
 }
 @Test fun sourceExpiryAndResourceResumeNeverDependOnUrlAlone() {
  assertEquals(1700000000000L,expiryMs("https://example.invalid/a?exp=1700000000"))
  assertEquals(1700000000123L,expiryMs("https://example.invalid/a?expires=1700000000123"))
  val v=ResourceValidators("\"strong\"",null,100,true)
  assertTrue(canResumeResource(v,v));assertFalse(canResumeResource(v,v.copy(etag="\"different\"")))
  assertFalse(canResumeResource(v.copy(etag="W/\"weak\""),v.copy(etag="W/\"weak\"")))
  assertFalse(canResumeResource(v,v.copy(lengthBytes=101)));assertFalse(canResumeResource(ResourceValidators(),ResourceValidators()))
 }
 @Test fun productionPolicyUsesExactHttpsHostsAndNoCredentialUrls() {
  val p=SourcePolicy(setOf("media.example.invalid"))
  assertTrue(p.accepts("https://media.example.invalid/a"));assertFalse(p.accepts("https://media.example.invalid.evil.invalid/a"))
  assertFalse(p.accepts("http://media.example.invalid/a"));assertFalse(p.accepts("https://user:pass@media.example.invalid/a"));assertFalse(p.accepts("https://media.example.invalid:444/a"))
 }
 @Test fun embedFallbackIsPurposeSpecificAndNeverFakeNative()=runBlocking {
  action={200 to "<html><video data-provider='public'></video></html>".toByteArray()}
  val r=CatalogSourceResolver(http,{policy()},embedFamily={"fixture"})
  assertTrue(r.resolve(handle("/embed",StoredSourceKind.EMBED),ResolvePurpose.PLAYBACK) is Resolution.WebEmbed)
  assertEquals(ErrorCode.UNSUPPORTED,(r.resolve(handle("/embed",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) as Resolution.Unavailable).error.code)
  assertEquals(listOf("/video.mp4?x=a&y=b"),PublicSourceTags.extract("<source type='video/mp4' src=\"/video.mp4?x=a&amp;y=b\">"))
 }
 @Test fun webPolicyBlocksPopupsExternalSchemesAndOtherPaths() {
  val p=WebSourcePolicy("https://player.example.invalid/embed/123","Fixture",setOf("player.example.invalid","cdn.example.invalid"))
  assertEquals(mapOf("Referer" to "https://player.example.invalid/"),p.initialRequestHeaders)
  assertTrue(p.topLevel("https://player.example.invalid/embed/123#play"));assertFalse(p.topLevel("https://player.example.invalid/login"))
  for(url in listOf("intent://evil","file:///etc/hosts","content://secret","javascript:alert(1)","https://other.invalid/","http://player.example.invalid/embed/123")) assertFalse(p.resource(url))
  assertTrue(p.resource("https://cdn.example.invalid/player.js"))
 }
 @Test fun compiledProviderPoliciesCoverFiveFamiliesAndExactProtectedHosts() {
  assertTrue(ProviderPolicies.embedFamilies.size >= 5)
  for((family,hosts) in ProviderPolicies.embedFamilies) for(host in hosts) {
   assertEquals(family,ProviderPolicies.family(host));val source=SourceHandle(TitleId("neutral"),SourceId("source"),family,"https://$host/embed/neutral",StoredSourceKind.EMBED)
   assertTrue(ProviderPolicies.forSource(source)!!.accepts(source.originalUrl))
  }
  assertNull(ProviderPolicies.family("eporner.com.evil.invalid"))
  for(host in ProviderPolicies.protectedHosts) {
   val source=SourceHandle(TitleId("neutral"),SourceId("source"),"fixture","https://$host/neutral.mp4",StoredSourceKind.FILE)
   val p=ProviderPolicies.forSource(source)!!;assertEquals("https://erogarga.com/",p.scopes.single().headers["Referer"])
  }
 }
 @Test fun expiredHandleStopsBeforeNetworkAndDateOnlyCannotResume()=runBlocking {
  val r=CatalogSourceResolver(http,{policy()},clockMs={2_000_000L}).resolve(handle("/video.mp4?exp=1"),ResolvePurpose.DOWNLOAD) as Resolution.Unavailable
  assertEquals(ErrorCode.EXPIRED,r.error.code);assertEquals(0,calls.get())
  val v=ResourceValidators(null,"Wed, 21 Oct 2026 00:00:00 GMT",100,true);assertFalse(canResumeResource(v,v))
 }
 @Test fun inconsistentRangesAndRedirectLoopsFailExplicitly()=runBlocking {
  action={206 to mp4};assertEquals(ErrorCode.PROVIDER_CHANGED,(resolver().resolve(handle(),ResolvePurpose.PLAYBACK) as Resolution.Unavailable).error.code)
  calls.set(0);action={302 to "/loop".toByteArray()}
  assertTrue(resolver().resolve(handle("/loop"),ResolvePurpose.PLAYBACK) is Resolution.Unavailable);assertEquals(5,calls.get())
 }
 @Test fun sourceMarkupReadsActualAttributesAndIgnoresCodeCommentsAndLazyPlaceholders() {
  val html="""<!-- <source src='/comment.mp4'> --><script>const text="<source src='/script.mp4'>";</script><style>/* <video src='/style.mp4'> */</style><video data-src='/lazy.mp4' src='/real.mp4?a=1&#38;b=2'></video><source SRC=/other.mp4><source data-src='/not-src.mp4'>"""
  assertEquals(listOf("/real.mp4?a=1&b=2","/other.mp4"),PublicSourceTags.extract(html))
  assertEquals(emptyList<String>(),PublicSourceTags.extract("<video data-long='"+"x".repeat(10000)+"' src='/oversized.mp4'>"))
  assertEquals(16,PublicSourceTags.extract("<source src='/a.mp4'>".repeat(1000)).size)
  assertEquals(listOf("/actual.mp4"),PublicSourceTags.extract("İ<source-set src='/custom-tag.mp4'></source-set><SCRIPT>let s='İ<video src=\"/code.mp4\">';</SCRIPT><VIDEO SRC='/actual.mp4'>"))
 }
 @Test fun staticPlayerConfigPreservesSignedValuesAndRejectsDynamicOrPreviewProviders() {
  val html="""<script>var flashvars={video_id:'neutral',video_url:'https:\/\/media.example.invalid\/full.mp4?a=1\u0026b=2',video_alt_url:'/mirror.mp4',preview_url:'/preview.mp4',autoplay:0};kt_player('player',flashvars);</script>"""
  assertEquals(listOf("https://media.example.invalid/full.mp4?a=1&b=2","/mirror.mp4"),PublicPlayerConfig.extract("fullerotic",html))
  assertTrue(PublicPlayerConfig.extract("porntrex",html).isEmpty())
  assertTrue(PublicPlayerConfig.extract("fullerotic",html.replace("kt_player(","generate_mp4('opaque');kt_player(")).isEmpty())
  assertTrue(PublicPlayerConfig.extract("fullerotic",html.replace("video_alt_url:'/mirror.mp4'","video_url:computeMedia()")).isEmpty())
  assertTrue(PublicPlayerConfig.extract("fullerotic",html.replace("video_alt_url:'/mirror.mp4'","video_url:'/ambiguous.mp4'")).isEmpty())
  assertTrue(PublicPlayerConfig.extract("fullerotic","<script>const example=\"var flashvars={video_url:'/not-real.mp4'};kt_player('sample');\";</script>").isEmpty())
  assertTrue(PublicPlayerConfig.extract("fullerotic","<script>/* var flashvars={video_url:'/not-real.mp4'}; */ kt_player('sample');</script>").isEmpty())
 }
 @Test fun clearProviderConfigTriesBoundedAlternativesAndUsesFinalPageOrigin()=runBlocking {
  action={path -> 200 to when(path) {
   "/embed" -> "<script>var flashvars={video_url:'/bad.mp4',video_alt_url:'/full.mp4'};kt_player('player',flashvars);</script>".toByteArray()
   "/bad.mp4" -> "<html>Not media</html>".toByteArray()
   else -> mp4
  } }
  val r=CatalogSourceResolver(http,{policy()},embedFamily={"fullerotic"}).resolve(handle("/embed",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) as Resolution.NativeMedia
  assertEquals(origin+"/full.mp4",r.uri);assertEquals(3,calls.get())
  assertEquals(origin+"/",requests.last().second["referer"])
 }
 @Test fun porntrexPreviewSourceNeverBecomesFullNativePlaybackOrDownload()=runBlocking {
  action={200 to "<html><video src='/preview.mp4'></video></html>".toByteArray()}
  val r=CatalogSourceResolver(http,{policy()},embedFamily={"porntrex"})
  assertTrue(r.resolve(handle("/embed",StoredSourceKind.EMBED),ResolvePurpose.PLAYBACK) is Resolution.WebEmbed)
  assertTrue(r.resolve(handle("/embed",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) is Resolution.Unavailable)
  assertEquals(2,calls.get());assertTrue(requests.none { it.first=="/preview.mp4" })
 }
 @Test fun largeHlsIsRefetchedWithinManifestBoundsInsteadOfReadingHalfMiBForEveryFile()=runBlocking {
  val manifest=("#EXTM3U\n"+"# comment padding padding padding\n".repeat(400)+"#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n").toByteArray()
  action={200 to manifest}
  assertTrue(resolver().resolve(handle("/large.m3u8",StoredSourceKind.HLS),ResolvePurpose.DOWNLOAD) is Resolution.NativeMedia)
  assertEquals(2,calls.get());assertEquals("bytes=0-8191",requests[0].second["range"]);assertNull(requests[1].second["range"])
 }
 @Test fun manifestChangingToHtmlDuringRefetchIsNeverPromoted()=runBlocking {
  action={200 to if(calls.get()==1) ("#EXTM3U\n"+"# padding\n".repeat(1000)).toByteArray() else "<html>Changed</html>".toByteArray()}
  assertEquals(ErrorCode.PROVIDER_CHANGED,(resolver().resolve(handle("/changed.m3u8",StoredSourceKind.HLS),ResolvePurpose.PLAYBACK) as Resolution.Unavailable).error.code)
 }
 @Test fun measuredProviderResourcesAreAllowedWithoutBecomingNavigationTargets() {
  val tizam=WebSourcePolicy.forEntry("https://abv.tizam.org/fil_my_dlya_vzroslyh/neutral.html")!!
  assertTrue(tizam.topLevel("https://adv.tizam.org/fil_my_dlya_vzroslyh/neutral.html"))
  for(host in listOf("video1.tizam.cc","video2.tizam.cc","video4.tizam.cc")) assertTrue(tizam.resource("https://$host/neutral.mp4"))
  assertFalse(tizam.topLevel("https://video2.tizam.cc/fil_my_dlya_vzroslyh/neutral.html"))
  val eporner=WebSourcePolicy.forEntry("https://www.eporner.com/embed/neutral/?token=private#seek")!!
  for(host in listOf("i.eporner.com","static-eu-cdn.eporner.com")) {
   assertTrue(eporner.resource("https://$host/player.js"));assertFalse(eporner.topLevel("https://$host/embed/neutral/?token=private"))
  }
  assertFalse(eporner.resource("https://i.eporner.com.evil.invalid/player.js"));assertEquals(mapOf("Referer" to "https://www.eporner.com/"),eporner.initialRequestHeaders)
  assertEquals("#EPvideo",eporner.playerContainerSelector)
 }
 @Test fun tubeTransportEncodingUsesPublishedAlphabetAndPreservesLiteralPlus() {
  assertEquals("/full.mp4?x=a+b+c",TubeUrlEncoding.decode("L2Z1bGwubXА0P3g9YStiJTJСYw~~"))
  assertEquals("https://media.example.invalid/full.mp4?x=one two",TubeUrlEncoding.decode("aHR0cHМ6Ly9tZWRpYS5leGFtcGxlLmludmFsaWQvZnVsbС5tcDQ,eD1vbmUlМjВ0d28~"))
  for(value in listOf("A","aaaa:bbb","A".repeat(8196),"L2Z1bGwubXА0P3g9YStiJTJСYw~~!")) assertNull(TubeUrlEncoding.decode(value))
 }
 @Test fun tubePublicMetadataResolvesSelectedMediaWithoutExecutingPageScripts()=runBlocking {
  action={path -> 200 to when {
   path.startsWith("/api/json/video/") -> """{"video":{"video_id":"42","status_id":"1","is_private":"0","duration":"00:30"}}""".toByteArray()
   path=="/api/videofile.php" -> """[{"format":".mp4","video_url":"L3ZpZGVvLm1wND90b2tlbj1hJTJСYg~~"}]""".toByteArray()
   else -> mp4
  } }
  val p=policy(listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))))
  val r=CatalogSourceResolver(http,{p},embedFamily={"tubepornclassic"}).resolve(handle("/embed/42/",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) as Resolution.NativeMedia
  assertEquals(origin+"/video.mp4?token=a+b",r.uri);assertEquals(3,calls.get());assertEquals("/api/json/video/86400/0/0/42.json",requests[0].first)
  assertTrue(requests.all { it.second["referer"]=="https://fixture.invalid/" });assertTrue(requests.none { it.first=="/embed/42/" })
 }
 @Test fun tubePrivateDeletedAndMismatchedIdentitiesNeverRequestMedia()=runBlocking {
  val resolver=CatalogSourceResolver(http,{policy()},embedFamily={"tubepornclassic"})
  for((id,status,private,code) in listOf(listOf("99","1","0",ErrorCode.PROVIDER_CHANGED),listOf("42","5","0",ErrorCode.NOT_FOUND),listOf("42","1","1",ErrorCode.AUTH_REQUIRED))) {
   calls.set(0);action={200 to """{"video":{"video_id":"$id","status_id":"$status","is_private":"$private"}}""".toByteArray()}
   val result=resolver.resolve(handle("/embed/42/",StoredSourceKind.EMBED),ResolvePurpose.PLAYBACK) as Resolution.Unavailable
   assertEquals(code,result.error.code);assertEquals(1,calls.get())
  }
 }
 @Test fun tubeLongPublicVideoOffersTheProvidersHlsRoute()=runBlocking {
  action={path -> 200 to when {
   path.startsWith("/api/json/video/") -> """{"video":{"video_id":"42","status_id":"1","is_private":"0","duration":"01:00:00"}}""".toByteArray()
   path=="/api/videofile.php" -> """[{"video_url":"L3ZpZGVvLm1wND90b2tlbj1hJTJСYg~~"}]""".toByteArray()
   else -> "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
  } }
  val result=CatalogSourceResolver(http,{policy()},embedFamily={"tubepornclassic"}).resolve(handle("/embed/42/",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) as Resolution.NativeMedia
  assertEquals("application/x-mpegURL",result.mimeType);assertTrue(result.uri.endsWith("&f=video.m3u8"));assertEquals(3,calls.get())
 }
 @Test fun tubeUnknownEncodingAndNonNumericIdentityFailBoundedly()=runBlocking {
  val resolver=CatalogSourceResolver(http,{policy()},embedFamily={"tubepornclassic"})
  val invalid=resolver.resolve(handle("/embed/not-numeric/",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) as Resolution.Unavailable
  assertEquals(ErrorCode.PROVIDER_CHANGED,invalid.error.code);assertEquals(0,calls.get())
  action={path -> 200 to if(path.startsWith("/api/json/video/")) """{"video":{"video_id":"42","status_id":"1","is_private":"0"}}""".toByteArray() else """[{"video_url":"unsupported:opaque"}]""".toByteArray() }
  assertEquals(ErrorCode.UNSUPPORTED,(resolver.resolve(handle("/embed/42/",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) as Resolution.Unavailable).error.code);assertEquals(2,calls.get())
 }
 @Test fun epornerRotatingMediaNodesAreResourceOnlyHttpsWithoutCredentials() {
  val policy=WebSourcePolicy.forEntry("https://www.eporner.com/embed/neutral/")!!
  for(host in listOf("dash-s19-s50-fr-cdn.eporner.com","vid-s7-n50-fr-cdn.eporner.com","vid-s99-s50-fr-cdn.eporner.com")) {
   assertTrue(policy.resource("https://$host/full.mpd"));assertFalse(policy.topLevel("https://$host/embed/neutral/"))
   assertFalse(policy.resource("http://$host/full.mpd"));assertFalse(policy.resource("https://$host:444/full.mpd"));assertFalse(policy.resource("https://user:pass@$host/full.mpd"))
  }
  for(host in listOf("dash-s0-s50-fr-cdn.eporner.com","dash-s100-s50-fr-cdn.eporner.com","dash-s19-s50-fr-cdn.eporner.com.evil.invalid","unrelated.eporner.com")) assertFalse(policy.resource("https://$host/full.mpd"))
  assertFalse(WebSourcePolicy.forEntry("https://www.porntrex.com/embed/neutral/")!!.resource("https://dash-s19-s50-fr-cdn.eporner.com/full.mpd"))
 }
 @Test fun sourcePolicyDiagnosticsReturnHostnameWithoutUrlOrToken()=runBlocking {
  action={302 to "https://new.example.invalid/private?token=neutral".toByteArray()};val seen=mutableListOf<String>()
  val result=CatalogSourceResolver(http,{policy()},onBlockedHost={seen+=it}).resolve(handle(),ResolvePurpose.PLAYBACK) as Resolution.Unavailable
  assertEquals(ErrorCode.PROVIDER_CHANGED,result.error.code);assertEquals(listOf("new.example.invalid"),seen);assertFalse(result.error.message.contains("token"))
 }
 @Test fun unprovenDynamicPlayersFallBackToContainedWebPlayerForPlayback()=runBlocking {
  action={200 to "<script>generate_mp4('opaque');</script>".toByteArray()}
  for(family in listOf("fullerotic","porntrex")) {
   val playback=CatalogSourceResolver(http,{policy()},embedFamily={family}).resolve(handle("/embed",StoredSourceKind.EMBED),ResolvePurpose.PLAYBACK)
   assertTrue(playback is Resolution.WebEmbed)
   val download=CatalogSourceResolver(http,{policy()},embedFamily={family}).resolve(handle("/embed",StoredSourceKind.EMBED),ResolvePurpose.DOWNLOAD) as Resolution.Unavailable
   assertEquals(ErrorCode.UNSUPPORTED,download.error.code);assertFalse(download.error.retryable)
  }
 }
 @Test fun longMediaReadOutlivesProbeDeadlineWhileRetainingIdleTimeoutAndHeaders() {
  action={Thread.sleep(300);200 to mp4}
  val transport=SourceHttp(createSourceHttpClient().newBuilder().callTimeout(100,java.util.concurrent.TimeUnit.MILLISECONDS).build())
  val p=policy(listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))))
  val request=Request.Builder().url(origin+"/video.mp4").build()
  try { transport.client(p).newCall(request).execute().use { it.body?.bytes() };fail("The resolver deadline must remain bounded") } catch(_: java.io.IOException) { }
  val media=transport.mediaClient(p)
  assertEquals(0,media.callTimeoutMillis);assertEquals(15000,media.readTimeoutMillis);assertEquals(10000,media.connectTimeoutMillis)
  media.newCall(request).execute().use { assertArrayEquals(mp4,it.body!!.bytes()) }
  assertEquals("https://fixture.invalid/",requests.last().second["referer"])
 }
}

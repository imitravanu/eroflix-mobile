package tech.imitravanu.eroflix

import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections

/** Phone-local neutral test server. Its failure switch never changes phone radios. */
internal class LabFixtureServer(private val root: File): AutoCloseable {
 private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
 private val workers=Executors.newFixedThreadPool(4)
 private val open=AtomicBoolean(true)
 private val clients=Collections.synchronizedSet(mutableSetOf<Socket>())
 val observed=Collections.synchronizedList(mutableListOf<Pair<String,Boolean>>())
 @Volatile var unavailable=false
 @Volatile var slow=false
 val origin="http://127.0.0.1:${socket.localPort}"
 init { Thread({ while(open.get()) { try { val client=socket.accept();clients+=client;workers.submit { serve(client) } } catch(_: Exception) { if(!open.get()) break } } },"SourceLabFixture").apply { isDaemon=true;start() } }
 private fun serve(client: Socket) {
  try { client.use { s ->
   s.soTimeout=5000
   val input=s.getInputStream().bufferedReader();val request=input.readLine()?.split(' ') ?: return
   if(request.size<2 || request[0]!="GET") return
   val headers=mutableMapOf<String,String>();var count=0
   while(true) { val line=input.readLine() ?: return;if(line.isEmpty()) break;if(++count>64 || line.length>8192) return;val index=line.indexOf(':');if(index>0) headers[line.substring(0,index).lowercase()]=line.substring(index+1).trim() }
   val path=java.net.URLDecoder.decode(request[1].substringBefore('?'),"UTF-8").removePrefix("/")
   val allowed=headers["referer"]=="https://fixture.invalid/";observed+=path to allowed
   val file=File(root,path).canonicalFile
   val status=when { !allowed -> 403;unavailable -> 503;!file.path.startsWith(root.canonicalPath+File.separator) || !file.isFile -> 404;else -> 200 }
   val output=s.getOutputStream()
   if(status!=200) { output.write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return }
   val length=file.length();val range=headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
   val start=range?.groupValues?.get(1)?.toLongOrNull() ?: 0L;val end=minOf(range?.groupValues?.get(2)?.toLongOrNull() ?: length-1,length-1)
   if(start<0 || start>=length || end<start) { output.write("HTTP/1.1 416 Range\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return }
   val mime=when(file.extension) { "m3u8" -> "application/vnd.apple.mpegurl";"mp4","m4s" -> "video/mp4";"ts" -> "video/mp2t";else -> "application/octet-stream" }
   val extra=if(range!=null) "Content-Range: bytes $start-$end/$length\r\n" else ""
   output.write("HTTP/1.1 ${if(range!=null) "206 Partial Content" else "200 OK"}\r\nContent-Type: $mime\r\nContent-Length: ${end-start+1}\r\nAccept-Ranges: bytes\r\nETag: \"neutral-${file.name}-$length\"\r\n${extra}Connection: close\r\n\r\n".toByteArray())
   java.io.RandomAccessFile(file,"r").use { f -> f.seek(start);var left=end-start+1;val buffer=ByteArray(1024)
    while(left>0 && open.get() && !unavailable) { val n=f.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());if(n<0) break;output.write(buffer,0,n);output.flush();left-=n;if(slow && file.extension in setOf("ts","m4s","mp4")) Thread.sleep(25) }
   }
  } } catch(_: Exception) { } finally { clients-=client }
 }
 fun interruptNetwork() { unavailable=true;synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } } }
 override fun close() { open.set(false);socket.close();synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } };workers.shutdownNow() }
}

package tech.imitravanu.eroflix

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** Isolated P07 feasibility spike: clear H264/AAC only, no transcode/DRM/subtitles. */
internal suspend fun sampleCopySpike(input: File,output: File): Map<String,Any> {
 val extractor=MediaExtractor();var muxer: MediaMuxer?=null;var started=false
 try {
  extractor.setDataSource(input.absolutePath)
  require(extractor.trackCount in 1..2) { "Platform extractor must expose one or two tracks" }
  val formats=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
  require(formats.all { it.getString(MediaFormat.KEY_MIME) in setOf("video/avc","audio/mp4a-latm") }) { "Spike supports clear H264/AAC tracks only" }
  val writer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=writer
  val mapping=formats.mapIndexed { index,format -> extractor.selectTrack(index);writer.addTrack(format) }
  writer.start();started=true
  val buffer=ByteBuffer.allocate(2*1024*1024);val info=MediaCodec.BufferInfo();val last=LongArray(formats.size) {-1L};var samples=0;var reordered=0
  while(true) {
   currentCoroutineContext().ensureActive();buffer.clear();val n=extractor.readSampleData(buffer,0);if(n<0) break
   val track=extractor.sampleTrackIndex;val time=extractor.sampleTime
   require(n<=buffer.capacity()) { "Sample exceeds 2 MiB spike buffer" }
   require(time>=0) { "Negative presentation times need a dedicated export path" }
   if(formats[track].getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm")
    require(time>last[track]) { "Platform extractor emitted non-increasing AAC timestamps; dedicated TS export required" }
   // Preserve extractor order. MP4 muxing supports B-frames; PTS need not be sorted.
   if(time<last[track]) reordered++
   last[track]=time
   require(extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)==0)
   val flags=if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
   info.set(0,n,time,flags);writer.writeSampleData(mapping[track],buffer,info);samples++
   extractor.advance()
  }
  writer.stop();started=false;writer.release();muxer=null
  val check=MediaExtractor()
  try { check.setDataSource(output.absolutePath);require(check.trackCount==formats.size);check.selectTrack(0);check.seekTo(1_000_000,MediaExtractor.SEEK_TO_CLOSEST_SYNC);require(check.readSampleData(buffer,0)>0) } finally { check.release() }
  return mapOf("supported" to true,"samples" to samples,"tracks" to formats.size,"outputBytes" to output.length(),"seekRead" to true,"reorderedPresentationSamples" to reordered)
 } catch(e: Exception) { output.delete();throw e }
 finally { extractor.release();if(started) runCatching { muxer?.stop() };muxer?.release() }
}

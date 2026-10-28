package tech.imitravanu.eroflix.core.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class GainProcessor : BaseAudioProcessor() {
    @Volatile var processedSamples=0L
        private set
    @Volatile var gain=1f
        set(value) { field=value.coerceIn(1f,2f) }
    private var scratch=ByteArray(0)
    private var lastOutput: ByteBuffer?=null
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if(inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val size=inputBuffer.remaining()
        // AudioProcessingPipeline polls with the shared EMPTY_BUFFER. Never make it our output.
        if(size==0) return
        require(size%2==0) { "PCM16 input must contain complete samples." }
        if(scratch.size<size) scratch=ByteArray(size)
        // Consume before replaceOutputBuffer clears/reuses storage. Snapshot preserves partial limits.
        inputBuffer.get(scratch,0,size)
        val alias=inputBuffer===lastOutput
        val output=replaceOutputBuffer(if(alias) maxOf(size,inputBuffer.capacity()+1) else size)
        lastOutput=output
        processedSamples+=size/2
        val level=gain
        if(level==1f) output.put(scratch,0,size)
        else {
            val input=ByteBuffer.wrap(scratch,0,size).order(ByteOrder.nativeOrder())
            while(input.remaining()>=2) {
                val sample=input.short.toInt()
                output.putShort((gainedSample(sample/32768f,level)*32767).toInt().coerceIn(-32768,32767).toShort())
            }
        }
        output.flip()
    }
    override fun onReset() { scratch=ByteArray(0);lastOutput=null;processedSamples=0 }
}

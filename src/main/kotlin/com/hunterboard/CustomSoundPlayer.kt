package com.hunterboard

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.util.Util
import org.lwjgl.stb.STBVorbis
import org.lwjgl.stb.STBVorbisInfo
import org.lwjgl.system.MemoryUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ShortBuffer
import java.nio.file.Path
import javax.sound.sampled.*

object CustomSoundPlayer {

    val soundDir: Path = FabricLoader.getInstance().configDir.resolve("hunterboard/sounds")

    private var currentClip: Clip? = null

    fun ensureDirectory() {
        soundDir.toFile().mkdirs()
    }

    fun listSounds(): List<String> {
        ensureDirectory()
        return soundDir.toFile().listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("wav", "ogg") }
            ?.map { it.name }
            ?.sorted() ?: emptyList()
    }

    fun play(filename: String, volume: Float, pitch: Float) {
        stop()
        val file = soundDir.resolve(filename).toFile()
        if (!file.exists()) return
        try {
            if (filename.endsWith(".ogg", ignoreCase = true)) {
                playOgg(file, volume)
            } else {
                playWav(file, volume)
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            currentClip?.let {
                if (it.isRunning) it.stop()
                it.close()
            }
        } catch (_: Exception) {}
        currentClip = null
    }

    fun openDirectory() {
        ensureDirectory()
        Util.getOperatingSystem().open(soundDir.toFile())
    }

    fun formatSoundName(filename: String): String {
        return filename.substringBeforeLast(".")
            .replace("_", " ").replace("-", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun playWav(file: File, volume: Float) {
        val audioStream = AudioSystem.getAudioInputStream(file)
        val clip = AudioSystem.getClip()
        clip.open(audioStream)
        setVolume(clip, volume)
        clip.addLineListener { e -> if (e.type == LineEvent.Type.STOP) clip.close() }
        currentClip = clip
        clip.start()
    }

    private fun playOgg(file: File, volume: Float) {
        val bytes = file.readBytes()
        val nativeBuf = MemoryUtil.memAlloc(bytes.size)
        var pcmBuf: ShortBuffer? = null
        try {
            nativeBuf.put(bytes).flip()
            val error = IntArray(1)
            val decoder = STBVorbis.stb_vorbis_open_memory(nativeBuf, error, null)
            if (decoder == 0L) return

            val info = STBVorbisInfo.malloc()
            try {
                STBVorbis.stb_vorbis_get_info(decoder, info)
                val channels = info.channels()
                val sampleRate = info.sample_rate()
                val totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(decoder)

                // Must use native (direct) buffer for LWJGL/STBVorbis
                val totalShorts = totalSamples * channels
                pcmBuf = MemoryUtil.memAllocShort(totalShorts)
                val samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcmBuf)
                STBVorbis.stb_vorbis_close(decoder)

                val actualShorts = samplesRead * channels
                val byteArray = ByteArray(actualShorts * 2)
                for (i in 0 until actualShorts) {
                    val s = pcmBuf.get(i).toInt()
                    byteArray[i * 2] = (s and 0xFF).toByte()
                    byteArray[i * 2 + 1] = (s shr 8).toByte()
                }

                val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
                val bais = ByteArrayInputStream(byteArray)
                val audioStream = AudioInputStream(bais, format, (byteArray.size / format.frameSize).toLong())
                val clip = AudioSystem.getClip()
                clip.open(audioStream)
                setVolume(clip, volume)
                clip.addLineListener { e -> if (e.type == LineEvent.Type.STOP) clip.close() }
                currentClip = clip
                clip.start()
            } finally {
                info.free()
            }
        } finally {
            pcmBuf?.let { MemoryUtil.memFree(it) }
            MemoryUtil.memFree(nativeBuf)
        }
    }

    private fun setVolume(clip: Clip, volume: Float) {
        try {
            val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val dB = if (volume <= 0f) gain.minimum
                     else (20.0 * Math.log10(volume.toDouble())).toFloat()
                         .coerceIn(gain.minimum, gain.maximum)
            gain.value = dB
        } catch (_: Exception) {}
    }
}

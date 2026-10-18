package com.gruning.mimir.rewrite.core.audio

/**
 * What the player is doing with the audio of Your music's current track, for the track-info line. Pure data: nothing here
 * touches Media3, so the strings and the arithmetic are JVM-testable. A part that is unknown is null and is left out of the
 * strings, never printed as "null".
 *
 * @property codec the label of the compressed or container format ("FLAC", "MP3", "AAC", "Opus", "Vorbis", "WAV", "ALAC"), or
 *  null for a format with no label here.
 * @property lossless FLAC, ALAC and WAV: the bit depth is the file's and the bitrate is the file's average.
 * @property bitDepth the source's bits per sample, for lossless sources only (a lossy stream has no bit depth).
 * @property sampleRateHz the source's sample rate.
 * @property channels the source's channel count.
 * @property bitrateKbps a lossy stream's declared bitrate, or a lossless file's size x 8 / duration; null when unknown.
 * @property output what the player hands the system mixer, once the audio track exists.
 */
data class TrackFormat(
    val codec: String? = null,
    val lossless: Boolean = false,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val output: OutputFormat? = null,
) {
    /** "FLAC · 24/96 · 2,304 kbps" or "MP3 · 320 kbps · 44.1 kHz"; null when nothing about the source is known. */
    val sourceLine: String? get() = TrackFormatText.source(this)

    /** "Output 16-bit · 48 kHz"; null until the audio track is initialised. */
    val outputLine: String? get() = TrackFormatText.output(output)

    /** "16-bit · 48 kHz": [outputLine] without its "Output " lead, for a row that is itself titled Output; null until known. */
    val outputValue: String? get() = TrackFormatText.outputValue(output)
}

/** The format of the PCM the player writes: [bitDepth] is 16 for 16-bit and 32 for float, and [isFloat] says which. */
data class OutputFormat(val bitDepth: Int? = null, val isFloat: Boolean = false, val sampleRateHz: Int? = null)

/** The strings of [TrackFormat], and the arithmetic and mappings behind it, over plain numbers and strings. */
internal object TrackFormatText {
    private const val Separator = " · "

    // Media3's C.ENCODING_* values, spelled out so this file stays free of the unstable API.
    private const val ENCODING_PCM_8BIT = 3
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_24BIT = 21
    private const val ENCODING_PCM_32BIT = 22
    private const val ENCODING_PCM_FLOAT = 4

    fun source(format: TrackFormat): String? {
        val parts = ArrayList<String>(3)
        format.codec?.let(parts::add)
        if (format.lossless) {
            depthAndRate(format.bitDepth, format.sampleRateHz)?.let(parts::add)
            format.bitrateKbps?.let { parts += "${grouped(it)} kbps" }
        } else {
            format.bitrateKbps?.let { parts += "${grouped(it)} kbps" }
            format.sampleRateHz?.let { parts += kilohertz(it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(Separator)
    }

    fun output(output: OutputFormat?): String? = outputValue(output)?.let { "Output $it" }

    fun outputValue(output: OutputFormat?): String? {
        if (output == null) return null
        val depth = when {
            output.isFloat -> "float"
            output.bitDepth != null -> "${output.bitDepth}-bit"
            else -> null
        }
        val parts = listOfNotNull(depth, output.sampleRateHz?.let(::kilohertz))
        return parts.takeIf { it.isNotEmpty() }?.joinToString(Separator)
    }

    /** "24/96" with both known, "96 kHz" or "24-bit" with one. The rate is in kHz and drops a zero fraction ("44.1", "96"). */
    private fun depthAndRate(bitDepth: Int?, rateHz: Int?): String? = when {
        bitDepth != null && rateHz != null -> "$bitDepth/${kiloNumber(rateHz)}"
        rateHz != null -> kilohertz(rateHz)
        bitDepth != null -> "$bitDepth-bit"
        else -> null
    }

    /** 44100 is "44.1 kHz", 48000 is "48 kHz", 22050 is "22.05 kHz". */
    fun kilohertz(rateHz: Int): String = "${kiloNumber(rateHz)} kHz"

    private fun kiloNumber(rateHz: Int): String {
        val whole = rateHz / 1000
        val fraction = rateHz % 1000
        if (fraction == 0) return whole.toString()
        return "$whole." + fraction.toString().padStart(3, '0').trimEnd('0')
    }

    /** 2304 is "2,304": explicit grouping, so the text never depends on the device's locale. */
    fun grouped(value: Int): String {
        val digits = kotlin.math.abs(value).toString()
        val out = StringBuilder()
        digits.forEachIndexed { index, char ->
            if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
            out.append(char)
        }
        return if (value < 0) "-$out" else out.toString()
    }

    /** A lossless file's average bitrate: bits per millisecond are kilobits per second. Null for an unknown size or duration. */
    fun losslessBitrateKbps(sizeBytes: Long, durationMs: Long): Int? {
        if (sizeBytes <= 0L || durationMs <= 0L) return null
        val kbps = (sizeBytes * 8L + durationMs / 2L) / durationMs
        return kbps.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
    }

    /** A lossy stream's bitrate in kbps from bits per second, rounded (319,990 bps is 320); null when the format declares none. */
    fun lossyBitrateKbps(bitsPerSecond: Int): Int? =
        if (bitsPerSecond <= 0) null else (bitsPerSecond + 500) / 1000

    /** The label for a Media3 sample or container MIME type, or null. */
    fun codecLabel(sampleMimeType: String?, containerMimeType: String?): String? = when (sampleMimeType) {
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-L1" -> "MP1"
        "audio/mpeg-L2" -> "MP2"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/alac" -> "ALAC"
        "audio/raw" -> if (containerMimeType == "audio/wav" || containerMimeType == "audio/x-wav" || containerMimeType == null) "WAV" else null
        else -> null
    }

    fun isLossless(codec: String?): Boolean = codec == "FLAC" || codec == "ALAC" || codec == "WAV"

    /** The bits per sample of a Media3 PCM encoding (a float is 32), or null for any other value. */
    fun pcmBitDepth(pcmEncoding: Int): Int? = when (pcmEncoding) {
        ENCODING_PCM_8BIT -> 8
        ENCODING_PCM_16BIT -> 16
        ENCODING_PCM_24BIT -> 24
        ENCODING_PCM_32BIT, ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    fun isFloatEncoding(encoding: Int): Boolean = encoding == ENCODING_PCM_FLOAT

    /**
     * The track's source format from what the extractor declared. [bitDepthEncoding], [sampleRateHz], [channels], [averageBitrate]
     * and [bitrate] are Media3's `Format` fields (-1 for unknown); [losslessBitrateKbps] is the file average, computed by the
     * caller once size and duration are known. Lossy keeps the declared average, else the peak or nominal.
     */
    fun sourceFormat(
        sampleMimeType: String?,
        containerMimeType: String?,
        bitDepthEncoding: Int,
        sampleRateHz: Int,
        channels: Int,
        averageBitrate: Int,
        bitrate: Int,
        losslessBitrateKbps: Int?,
        output: OutputFormat?,
    ): TrackFormat {
        val codec = codecLabel(sampleMimeType, containerMimeType)
        val lossless = isLossless(codec)
        return TrackFormat(
            codec = codec,
            lossless = lossless,
            bitDepth = if (lossless) pcmBitDepth(bitDepthEncoding) else null,
            sampleRateHz = sampleRateHz.takeIf { it > 0 },
            channels = channels.takeIf { it > 0 },
            bitrateKbps = if (lossless) losslessBitrateKbps else lossyBitrateKbps(if (averageBitrate > 0) averageBitrate else bitrate),
            output = output,
        )
    }
}

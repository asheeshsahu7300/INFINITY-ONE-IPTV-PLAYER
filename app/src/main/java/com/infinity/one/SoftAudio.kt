package com.infinity.one

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/**
 * Software audio decoder (FFmpeg): plays audio formats like AC3, E-AC3, DTS or TrueHD even when the device cannot
 * decode them itself. Video extension renderers (FfmpegVideoRenderer) are strictly disabled so 4K video is ALWAYS
 * decoded by the TV hardware decoder (OMX.realtek.video.decoder up to 4096x2176).
 */
object SoftAudio {
    private var checked = false
    private var ok = false

    fun available(ctx: Context): Boolean {
        if (checked) return ok
        checked = true
        ok = try {
            val c = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
                .getConstructor(Handler::class.java, AudioRendererEventListener::class.java, AudioSink::class.java)
            c.newInstance(null, null, DefaultAudioSink.Builder(ctx.applicationContext).build())
            true
        } catch (t: Throwable) {
            false
        }
        return ok
    }

    fun factory(ctx: Context): DefaultRenderersFactory = CustomRenderersFactory(ctx)
}

class CustomRenderersFactory(ctx: Context) : DefaultRenderersFactory(ctx) {
    init {
        setExtensionRendererMode(if (SoftAudio.available(ctx)) EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_OFF)
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // ALWAYS use hardware video decoding! Never use software CPU video decoding (FfmpegVideoRenderer) for video!
        super.buildVideoRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // Extension mode ON so FFmpeg decodes DTS, DTS-HD, TrueHD, AC3, EAC3
        super.buildAudioRenderers(
            context,
            if (SoftAudio.available(context)) EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        // Disable float output to ensure maximum compatibility with TV audio drivers and ARC/eARC
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}

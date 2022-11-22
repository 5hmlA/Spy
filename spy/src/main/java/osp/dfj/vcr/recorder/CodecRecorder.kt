package osp.dfj.vcr.recorder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.net.toUri
import osp.dfj.vcr.Spy
import osp.dfj.vcr.SpyConfig
import osp.dfj.vcr.log
import osp.dfj.vcr.touch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * @author yun.
 * @date 2022/11/20
 * @des [一句话描述]
 * @since [https://github.com/ZuYun]
 * <p><a href="https://github.com/ZuYun">github</a>
 */
internal class CodecRecorder(private val context: Context, private val data: Intent, private val config: SpyConfig) : Spy {

    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val MIME_TYPE = "video/avc" // H.264 Advanced Video Coding
    private val TIMEOUT_USEC = 10000L

    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private val mixRunnig: MixRunnig = config.mixRunningClass.newInstance()
    private var trackIndex: Int = 0
    private var muxerStarted: Boolean = false
    private val encoding = AtomicBoolean(false)
    private var presentationTimeUs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()
    private var stopHandler: Continuation<Uri?>? = null
    private var keepFile = false
    private val tempFilePath = "${context.externalCacheDir?.path ?: context.cacheDir.path}/Spy/spy_cache.mp4".apply {
        File(this).touch(true)
    }

    private fun prepareEncoder() {
        //1280, 720
        //宽高不能太大 否则部分手机会奔溃 比如vivo
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics().apply {
            windowManager.defaultDisplay.getRealMetrics(this)
        }
//        val currentWindowMetrics = windowManager.currentWindowMetrics
//        "--> ${currentWindowMetrics.bounds} > $displayMetrics".log()
        val isbigSize = context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK == Configuration.SCREENLAYOUT_SIZE_LARGE
        val proportion = if (isbigSize) 2 else 1
        val width = 720 * proportion
        //宽高比 必须是16
        val height = (displayMetrics.heightPixels * 1F / displayMetrics.widthPixels * width).toInt() / 16 * 16 * proportion

        //createMediaProjection
        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data)
        //encoder
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.videoConfig.bitrate ?: (6 * width * height))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.videoConfig.frameRate ?: 60)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.videoConfig.frameInterval ?: 10)
        encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = createInputSurface()
            //createVirtualDisplay 必须在线程执行 否则会卡死主线程 因为handler是空的
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "spy_monitor",
                width,
                height,
                Resources.getSystem().displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                surface,
                null,
                //The Handler on which the callback should be invoked, or null if the callback should be invoked on the calling thread's main android.os.Looper
                null
            )
            start()
        }
        muxer = MediaMuxer(tempFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
    }


    override fun record() {
        thread {
            prepareEncoder()
            encoding.set(true)
            recordingMixingAndRelease()
        }
    }

    override fun pause() {
    }

    override fun resume() {
    }

    override suspend fun stop(keep: Boolean) = suspendCoroutine {
        stopHandler = it
        keepFile = keep
        encoding.set(false)
        " $this stop >> $keep".log()
    }

    private fun recordingMixingAndRelease() {
        try {
            while (encoding.get()) {
                //循环 录制 从视频buffer解码数据保存到 muxer
                recordVirtualDisplay()
            }
            endOfEncode()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            releaseEncoder()
        }
    }

    private fun recordVirtualDisplay() {
        with(encoder) {
            val encoderStatus = dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw RuntimeException("format changed twice")
                }
                ("retrieving buffers INFO_OUTPUT_FORMAT_CHANGED!").log()
                // should happen before receiving buffers, and should only happen once
                val newFormat = outputFormat
                trackIndex = muxer.addTrack(newFormat)
                //只出现一次 而且在收到视频buggers之前
                readyForEncoderBuffers(muxer)
                muxer.start()
                muxerStarted = true
            } else if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                ("no output available, spinning to await EOS").log()
                //等一会儿再继续
                try {
                    Thread.sleep(10)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (encoderStatus < 0) {
                ("unexpected result from encoder.dequeueOutputBuffer: $encoderStatus").log()
            } else {
                if (muxerStarted) {
                    //数据解码
                    val encodedData = getOutputBuffer(encoderStatus)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.
                        // Ignore it.
                        ("ignoring BUFFER_FLAG_CODEC_CONFIG").log()
                        bufferInfo.size = 0
                    }
                    val bufferSize = bufferInfo.size
                    if (bufferSize == 0 || encodedData == null) {
                        ("info.size == 0, drop it. encodedData = $encodedData").log()
                        return
                    }
                    presentationTimeUs = bufferInfo.presentationTimeUs
                    val offset = bufferInfo.offset
                    """
                        got buffer, info: size=$bufferSize, 
                        presentationTimeUs=$presentationTimeUs, 
                        offset=$offset, 
                        trackIndex=$trackIndex
                    """.log()
                    encodedData.position(offset)
                    encodedData.limit(offset + bufferSize)
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    updatePresentationTimeUs(muxer, presentationTimeUs)
                    //释放bugger
                    releaseOutputBuffer(encoderStatus, false)
                } else {
                    throw RuntimeException("muxer hasn't started")
                }
            }
        }
    }

    private fun releaseEncoder() {
        try {
            encoder.stop()
            encoder.release()
            virtualDisplay.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            muxer.stop()
        } finally {
            muxer.release()
        }
    }


    private fun readyForEncoderBuffers(muxer: MediaMuxer) {
        mixRunnig.readyForEncoderBuffers(context, muxer)
    }

    private fun updatePresentationTimeUs(muxer: MediaMuxer, presentationTimeUs: Long) {
        mixRunnig.updatePresentationTimeUs(muxer, presentationTimeUs)
    }

    private fun endOfEncode() {
        mixRunnig.endOfEncode()
        if (keepFile) {
            val uri = config.storageConfig.saveFile(context, tempFilePath)
            stopHandler?.resume(uri)
            " $this > endOfEncode >>> $keepFile uri:$uri".log()
        } else {
            stopHandler?.resume(null)
            " $this > endOfEncode >>> $keepFile".log()
        }
    }
}
//com.android.systemui.screenrecord.ScreenMediaRecorder
//com.android.systemui.screenrecord.ScreenRecordingMuxer
interface MixRunnig {
    fun readyForEncoderBuffers(context: Context, muxer: MediaMuxer)
    fun updatePresentationTimeUs(muxer: MediaMuxer, presentationTimeUs: Long)
    fun endOfEncode()
}

class LogMixRunning : MixRunnig {
    override fun readyForEncoderBuffers(context: Context, muxer: MediaMuxer) {
        (" >> readyForEncoderBuffers $muxer").log()
    }

    override fun updatePresentationTimeUs(muxer: MediaMuxer, presentationTimeUs: Long) {
        (" >> updatePresentationTimeUs $muxer > $presentationTimeUs").log()
    }

    override fun endOfEncode() {
        (" >> endOfEncode").log()
    }
}

//同时录制音频
class RecordAutioMixRunning : MixRunnig {
    override fun readyForEncoderBuffers(context: Context, muxer: MediaMuxer) {
        (" >> readyForEncoderBuffers $muxer").log()
    }

    override fun updatePresentationTimeUs(muxer: MediaMuxer, presentationTimeUs: Long) {
        (" >> updatePresentationTimeUs $muxer > $presentationTimeUs").log()
    }

    override fun endOfEncode() {
        (" >> endOfEncode").log()
    }
}

class MusicMixRunning : MixRunnig {
    private val audioExtractor = MediaExtractor()

    var videoTimeUs = 0L
    var videoUsStart = 0L
    var writeAudioIndex = 0
    var audioDuration = 0L
    var muxer: MediaMuxer? = null

    override fun readyForEncoderBuffers(context: Context, muxer: MediaMuxer) {
        (" >> readyForEncoderBuffers $muxer").log()
        val musicRes = 0//todo
        this.muxer = muxer
        context.resources.openRawResourceFd(musicRes).use {
            audioExtractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)

            fun findAudioTrack(): Int {
                for (i in 0..audioExtractor.trackCount) {
                    val track = audioExtractor.getTrackFormat(i)
                    if (track.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        return i
                    }
                }
                return 0
            }

            val audioTrack = findAudioTrack()
            audioExtractor.selectTrack(audioTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            audioDuration = audioFormat.getLong(MediaFormat.KEY_DURATION) - TimeUnit.SECONDS.toMicros(2)
            writeAudioIndex = muxer.addTrack(audioFormat)
        }
    }

    override fun updatePresentationTimeUs(muxer: MediaMuxer, presentationTimeUs: Long) {
        (" >> updatePresentationTimeUs $muxer > $presentationTimeUs").log()
        if (videoUsStart == 0L) {
            videoUsStart = presentationTimeUs
        }
        videoTimeUs = presentationTimeUs
    }

    @SuppressLint("WrongConstant")
    override fun endOfEncode() {
        (" >> endOfEncode").log()
        val videoDuration = videoTimeUs - videoUsStart
        " >> video duration > ${TimeUnit.MICROSECONDS.toSeconds(videoDuration)}".log()
        muxer?.run {
            val audioBuffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()
            val sampleFlags = audioExtractor.sampleFlags
            val diff = (audioDuration - videoDuration).coerceAtLeast(0)
            if (diff > 0) {
                audioExtractor.seekTo(diff, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            while (true) {
                val readSampleDataSize = audioExtractor.readSampleData(audioBuffer, 0)
                if (readSampleDataSize <= 0) {
                    //音频读取完了 从头开始
                    audioExtractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    continue
                }
                audioBufferInfo.flags = sampleFlags
                audioBufferInfo.offset = 0
                audioBufferInfo.size = readSampleDataSize                      //sampleTime 这段时长
                audioBufferInfo.presentationTimeUs = videoUsStart + audioExtractor.sampleTime - diff
                writeSampleData(writeAudioIndex, audioBuffer, audioBufferInfo)
                if (audioBufferInfo.presentationTimeUs >= videoTimeUs) {
                    break
                }
                audioExtractor.advance()
            }
        }
        audioExtractor.release()
    }
}
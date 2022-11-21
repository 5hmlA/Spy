package osp.dfj.vcr

import android.content.Context
import android.os.Environment
import android.os.Parcelable
import android.util.Log
import androidx.annotation.RestrictTo
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import osp.dfj.vcr.recorder.DeFrontServiceNotify
import osp.dfj.vcr.recorder.DefMxiRunning
import osp.dfj.vcr.recorder.FrontServiceNotify
import osp.dfj.vcr.recorder.MixRunnig
import java.io.File

/**
 * @author yun.
 * @date 2022/11/20
 * @des [一句话描述]
 * @since [https://github.com/ZuYun]
 * <p><a href="https://github.com/ZuYun">github</a>
 */
@Parcelize
data class SpyConfig(
    val videoConfig: VideoConfig,
    val storageConfig: StorageConfig,
    val notifyClass: Class<out FrontServiceNotify>,
    val mixRunningClass: Class<out MixRunnig>
) : Parcelable

@RestrictTo(RestrictTo.Scope.LIBRARY)
data class SpyConfigBuilder(
    private val videoConfig: VideoConfig = VideoConfig(),
    private val storageConfig: StorageConfig = StorageConfig(),
    var notifyClass: Class<out FrontServiceNotify> = DeFrontServiceNotify::class.java,
    var mixRunningClass: Class<out MixRunnig> = DefMxiRunning::class.java
) {
    @JvmSynthetic
    fun video(config: VideoConfig.() -> Unit) = videoConfig.config()

    fun storage(config: StorageConfig.() -> Unit) = storageConfig.config()

    internal fun build(): SpyConfig = SpyConfig(videoConfig, storageConfig, notifyClass, mixRunningClass)
}

@Parcelize
data class VideoConfig @JvmOverloads constructor(
    var bitrate: Int? = null,
    var frameRate: Int? = null,
    var frameInterval: Int? = null,
) : Parcelable

/**
 * An immutable data class representing configuration options for the storage of the output file.
 */
@Parcelize
data class StorageConfig @JvmOverloads constructor(
    val fileName: String = "spy",
    val directory: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
) : Parcelable {

    @IgnoredOnParcel
    internal val mediaStorageLocation: File = File(directory, fileName).touch(true)

}

internal fun File.touch(refresh: Boolean = false): File {
    if (!exists()) {
        if (!mkdirs()) {
            "failed to create file $path".log()
        }
    } else if (refresh) {
        if (delete()) {
            "delete old file succeed $path".log()
        }
    }
    return this
}
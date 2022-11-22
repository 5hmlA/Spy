package osp.dfj.vcr

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import androidx.annotation.RestrictTo
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import osp.dfj.vcr.recorder.*
import java.io.File
import java.nio.file.Files

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
    var mixRunningClass: Class<out MixRunnig> = LogMixRunning::class.java
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
    val fileName: String = "spy.mp4",
    val directory: File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Spy"),
) : Parcelable {

    fun saveFile(context: Context, url: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis())
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis())
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Spy")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        // Add to the mediastore
//        val os = resolver.openOutputStream(uri!!, "w")
//
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            Files.copy(File(url).toPath(), os)
//        }
        File(url).toURL().openStream().use { input ->
            resolver.openOutputStream(uri!!).use { output ->
                input.copyTo(output!!, DEFAULT_BUFFER_SIZE)
            }
        }
        return uri
    }
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
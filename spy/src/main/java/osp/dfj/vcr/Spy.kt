package osp.dfj.vcr

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import osp.dfj.vcr.recorder.CaptureState
import osp.dfj.vcr.recorder.ScreenService
import kotlin.coroutines.*


/**
 * @author yun.
 * @date 2022/11/20
 * @des [一句话描述]
 * @since [https://github.com/ZuYun]
 * <p><a href="https://github.com/ZuYun">github</a>
 */
interface Spy {

    companion object {
        fun monitor(activity: ComponentActivity, config: SpyConfigBuilder.() -> Unit = {}): Spy {
            return Diplomat(activity, SpyConfigBuilder().apply(config).build())
        }
    }

    fun record()
    fun pause()
    fun resume()
    suspend fun stop(keep: Boolean): Uri?
}

internal class Diplomat(private val activity: ComponentActivity, private val config: SpyConfig) : Spy {

    inner class ScreenCaptureContract(val context: Context) : ActivityResultContract<Unit, Intent?>() {
        private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager


        override fun createIntent(context: Context, input: Unit): Intent {
            return mediaProjectionManager.createScreenCaptureIntent()
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Intent? {
            return intent
        }
    }

    private val permissionRequest: ActivityResultLauncher<Array<String>>
    private val screenCaptureRequest: ActivityResultLauncher<Unit>
    private var stopHandler: Continuation<Uri?>? = null

    init {
        permissionRequest = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            " permissionRequest $it".log()
            permissionCheckThenCaptureRequest(activity)
        }
        screenCaptureRequest = activity.registerForActivityResult(ScreenCaptureContract(activity)) {
            if (it != null) {
                allowedCapture(activity, it)
            }
        }
        _broadcaster.value = null
        _broadcaster.observe(activity) {
            if (it is CaptureState.Finish) {
                " BroadcastReceiver $this  path:${it.uri} ".log()
                stopHandler?.resume(it.uri)
                stopHandler = null
            }
        }

        val builder = VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.detectFileUriExposure()
        }
    }

    private fun permissionCheckThenCaptureRequest(activity: ComponentActivity) {
        val checkPermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                    + ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE)) + ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                    + ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE))
        }
        if (checkPermission != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } else {
            //请求录制
            screenCaptureRequest.launch(Unit)
        }
    }

    private fun allowedCapture(activity: ComponentActivity, data: Intent) {
        ScreenService.record(activity, data, config)
    }

    override fun record() {

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            https://developer.android.google.cn/training/data-storage/manage-all-files
//            " >> check ExternalStorage >> ${Environment.isExternalStorageManager()}".log()
//            if (!Environment.isExternalStorageManager()) {
//                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
//                return
//            }
//        }
        //权限检查
        permissionCheckThenCaptureRequest(activity)
    }

    override fun pause() {
    }

    override fun resume() {
    }

    override suspend fun stop(keep: Boolean) = suspendCoroutine {
        if (_broadcaster.value == null) {
            throw RuntimeException("stop must be after record")
        }
        if (stopHandler != null) {
            throw RuntimeException("stop only once after record")
        }
        stopHandler = it
        ScreenService.stop(activity, keep)
    }
}


object IOScope : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

}

internal fun String.log() {
    Log.d("Spy", this)
}

fun Uri?.open(context: Context) {
    if (this == null) {
        return
    }

//    val intent = Intent()
//    val comp: ComponentName = ComponentName(
//        "com.tencent.mm",
//        "com.tencent.mm.ui.tools.ShareToTimeLineUI"
//    )
//    intent.setComponent(comp)
//    intent.setAction("android.intent.action.SEND")
//    intent.setType("image/*")
//    intent.putExtra(Intent.EXTRA_TEXT, "我是文字")
//    intent.putExtra(Intent.EXTRA_STREAM, this)
//    context.startActivity(intent)
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(this@open, "video/*")
        context.startActivity(this)
    }
}

internal val _broadcaster = MutableLiveData<CaptureState?>()
//val broadcaster: LiveData<CaptureState?> = _broadcaster
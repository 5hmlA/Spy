package osp.dfj.vcr

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val broadcaster by lazy {
        LocalBroadcastManager.getInstance(activity)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("spy") != null) {
                " BroadcastReceiver $this  path:${intent.data} ".log()
                stopHandler?.resume(intent.data)
                stopHandler = null
                broadcaster.unregisterReceiver(this)
            }
        }
    }

    init {
        permissionRequest = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionCheckThenCaptureRequest(activity)
        }
        screenCaptureRequest = activity.registerForActivityResult(ScreenCaptureContract(activity)) {
            if (it != null) {
                allowedCapture(activity, it)
            }
        }
    }

    private fun permissionCheckThenCaptureRequest(activity: ComponentActivity) {
        val checkPermission = (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                + ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE)
                + ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE))
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
        broadcaster.registerReceiver(receiver, IntentFilter(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE))
        ScreenService.record(activity, data, config)
    }

    override fun record() {
        //权限检查
        permissionCheckThenCaptureRequest(activity)
    }

    override fun pause() {
    }

    override fun resume() {
    }

    override suspend fun stop(keep: Boolean) = suspendCoroutine {
        if (stopHandler != null) {
            throw RuntimeException("stop only once after record")
        }
        stopHandler = it
        ScreenService.stop(activity, keep)
    }
}


object IOScope : CoroutineScope {
    /**
     * Returns [EmptyCoroutineContext].
     */
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
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(this@open, "video/*")
        context.startActivity(this)
    }
}
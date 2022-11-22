package osp.dfj.vcr.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import osp.dfj.spy.R
import osp.dfj.vcr.*
import osp.dfj.vcr._broadcaster
import osp.dfj.vcr.log

/**
 * @author yun.
 * @date 2022/11/20
 * @des [一句话描述]
 * @since [https://github.com/ZuYun]
 * <p><a href="https://github.com/ZuYun">github</a>
 */
interface FrontServiceNotify {
    fun notify(service: Service): Pair<Int, Notification>?
}

class DeFrontServiceNotify : FrontServiceNotify {
    override fun notify(service: Service): Pair<Int, Notification>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notificationManager =
                service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationChannelId = "11011"
            val channel = NotificationChannel(
                notificationChannelId,
                "Spy Recorder",
                NotificationManager.IMPORTANCE_MIN
            ).let {
                it.description = "Screen Record Service channel"
                it.enableLights(false)
                it.lightColor = Color.RED
                it.enableVibration(true)
                it
            }
            notificationManager.createNotificationChannel(channel)
            val icon = service.packageManager.getApplicationIcon(service.packageName).toBitmap()
            val notificationBuilder: NotificationCompat.Builder = NotificationCompat.Builder(service, notificationChannelId)
                .setLargeIcon(icon)
                .setContentTitle("Spy Service")
                .setContentText("monitoring screen")
            return 110 to notificationBuilder.build()
        }
        return null
    }
}

abstract class FrontService : Service() {

    fun tobeFrontService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notify()?.run {
                startForeground(first, second, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    abstract fun notify(): Pair<Int, Notification>?

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            stopForeground(true)
        }
    }
}

sealed class CaptureState(val desc: String) {
    object Start : CaptureState("start")
    object Stop : CaptureState("stop")
    data class Finish(val uri: Uri?) : CaptureState("finish")
}

internal class ScreenService : FrontService(), Spy, CoroutineScope by IOScope {

    companion object {
        fun record(context: Context, intent: Intent, spyConfig: SpyConfig) {
            context.startService(Intent(context, ScreenService::class.java).apply {
                putExtra("cmd", CaptureState.Start.desc)
                putExtra("intent", intent)
                putExtra("spy_config", spyConfig)
            })
        }

        fun stop(context: Context, keep: Boolean) {
            context.startService(Intent(context, ScreenService::class.java).apply {
                putExtra("cmd", CaptureState.Stop.desc)
                putExtra("keep", keep)
            })
        }
    }

    private var codecRecoder: CodecRecorder? = null
    private var spyConfig: SpyConfig? = null
    private var state: String = "idle"


    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val cmd = intent.getStringExtra("cmd")
        " $this > state:$state >> cmd:$cmd ".log()
        if (cmd == null || state == cmd) {
            return START_NOT_STICKY
        }
        when (cmd) {
            CaptureState.Start.desc -> {
                _broadcaster.postValue(CaptureState.Start)
                spyConfig = intent.getParcelableExtra<SpyConfig>("spy_config")!!
                tobeFrontService()
                val data = intent.getParcelableExtra<Intent>("intent")!!
                codecRecoder = CodecRecorder(this@ScreenService, data, spyConfig!!)
                record()
                state = cmd
            }
            CaptureState.Stop.desc -> {
                launch {
                    if (state == CaptureState.Start.desc) {
                        stop(intent.getBooleanExtra("keep", false))?.run {
                            val mediaScanIntent = Intent(
                                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE
                            )
                            mediaScanIntent.putExtra("spy", "spy")
                            mediaScanIntent.data = this
                            sendBroadcast(mediaScanIntent)
                            _broadcaster.postValue(CaptureState.Finish(this))
                            " $state save filed $this broadcast ".log()
                        } ?: _broadcaster.postValue(CaptureState.Finish(null))
                    }
                    state = cmd
                    " $state stop self ".log()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun record() {
        codecRecoder?.record()
    }

    override fun pause() {

    }

    override fun resume() {
    }

    override suspend fun stop(keep: Boolean): Uri? {
        return codecRecoder?.stop(keep)
    }

    override fun notify(): Pair<Int, Notification>? {
        return spyConfig?.notifyClass?.newInstance()?.notify(this) ?: DeFrontServiceNotify().notify(this)
    }
}
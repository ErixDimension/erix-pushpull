package com.erix.twrp.transfer
import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.adb.AdbUsbTransport
import com.erix.twrp.storage.LocalStorageManager
import com.erix.twrp.storage.RemoteStorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// Runtime object menyimpan instance Dadb yang aktif
object AdbRuntime { 
    @Volatile var active: Pair<AdbUsbTransport, String>? = null 
}

class TransferService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "erix_transfer"; const val NOTIF_ID = 0x4552
        const val EXTRA_ACTION = "action"; const val EXTRA_SRC = "src"; const val EXTRA_DST = "dst"
        val events = MutableSharedFlow<TransferEvent>(replay = 0, extraBufferCapacity = 64)
        fun start(context: Context, action: String, src: String, dst: String) {
            val i = Intent(context, TransferService::class.java).apply { 
                putExtra(EXTRA_ACTION, action); putExtra(EXTRA_SRC, src); putExtra(EXTRA_DST, dst) 
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
    }
    
    private lateinit var adb: AdbManager; 
    private lateinit var local: LocalStorageManager; 
    private lateinit var resume: ResumeManager; 
    private lateinit var engine: TransferEngine; 
    private var job: Job? = null
    
    override fun onCreate() { 
        super.onCreate(); createChannel(); startForeground(NOTIF_ID, buildNotif("Preparing...", 0, 0)) 
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return START_NOT_STICKY
        val src = intent.getStringExtra(EXTRA_SRC) ?: return START_NOT_STICKY
        val dst = intent.getStringExtra(EXTRA_DST) ?: return START_NOT_STICKY
        
        val (dadbInstance, serial) = AdbRuntime.active ?: run { 
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif("Device tidak terhubung", 0, 0))
            stopSelf(); return START_NOT_STICKY 
        }
        
        adb = AdbManager(this, dadbInstance)
        local = LocalStorageManager(this)
        resume = ResumeManager(this)
        engine = TransferEngine(adb, local, RemoteStorageManager(adb), resume, serial)
        
        job = lifecycleScope.launch {
            try { 
                engine.transfer(action, src, dst).collect { ev -> 
                    events.tryEmit(ev)
                    if (ev is TransferEvent.Progress) {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif("${FormatSize.human(ev.done)} / ${FormatSize.human(ev.total)}", ev.done, ev.total))
                    } 
                } 
            } finally { 
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() 
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onDestroy() { job?.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }
    
    private fun createChannel() { 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { 
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ERIX Transfer", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            ) 
        } 
    }
    
    private fun buildNotif(text: String, done: Long, total: Long): Notification { 
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ERIX Transfer").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (total > 0) b.setProgress(100, ((done * 100) / total).toInt(), false)
        return b.build() 
    }
    
    private object FormatSize { 
        fun human(b: Long): String { 
            var v = b.toDouble(); val u = listOf("B","KB","MB","GB"); var i = 0
            while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
            return if (i == 0) "%d B".format(v.toLong()) else "%.1f %s".format(v, u[i]) 
        } 
    }
}

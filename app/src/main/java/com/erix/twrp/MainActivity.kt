package com.erix.twrp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.erix.twrp.adb.AdbDeviceWatcher
import com.erix.twrp.databinding.ActivityMainBinding
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        AdbDeviceWatcher.start(this)
        lifecycleScope.launch {
            DebugLogger.logs.collect { b.tvLog.text = it }
        }
    }
}

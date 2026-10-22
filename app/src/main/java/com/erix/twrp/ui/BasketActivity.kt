package com.erix.twrp.ui
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.erix.twrp.databinding.ActivityBasketBinding
class BasketActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); val b = ActivityBasketBinding.inflate(layoutInflater); setContentView(b.root); b.btnCancel.setOnClickListener { finish() } }
}

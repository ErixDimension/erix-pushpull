package com.erix.twrp.ui
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.erix.twrp.databinding.ActivityBasketBinding
import com.erix.twrp.util.BasketManager
class BasketActivity : AppCompatActivity() {
    private lateinit var b: ActivityBasketBinding; private lateinit var adapter: BasketAdapter
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityBasketBinding.inflate(layoutInflater); setContentView(b.root)
        if (BasketManager.items.isEmpty()) { finish(); return }
        adapter = BasketAdapter(BasketManager.items); b.rvBasket.layoutManager = LinearLayoutManager(this); b.rvBasket.adapter = adapter
        b.btnCancel.setOnClickListener { BasketManager.clear(); finish() }
        b.btnPush.setOnClickListener { Toast.makeText(this, "Fitur Keranjang menyusul", Toast.LENGTH_SHORT).show() }
        b.btnPull.setOnClickListener { Toast.makeText(this, "Fitur Keranjang menyusul", Toast.LENGTH_SHORT).show() }
    }
}

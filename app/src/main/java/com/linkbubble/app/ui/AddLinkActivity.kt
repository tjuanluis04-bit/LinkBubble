package com.linkbubble.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.linkbubble.app.data.AppDatabase
import com.linkbubble.app.data.LeafCategory
import com.linkbubble.app.data.LinkItem
import com.linkbubble.app.databinding.ActivityAddLinkBinding
import kotlinx.coroutines.launch

class AddLinkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY_ID = "extra_category_id"
    }

    private lateinit var binding: ActivityAddLinkBinding
    private var categories: List<LeafCategory> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddLinkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        // Si viene de "Compartir" desde otra app, precarga el texto/URL compartido.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                binding.etUrl.setText(sharedText.trim())
            }
        }

        val preselectedCategoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        val dao = AppDatabase.getInstance(this).categoryDao()

        lifecycleScope.launch {
            categories = dao.getAllLeafCategoriesOnce()
            if (categories.isEmpty()) {
                Toast.makeText(
                    this@AddLinkActivity,
                    "Creá primero una categoría y una subcategoría desde la burbuja",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            val names = categories.map { "${it.parentName} / ${it.name}" }
            binding.spinnerCategory.adapter = ArrayAdapter(
                this@AddLinkActivity,
                android.R.layout.simple_spinner_dropdown_item,
                names
            )
            if (preselectedCategoryId != null) {
                val index = categories.indexOfFirst { it.id == preselectedCategoryId }
                if (index >= 0) binding.spinnerCategory.setSelection(index)
            }
        }

        binding.btnCancel.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Pega una URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (categories.isEmpty()) return@setOnClickListener

            val selectedCategory = categories[binding.spinnerCategory.selectedItemPosition]
            val title = binding.etTitle.text.toString().trim().ifEmpty { url }

            val linkDao = AppDatabase.getInstance(this).linkDao()
            lifecycleScope.launch {
                linkDao.insert(
                    LinkItem(
                        categoryId = selectedCategory.id,
                        url = url,
                        title = title
                    )
                )
                Toast.makeText(this@AddLinkActivity, "Link guardado", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

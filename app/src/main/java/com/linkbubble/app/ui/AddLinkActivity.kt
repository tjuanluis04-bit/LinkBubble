package com.linkbubble.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.linkbubble.app.data.AppDatabase
import com.linkbubble.app.data.Category
import com.linkbubble.app.data.LeafCategory
import com.linkbubble.app.data.LinkItem
import com.linkbubble.app.data.SyncRepository
import com.linkbubble.app.databinding.ActivityAddLinkBinding
import kotlinx.coroutines.launch

class AddLinkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY_ID = "extra_category_id"
    }

    private lateinit var binding: ActivityAddLinkBinding
    private var categories: List<LeafCategory> = emptyList()
    private var selectedNewColor: String = ColorPalette.COLORS.first()

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
        loadCategories(preselectedCategoryId)

        binding.btnToggleNewCategory.setOnClickListener {
            val visible = binding.llNewCategorySection.visibility == android.view.View.VISIBLE
            binding.llNewCategorySection.visibility =
                if (visible) android.view.View.GONE else android.view.View.VISIBLE
        }
        buildNewCategoryColorSwatches()

        binding.btnCreateCategory.setOnClickListener { onCreateCategoryClicked() }

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { onSaveClicked() }
    }

    private fun loadCategories(preselectedCategoryId: String?) {
        val dao = AppDatabase.getInstance(this).categoryDao()
        lifecycleScope.launch {
            categories = dao.getAllLeafCategoriesOnce()
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
    }

    private fun buildNewCategoryColorSwatches() {
        val container = binding.llNewCategoryColors
        container.removeAllViews()
        val swatchViews = mutableListOf<android.view.View>()
        val density = resources.displayMetrics.density

        ColorPalette.COLORS.forEach { hex ->
            val size = (32 * density).toInt()
            val margin = (5 * density).toInt()
            val swatch = android.view.View(this)
            swatch.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = margin
                marginEnd = margin
            }
            swatch.tag = hex
            swatch.background = buildSwatchDrawable(hex, hex == selectedNewColor)
            swatch.setOnClickListener {
                selectedNewColor = hex
                swatchViews.forEach { v ->
                    val vHex = v.tag as String
                    v.background = buildSwatchDrawable(vHex, vHex == selectedNewColor)
                }
            }
            swatchViews.add(swatch)
            container.addView(swatch)
        }
    }

    private fun buildSwatchDrawable(hex: String, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(hex))
        if (selected) setStroke((2 * resources.displayMetrics.density).toInt(), Color.BLACK)
    }

    private fun onCreateCategoryClicked() {
        val topName = binding.etNewTopLevelName.text.toString().trim()
        val subName = binding.etNewSubName.text.toString().trim()
        if (topName.isEmpty() || subName.isEmpty()) {
            Toast.makeText(this, "Completá la categoría y la subcategoría", Toast.LENGTH_SHORT).show()
            return
        }

        val db = AppDatabase.getInstance(this)
        val syncRepo = SyncRepository(db)

        lifecycleScope.launch {
            val topOrder = db.categoryDao().getMaxTopLevelOrder()
            val topCategory = Category(
                name = topName, color = selectedNewColor, parentId = null,
                orderIndex = topOrder + 1
            )
            db.categoryDao().insert(topCategory)
            syncRepo.pushCategory(topCategory)

            val subOrder = db.categoryDao().getMaxChildOrder(topCategory.id)
            val subCategory = Category(
                name = subName, color = selectedNewColor, parentId = topCategory.id,
                orderIndex = subOrder + 1
            )
            db.categoryDao().insert(subCategory)
            syncRepo.pushCategory(subCategory)

            binding.etNewTopLevelName.setText("")
            binding.etNewSubName.setText("")
            binding.llNewCategorySection.visibility = android.view.View.GONE

            loadCategoriesAndSelect(subCategory.id)
            Toast.makeText(this@AddLinkActivity, "Categoría creada ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun loadCategoriesAndSelect(categoryId: String) {
        val dao = AppDatabase.getInstance(this).categoryDao()
        categories = dao.getAllLeafCategoriesOnce()
        val names = categories.map { "${it.parentName} / ${it.name}" }
        binding.spinnerCategory.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        val index = categories.indexOfFirst { it.id == categoryId }
        if (index >= 0) binding.spinnerCategory.setSelection(index)
    }

    private fun onSaveClicked() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Pega una URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (categories.isEmpty()) {
            Toast.makeText(this, "Creá una categoría primero (botón + Nueva categoría)", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedCategory = categories[binding.spinnerCategory.selectedItemPosition]
        val title = binding.etTitle.text.toString().trim().ifEmpty { url }

        val db = AppDatabase.getInstance(this)
        val syncRepo = SyncRepository(db)
        lifecycleScope.launch {
            val maxOrder = db.linkDao().getMaxOrder(selectedCategory.id)
            val newLink = LinkItem(
                categoryId = selectedCategory.id, url = url, title = title,
                orderIndex = maxOrder + 1
            )
            db.linkDao().insert(newLink)
            syncRepo.pushLink(newLink)
            Toast.makeText(this@AddLinkActivity, "Link guardado", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

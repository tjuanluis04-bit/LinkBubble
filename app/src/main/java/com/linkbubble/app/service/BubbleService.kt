package com.linkbubble.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.linkbubble.app.MainActivity
import com.linkbubble.app.R
import com.linkbubble.app.data.AppDatabase
import com.linkbubble.app.data.Category
import com.linkbubble.app.data.CategoryWithCount
import com.linkbubble.app.data.LinkItem
import com.linkbubble.app.ui.SubPanelItem
import com.linkbubble.app.ui.SubcategoryAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private sealed class FormMode {
    // parentId: si es null y editingId es null, se crea una categoría horizontal nueva.
    // si parentId tiene valor y editingId es null, se crea una subcategoría bajo ese padre.
    // si editingId tiene valor, se está editando esa categoría/subcategoría existente (nombre/color).
    data class CategoryForm(val editingId: Long? = null, val parentId: Long? = null) : FormMode()
    data class Link(val subcategoryId: Long) : FormMode()
}

class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var formView: View
    private lateinit var closeTargetView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var formParams: WindowManager.LayoutParams
    private lateinit var closeTargetParams: WindowManager.LayoutParams
    private var panelAdded = false
    private var formAdded = false
    private var closeTargetAdded = false

    private lateinit var db: AppDatabase
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var themedInflater: LayoutInflater

    private lateinit var subAdapter: SubcategoryAdapter
    private lateinit var llCategoryChips: LinearLayout

    // Nivel 1: categorías horizontales
    private var topLevelCategories: List<CategoryWithCount> = emptyList()
    private var selectedTopLevelId: Long? = null

    // Nivel 2: subcategorías verticales de la horizontal seleccionada
    private var childCategories: List<CategoryWithCount> = emptyList()
    private var childrenJob: Job? = null
    private val expandedSubIds = mutableSetOf<Long>()
    private val linksBySub = mutableMapOf<Long, List<LinkItem>>()
    private val linkJobs = mutableMapOf<Long, Job>()

    private var formMode: FormMode = FormMode.CategoryForm()
    private var selectedColor: String = COLOR_PALETTE.first()

    companion object {
        val COLOR_PALETTE = listOf(
            "#F44336", "#E91E63", "#9C27B0", "#673AB7",
            "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
            "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
            "#FFEB3B", "#FFC107", "#FF9800", "#795548"
        )
    }

    override fun onCreate() {
        super.onCreate()
        try {
            db = AppDatabase.getInstance(this)
            startForegroundNotification()

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            themedInflater = LayoutInflater.from(
                android.view.ContextThemeWrapper(this, R.style.Theme_LinkBubble)
            )
            setupBubble()
            setupPanel()
            setupForm()
            setupCloseTarget()
            observeTopLevelCategories()
        } catch (e: Throwable) {
            android.util.Log.e("BubbleService", "Fallo al iniciar la burbuja", e)
            writeCrashToFile(e)
            Toast.makeText(
                this,
                "Error guardado en Android/data/com.linkbubble.app/files/crash.txt",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun writeCrashToFile(e: Throwable) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, "crash.txt")
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            file.writeText(sw.toString())
        } catch (inner: Throwable) {
            android.util.Log.e("BubbleService", "No se pudo escribir el crash a archivo", inner)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Notificación en primer plano ----------

    private fun startForegroundNotification() {
        val channelId = "linkbubble_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "LinkBubble", NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LinkBubble activo")
            .setContentText("Toca para abrir la app")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun overlayWidthPx(): Int = (resources.displayMetrics.widthPixels * 0.94).toInt()

    // ---------- Burbuja flotante ----------

    private fun setupBubble() {
        bubbleView = themedInflater.inflate(R.layout.layout_bubble, null)

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        windowManager.addView(bubbleView, bubbleParams)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                        if (!isDragging) {
                            isDragging = true
                            hidePanel()
                            showCloseTarget()
                        }
                    }
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)

                    if (isDragging) {
                        updateCloseTargetHover()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!isDragging && elapsed < 300) {
                        togglePanel()
                    } else if (isDragging && isNearCloseTarget()) {
                        stopSelf()
                    }
                    hideCloseTarget()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Objetivo "soltar para cerrar" ----------

    private fun setupCloseTarget() {
        closeTargetView = themedInflater.inflate(R.layout.layout_close_target, null)

        val metrics = resources.displayMetrics
        val targetSize = (64 * metrics.density).toInt()
        val marginBottom = (120 * metrics.density).toInt()

        closeTargetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = marginBottom - targetSize
        }
    }

    private fun closeTargetCenter(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val targetSize = (64 * metrics.density).toInt()
        val marginBottom = (120 * metrics.density).toInt()
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels - marginBottom - (targetSize / 2)
        return Pair(cx, cy)
    }

    private fun bubbleCenter(): Pair<Int, Int> {
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        return Pair(bubbleParams.x + bubbleSize / 2, bubbleParams.y + bubbleSize / 2)
    }

    private fun isNearCloseTarget(): Boolean {
        val (bx, by) = bubbleCenter()
        val (cx, cy) = closeTargetCenter()
        val dist = kotlin.math.hypot((bx - cx).toDouble(), (by - cy).toDouble())
        return dist < (90 * resources.displayMetrics.density)
    }

    private fun updateCloseTargetHover() {
        val hovering = isNearCloseTarget()
        val label = closeTargetView.findViewById<TextView>(R.id.tvCloseTarget)
        val scale = if (hovering) 1.3f else 1.0f
        label.scaleX = scale
        label.scaleY = scale
    }

    private fun showCloseTarget() {
        if (!closeTargetAdded) {
            runCatching {
                windowManager.addView(closeTargetView, closeTargetParams)
                closeTargetAdded = true
            }
        }
    }

    private fun hideCloseTarget() {
        if (closeTargetAdded) {
            runCatching { windowManager.removeView(closeTargetView) }
            closeTargetAdded = false
        }
    }

    // ---------- Panel: nivel 1 (chips horizontales) + nivel 2 (acordeón vertical) ----------

    private fun setupPanel() {
        panelView = themedInflater.inflate(R.layout.layout_bubble_panel, null)

        panelParams = WindowManager.LayoutParams(
            overlayWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (100 * resources.displayMetrics.density).toInt()
        }

        llCategoryChips = panelView.findViewById(R.id.llCategoryChips)

        val rv = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSubcategories)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        subAdapter = SubcategoryAdapter(
            onToggleExpand = ::toggleExpandSub,
            onAddLinkClicked = ::showLinkForm,
            onSubcategoryMenuClicked = ::showSubcategoryMenu,
            onLinkChecked = ::onLinkChecked,
            onLinkClicked = ::onLinkClicked,
            onLinkMenuClicked = ::showLinkMenu,
            onAddSubcategoryClicked = {
                val parent = selectedTopLevelId
                if (parent == null) {
                    Toast.makeText(this, "Primero elegí o creá una categoría", Toast.LENGTH_SHORT).show()
                } else {
                    showCategoryForm(parentId = parent)
                }
            }
        )
        rv.adapter = subAdapter

        panelView.findViewById<View>(R.id.btnClosePanel).setOnClickListener {
            hidePanel()
        }
    }

    // ---------- Nivel 1: categorías horizontales ----------

    private fun observeTopLevelCategories() {
        serviceScope.launch {
            db.categoryDao().getTopLevelCategories().collect { categories ->
                topLevelCategories = categories

                val stillExists = categories.any { it.id == selectedTopLevelId }
                if (!stillExists) {
                    selectTopLevel(categories.firstOrNull()?.id, forceReload = true)
                }

                rebuildCategoryChips()
            }
        }
    }

    private fun rebuildCategoryChips() {
        llCategoryChips.removeAllViews()
        val density = resources.displayMetrics.density

        topLevelCategories.forEach { cat ->
            val chip = TextView(themedInflater.context)
            chip.text = cat.name
            chip.textSize = 14f
            chip.setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            chip.layoutParams = params

            val isSelected = cat.id == selectedTopLevelId
            val color = runCatching { Color.parseColor(cat.color) }.getOrDefault(Color.parseColor("#6200EE"))
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                if (isSelected) {
                    setColor(color)
                } else {
                    setColor(Color.WHITE)
                    setStroke((1.5f * density).toInt(), color)
                }
            }
            chip.setTextColor(if (isSelected) Color.WHITE else color)

            chip.setOnClickListener { selectTopLevel(cat.id) }
            chip.setOnLongClickListener { showTopLevelMenu(cat); true }

            llCategoryChips.addView(chip)
        }

        val addChip = TextView(themedInflater.context)
        addChip.text = "＋"
        addChip.textSize = 16f
        addChip.setTextColor(Color.parseColor("#6200EE"))
        addChip.setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        addChip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(Color.WHITE)
            setStroke((1.5f * density).toInt(), Color.parseColor("#CCCCCC"))
        }
        addChip.setOnClickListener { showCategoryForm(parentId = null) }
        llCategoryChips.addView(addChip)
    }

    private fun selectTopLevel(id: Long?, forceReload: Boolean = false) {
        if (selectedTopLevelId == id && !forceReload) return
        selectedTopLevelId = id

        // Se cierra el acordeón y se cancelan los observadores de links de la horizontal anterior.
        expandedSubIds.clear()
        linkJobs.values.forEach { it.cancel() }
        linkJobs.clear()
        linksBySub.clear()
        childrenJob?.cancel()
        childCategories = emptyList()

        if (id != null) {
            childrenJob = serviceScope.launch {
                db.categoryDao().getChildCategories(id).collect { children ->
                    childCategories = children
                    rebuildSubList()
                }
            }
        } else {
            rebuildSubList()
        }
    }

    // ---------- Nivel 2: subcategorías verticales + links ----------

    private fun toggleExpandSub(subcategoryId: Long) {
        if (expandedSubIds.contains(subcategoryId)) {
            expandedSubIds.remove(subcategoryId)
            linkJobs[subcategoryId]?.cancel()
            linkJobs.remove(subcategoryId)
            linksBySub.remove(subcategoryId)
        } else {
            expandedSubIds.add(subcategoryId)
            val job = serviceScope.launch {
                db.linkDao().getByCategory(subcategoryId).collect { links ->
                    linksBySub[subcategoryId] = links
                    rebuildSubList()
                }
            }
            linkJobs[subcategoryId] = job
        }
        rebuildSubList()
    }

    private fun rebuildSubList() {
        val items = mutableListOf<SubPanelItem>()
        if (selectedTopLevelId == null) {
            // Sin categoría horizontal seleccionada: no se muestra nada en el acordeón.
            subAdapter.submitList(items)
            return
        }
        for (sub in childCategories) {
            val expanded = expandedSubIds.contains(sub.id)
            items.add(SubPanelItem.Header(sub, expanded))
            if (expanded) {
                val links = linksBySub[sub.id]
                if (links.isNullOrEmpty()) {
                    items.add(SubPanelItem.Empty(sub.id))
                } else {
                    links.forEach { items.add(SubPanelItem.LinkRow(it)) }
                }
            }
        }
        items.add(SubPanelItem.Footer)
        subAdapter.submitList(items)
    }

    // ---------- Formulario flotante (crear/editar categoría u subcategoría, crear link) ----------

    private fun setupForm() {
        formView = themedInflater.inflate(R.layout.layout_bubble_form, null)

        formParams = WindowManager.LayoutParams(
            overlayWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            0, // focusable: necesita foco para que el teclado escriba en los EditText
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (60 * resources.displayMetrics.density).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        formView.findViewById<View>(R.id.btnFormCancel).setOnClickListener { hideForm() }
        formView.findViewById<View>(R.id.btnFormSave).setOnClickListener { onFormSave() }

        buildColorSwatches()
    }

    private fun buildColorSwatches() {
        val container = formView.findViewById<LinearLayout>(R.id.llColorSwatches)
        container.removeAllViews()
        val swatchViews = mutableListOf<View>()

        COLOR_PALETTE.forEach { hex ->
            val size = (36 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            val swatch = View(themedInflater.context)
            val params = LinearLayout.LayoutParams(size, size).apply {
                marginStart = margin
                marginEnd = margin
            }
            swatch.layoutParams = params
            swatch.tag = hex
            swatch.background = buildSwatchDrawable(hex, selected = hex == selectedColor)
            swatch.setOnClickListener {
                selectedColor = hex
                swatchViews.forEach { v ->
                    val vHex = v.tag as String
                    v.background = buildSwatchDrawable(vHex, selected = vHex == selectedColor)
                }
            }
            swatchViews.add(swatch)
            container.addView(swatch)
        }
    }

    private fun buildSwatchDrawable(hex: String, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(hex))
            if (selected) {
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.BLACK)
            }
        }
    }

    private fun showCategoryForm(editing: CategoryWithCount? = null, parentId: Long? = null) {
        formMode = FormMode.CategoryForm(editingId = editing?.id, parentId = parentId)
        selectedColor = editing?.color ?: COLOR_PALETTE.first()
        val isSubcategory = parentId != null || (editing != null && selectedTopLevelId != null && childCategories.any { it.id == editing.id })
        formView.findViewById<TextView>(R.id.tvFormTitle).text = when {
            editing != null && isSubcategory -> "Editar subcategoría"
            editing != null -> "Editar categoría"
            parentId != null -> "Nueva subcategoría"
            else -> "Nueva categoría"
        }
        formView.findViewById<View>(R.id.llCategoryForm).visibility = View.VISIBLE
        formView.findViewById<View>(R.id.llLinkForm).visibility = View.GONE
        formView.findViewById<EditText>(R.id.etCategoryName).setText(editing?.name ?: "")
        buildColorSwatches()
        hidePanel()
        showForm()
    }

    private fun showLinkForm(subcategoryId: Long) {
        formMode = FormMode.Link(subcategoryId)
        formView.findViewById<TextView>(R.id.tvFormTitle).text = "Nuevo link"
        formView.findViewById<View>(R.id.llCategoryForm).visibility = View.GONE
        formView.findViewById<View>(R.id.llLinkForm).visibility = View.VISIBLE
        formView.findViewById<EditText>(R.id.etUrl).setText("")
        formView.findViewById<EditText>(R.id.etTitle).setText("")
        hidePanel()
        showForm()
    }

    private fun showForm() {
        if (!formAdded) {
            try {
                windowManager.addView(formView, formParams)
                formAdded = true
            } catch (e: Throwable) {
                android.util.Log.e("BubbleService", "Fallo al mostrar el formulario", e)
                writeCrashToFile(e)
                Toast.makeText(this, "Error guardado en crash.txt", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideForm() {
        if (formAdded) {
            runCatching { windowManager.removeView(formView) }
            formAdded = false
        }
        showPanel()
    }

    private fun onFormSave() {
        when (val mode = formMode) {
            is FormMode.CategoryForm -> {
                val name = formView.findViewById<EditText>(R.id.etCategoryName).text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Escribe un nombre", Toast.LENGTH_SHORT).show()
                    return
                }
                val editingId = mode.editingId
                serviceScope.launch {
                    if (editingId != null) {
                        db.categoryDao().updateCategory(editingId, name, selectedColor)
                    } else {
                        val newId = db.categoryDao().insert(
                            Category(name = name, color = selectedColor, parentId = mode.parentId)
                        )
                        // Si se creó una categoría horizontal nueva, la seleccionamos automáticamente.
                        if (mode.parentId == null) {
                            selectTopLevel(newId, forceReload = true)
                        }
                    }
                }
                hideForm()
            }
            is FormMode.Link -> {
                val url = formView.findViewById<EditText>(R.id.etUrl).text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "Pega una URL", Toast.LENGTH_SHORT).show()
                    return
                }
                val title = formView.findViewById<EditText>(R.id.etTitle).text.toString().trim()
                    .ifEmpty { url }
                serviceScope.launch {
                    db.linkDao().insert(LinkItem(categoryId = mode.subcategoryId, url = url, title = title))
                }
                Toast.makeText(this, "Link guardado ✅", Toast.LENGTH_SHORT).show()
                hideForm()
            }
        }
    }

    // ---------- Mostrar / ocultar panel ----------

    private fun togglePanel() {
        if (panelAdded) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (!panelAdded) {
            try {
                windowManager.addView(panelView, panelParams)
                panelAdded = true
            } catch (e: Throwable) {
                android.util.Log.e("BubbleService", "Fallo al mostrar el panel", e)
                writeCrashToFile(e)
                Toast.makeText(this, "Error guardado en crash.txt", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hidePanel() {
        if (panelAdded) {
            runCatching { windowManager.removeView(panelView) }
            panelAdded = false
        }
    }

    // ---------- Acciones ----------

    private fun showTopLevelMenu(category: CategoryWithCount) {
        val popup = PopupMenu(this, llCategoryChips)
        popup.menu.add("Editar")
        popup.menu.add("Eliminar categoría")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Editar" -> showCategoryForm(editing = category, parentId = null)
                "Eliminar categoría" -> {
                    serviceScope.launch {
                        db.categoryDao().delete(category.id)
                        if (selectedTopLevelId == category.id) {
                            selectedTopLevelId = null
                        }
                    }
                }
            }
            true
        }
        popup.show()
    }

    private fun showSubcategoryMenu(subcategory: CategoryWithCount, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Editar")
        popup.menu.add("Eliminar subcategoría")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Editar" -> showCategoryForm(editing = subcategory, parentId = selectedTopLevelId)
                "Eliminar subcategoría" -> {
                    serviceScope.launch {
                        db.categoryDao().delete(subcategory.id)
                        expandedSubIds.remove(subcategory.id)
                        linkJobs[subcategory.id]?.cancel()
                        linkJobs.remove(subcategory.id)
                        linksBySub.remove(subcategory.id)
                    }
                }
            }
            true
        }
        popup.show()
    }

    private fun onLinkChecked(link: LinkItem, checked: Boolean) {
        serviceScope.launch {
            db.linkDao().update(link.copy(checked = checked))
        }
    }

    private fun onLinkClicked(link: LinkItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", link.url))
        Toast.makeText(this, "Copiado ✅", Toast.LENGTH_SHORT).show()
    }

    private fun showLinkMenu(link: LinkItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Eliminar")
        popup.setOnMenuItemClickListener {
            serviceScope.launch { db.linkDao().delete(link.id) }
            true
        }
        popup.show()
    }

    // ---------- Ciclo de vida ----------

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::bubbleView.isInitialized) {
            runCatching { windowManager.removeView(bubbleView) }
        }
        if (panelAdded) {
            runCatching { windowManager.removeView(panelView) }
        }
        if (formAdded) {
            runCatching { windowManager.removeView(formView) }
        }
        if (closeTargetAdded) {
            runCatching { windowManager.removeView(closeTargetView) }
        }
    }
}

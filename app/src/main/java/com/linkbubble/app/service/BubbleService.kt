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
    data class CategoryForm(val editingId: String? = null, val parentId: String? = null) : FormMode()
    data class Link(val subcategoryId: String) : FormMode()
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
    private var bubbleAdded = false
    private var autoHideMode = false
    private var formAdded = false
    private var closeTargetAdded = false

    private lateinit var db: AppDatabase
    private lateinit var syncRepo: com.linkbubble.app.data.SyncRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var themedInflater: LayoutInflater

    private lateinit var subAdapter: SubcategoryAdapter
    private lateinit var topAdapter: com.linkbubble.app.ui.TopLevelChipAdapter

    // Nivel 1: categorías horizontales
    private var topLevelCategories: List<CategoryWithCount> = emptyList()
    private var selectedTopLevelId: String? = null

    // Nivel 2: subcategorías verticales de la horizontal seleccionada
    private var childCategories: List<CategoryWithCount> = emptyList()
    private var childrenJob: Job? = null
    private val expandedSubIds = mutableSetOf<String>()
    private val linksBySub = mutableMapOf<String, List<LinkItem>>()
    private val linkJobs = mutableMapOf<String, Job>()

    private var formMode: FormMode = FormMode.CategoryForm()
    private var selectedColor: String = com.linkbubble.app.ui.ColorPalette.COLORS.first()

    companion object {
        const val ACTION_SHOW_BUBBLE = "com.linkbubble.app.action.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.linkbubble.app.action.HIDE_BUBBLE"
        const val ACTION_SET_AUTO_HIDE = "com.linkbubble.app.action.SET_AUTO_HIDE"
        const val EXTRA_ENABLED = "enabled"
        const val PREFS_NAME = "linkbubble_prefs"
        const val PREF_AUTO_HIDE = "auto_hide_enabled"

        @Volatile
        var isRunning: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        try {
            isRunning = true
            autoHideMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_AUTO_HIDE, false)
            db = AppDatabase.getInstance(this)
            syncRepo = com.linkbubble.app.data.SyncRepository(db)
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

            if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) {
                serviceScope.launch {
                    runCatching { syncRepo.fullMerge() }
                }
            }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> showBubbleView()
            ACTION_HIDE_BUBBLE -> hideBubbleView()
            ACTION_SET_AUTO_HIDE -> {
                autoHideMode = intent.getBooleanExtra(EXTRA_ENABLED, false)
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_AUTO_HIDE, autoHideMode).apply()
                if (autoHideMode) hideBubbleView() else showBubbleView()
            }
        }
        return START_STICKY
    }

    private fun showBubbleView() {
        if (!::bubbleView.isInitialized) return
        if (!bubbleAdded) {
            runCatching {
                windowManager.addView(bubbleView, bubbleParams)
                bubbleAdded = true
            }
        }
    }

    private fun hideBubbleView() {
        if (bubbleAdded) {
            runCatching { windowManager.removeView(bubbleView) }
            bubbleAdded = false
            hidePanel()
        }
    }

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

        if (!autoHideMode) {
            windowManager.addView(bubbleView, bubbleParams)
            bubbleAdded = true
        }

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

        val rvTop = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvTopLevel)
        rvTop.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
        )
        topAdapter = com.linkbubble.app.ui.TopLevelChipAdapter(
            onClick = { selectTopLevel(it.id) },
            onLongClick = { showTopLevelMenu(it) },
            onAddClick = { showCategoryForm(parentId = null) }
        )
        rvTop.adapter = topAdapter
        androidx.recyclerview.widget.ItemTouchHelper(TopChipDragCallback()).attachToRecyclerView(rvTop)

        val rvSub = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSubcategories)
        rvSub.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

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
        rvSub.adapter = subAdapter
        androidx.recyclerview.widget.ItemTouchHelper(SubDragCallback()).attachToRecyclerView(rvSub)

        panelView.findViewById<View>(R.id.btnClosePanel).setOnClickListener {
            hidePanel()
        }
    }

    // ---------- Arrastrar para reordenar: categorías horizontales ----------

    private inner class TopChipDragCallback : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ): Int {
            val item = topAdapter.getItemAt(viewHolder.bindingAdapterPosition)
            val flags = if (item is com.linkbubble.app.ui.TopChipItem.Chip) {
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            } else 0
            return makeMovementFlags(0, flags)
        }

        override fun onMove(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            target: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            val toItem = topAdapter.getItemAt(to)
            // No se puede soltar sobre el botón "+".
            if (toItem !is com.linkbubble.app.ui.TopChipItem.Chip) return false
            topAdapter.moveItem(from, to)
            return true
        }

        override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)
            val orderedIds = topAdapter.snapshotCategoryOrder()
            serviceScope.launch {
                orderedIds.forEachIndexed { index, id ->
                    db.categoryDao().updateOrder(id, index)
                    db.categoryDao().getById(id)?.let { syncRepo.pushCategory(it) }
                }
            }
        }
    }

    // ---------- Arrastrar para reordenar: subcategorías y links (mismo padre) ----------

    private inner class SubDragCallback : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ): Int {
            val item = subAdapter.getItemAt(viewHolder.bindingAdapterPosition)
            val dragFlags = if (item is SubPanelItem.Header || item is SubPanelItem.LinkRow) {
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN
            } else 0
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            target: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            val fromItem = subAdapter.getItemAt(from) ?: return false
            val toItem = subAdapter.getItemAt(to) ?: return false
            val compatible = when {
                fromItem is SubPanelItem.Header && toItem is SubPanelItem.Header -> true
                fromItem is SubPanelItem.LinkRow && toItem is SubPanelItem.LinkRow ->
                    fromItem.link.categoryId == toItem.link.categoryId
                else -> false
            }
            if (!compatible) return false
            subAdapter.moveItem(from, to)
            return true
        }

        override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)
            val snapshot = subAdapter.snapshotItems()
            serviceScope.launch {
                var headerIndex = 0
                val linkIndexByCategory = mutableMapOf<String, Int>()
                snapshot.forEach { item ->
                    when (item) {
                        is SubPanelItem.Header -> {
                            val id = item.subcategory.id
                            db.categoryDao().updateOrder(id, headerIndex)
                            db.categoryDao().getById(id)?.let { syncRepo.pushCategory(it) }
                            headerIndex++
                        }
                        is SubPanelItem.LinkRow -> {
                            val catId = item.link.categoryId
                            val idx = linkIndexByCategory.getOrDefault(catId, 0)
                            db.linkDao().updateOrder(item.link.id, idx)
                            syncRepo.pushLink(item.link.copy(orderIndex = idx))
                            linkIndexByCategory[catId] = idx + 1
                        }
                        else -> Unit
                    }
                }
            }
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

                topAdapter.submitList(categories, selectedTopLevelId)
            }
        }
    }

    private fun selectTopLevel(id: String?, forceReload: Boolean = false) {
        if (selectedTopLevelId == id && !forceReload) return
        selectedTopLevelId = id
        topAdapter.submitList(topLevelCategories, selectedTopLevelId)

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

    private fun toggleExpandSub(subcategoryId: String) {
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

        com.linkbubble.app.ui.ColorPalette.COLORS.forEach { hex ->
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

    private fun showCategoryForm(editing: CategoryWithCount? = null, parentId: String? = null) {
        formMode = FormMode.CategoryForm(editingId = editing?.id, parentId = parentId)
        selectedColor = editing?.color ?: com.linkbubble.app.ui.ColorPalette.COLORS.first()
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

    private fun showLinkForm(subcategoryId: String) {
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
                        db.categoryDao().getById(editingId)?.let { syncRepo.pushCategory(it) }
                    } else {
                        val maxOrder = if (mode.parentId == null) {
                            db.categoryDao().getMaxTopLevelOrder()
                        } else {
                            db.categoryDao().getMaxChildOrder(mode.parentId)
                        }
                        val newCategory = Category(
                            name = name, color = selectedColor, parentId = mode.parentId,
                            orderIndex = maxOrder + 1
                        )
                        db.categoryDao().insert(newCategory)
                        syncRepo.pushCategory(newCategory)
                        // Si se creó una categoría horizontal nueva, la seleccionamos automáticamente.
                        if (mode.parentId == null) {
                            selectTopLevel(newCategory.id, forceReload = true)
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
                    val maxOrder = db.linkDao().getMaxOrder(mode.subcategoryId)
                    val newLink = LinkItem(
                        categoryId = mode.subcategoryId, url = url, title = title,
                        orderIndex = maxOrder + 1
                    )
                    db.linkDao().insert(newLink)
                    syncRepo.pushLink(newLink)
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
        val popup = PopupMenu(this, panelView)
        popup.menu.add("Editar")
        popup.menu.add("Eliminar categoría")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Editar" -> showCategoryForm(editing = category, parentId = null)
                "Eliminar categoría" -> {
                    serviceScope.launch {
                        db.categoryDao().delete(category.id)
                        syncRepo.deleteCategoryRemote(category.id)
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
                        syncRepo.deleteCategoryRemote(subcategory.id)
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
            val updated = link.copy(checked = checked)
            db.linkDao().update(updated)
            syncRepo.pushLink(updated)
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
            serviceScope.launch {
                db.linkDao().delete(link.id)
                syncRepo.deleteLinkRemote(link.id)
            }
            true
        }
        popup.show()
    }

    // ---------- Ciclo de vida ----------

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        if (bubbleAdded) {
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

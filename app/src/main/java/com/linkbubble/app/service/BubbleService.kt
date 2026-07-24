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
import com.linkbubble.app.ui.PanelAdapter
import com.linkbubble.app.ui.PanelItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

private sealed class FormMode {
    object Category : FormMode()
    data class Link(val categoryId: Long) : FormMode()
}

class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var formView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var formParams: WindowManager.LayoutParams
    private var panelAdded = false
    private var formAdded = false

    private lateinit var db: AppDatabase
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var themedInflater: LayoutInflater

    private lateinit var adapter: PanelAdapter
    private var currentCategories: List<CategoryWithCount> = emptyList()
    private val expandedIds = mutableSetOf<Long>()
    private val linksByCategory = mutableMapOf<Long, List<LinkItem>>()
    private val linkJobs = mutableMapOf<Long, Job>()

    private var formMode: FormMode = FormMode.Category
    private var selectedColor: String = COLOR_PALETTE.first()

    companion object {
        val COLOR_PALETTE = listOf(
            "#F44336", "#E91E63", "#9C27B0", "#3F51B5",
            "#2196F3", "#009688", "#4CAF50", "#FF9800"
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
            observeCategories()
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
                    if (abs(dx) > 12 || abs(dy) > 12) isDragging = true
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!isDragging && elapsed < 300) {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPanel() {
        panelView = themedInflater.inflate(R.layout.layout_bubble_panel, null)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 380
        }

        val rv = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPanel)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        adapter = PanelAdapter(
            onToggleExpand = ::toggleExpand,
            onAddLinkClicked = ::showLinkForm,
            onCategoryMenuClicked = ::showCategoryMenu,
            onLinkChecked = ::onLinkChecked,
            onLinkClicked = ::onLinkClicked,
            onLinkMenuClicked = ::showLinkMenu,
            onAddCategoryClicked = ::showCategoryForm
        )
        rv.adapter = adapter

        panelView.findViewById<View>(R.id.btnClosePanel).setOnClickListener {
            hidePanel()
        }
    }

    // ---------- Formulario flotante (crear categoría / link, sin salir de la app) ----------

    private fun setupForm() {
        formView = themedInflater.inflate(R.layout.layout_bubble_form, null)

        formParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            0, // focusable: necesita foco para que el teclado escriba en los EditText
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 380
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
            val size = (28 * resources.displayMetrics.density).toInt()
            val margin = (4 * resources.displayMetrics.density).toInt()
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

    private fun showCategoryForm() {
        formMode = FormMode.Category
        selectedColor = COLOR_PALETTE.first()
        formView.findViewById<TextView>(R.id.tvFormTitle).text = "Nueva categoría"
        formView.findViewById<View>(R.id.llCategoryForm).visibility = View.VISIBLE
        formView.findViewById<View>(R.id.llLinkForm).visibility = View.GONE
        formView.findViewById<EditText>(R.id.etCategoryName).setText("")
        buildColorSwatches()
        hidePanel()
        showForm()
    }

    private fun showLinkForm(categoryId: Long) {
        formMode = FormMode.Link(categoryId)
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
                formParams.x = bubbleParams.x
                formParams.y = bubbleParams.y + 70
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
            is FormMode.Category -> {
                val name = formView.findViewById<EditText>(R.id.etCategoryName).text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Escribe un nombre", Toast.LENGTH_SHORT).show()
                    return
                }
                serviceScope.launch {
                    db.categoryDao().insert(Category(name = name, color = selectedColor))
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
                    db.linkDao().insert(LinkItem(categoryId = mode.categoryId, url = url, title = title))
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
                panelParams.x = bubbleParams.x
                panelParams.y = bubbleParams.y + 70
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

    // ---------- Datos ----------

    private fun observeCategories() {
        serviceScope.launch {
            db.categoryDao().getCategoriesWithCount().collect { categories ->
                currentCategories = categories
                rebuildList()
            }
        }
    }

    private fun toggleExpand(categoryId: Long) {
        if (expandedIds.contains(categoryId)) {
            expandedIds.remove(categoryId)
            linkJobs[categoryId]?.cancel()
            linkJobs.remove(categoryId)
            linksByCategory.remove(categoryId)
        } else {
            expandedIds.add(categoryId)
            val job = serviceScope.launch {
                db.linkDao().getByCategory(categoryId).collect { links ->
                    linksByCategory[categoryId] = links
                    rebuildList()
                }
            }
            linkJobs[categoryId] = job
        }
        rebuildList()
    }

    private fun rebuildList() {
        val items = mutableListOf<PanelItem>()
        for (cat in currentCategories) {
            val expanded = expandedIds.contains(cat.id)
            items.add(PanelItem.Header(cat, expanded))
            if (expanded) {
                val links = linksByCategory[cat.id]
                if (links.isNullOrEmpty()) {
                    items.add(PanelItem.Empty(cat.id))
                } else {
                    links.forEach { items.add(PanelItem.LinkRow(it)) }
                }
            }
        }
        items.add(PanelItem.Footer)
        adapter.submitList(items)
    }

    // ---------- Acciones ----------

    private fun showCategoryMenu(categoryId: Long, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Eliminar categoría")
        popup.setOnMenuItemClickListener {
            serviceScope.launch {
                db.categoryDao().delete(categoryId)
                expandedIds.remove(categoryId)
                linkJobs[categoryId]?.cancel()
                linkJobs.remove(categoryId)
                linksByCategory.remove(categoryId)
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
    }
}

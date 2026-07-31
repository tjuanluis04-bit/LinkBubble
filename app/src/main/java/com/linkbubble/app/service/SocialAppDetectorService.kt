package com.linkbubble.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Solo recibe eventos de cambio de ventana (typeWindowStateChanged) de los paquetes listados
 * en res/xml/accessibility_service_config.xml (Facebook, Instagram, X). No lee texto ni
 * contenido de pantalla — canRetrieveWindowContent está en false.
 */
class SocialAppDetectorService : AccessibilityService() {

    companion object {
        val TARGET_PACKAGES = setOf(
            "com.facebook.katana",
            "com.instagram.android",
            "com.twitter.android"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!BubbleService.isRunning) return // no molestar si el usuario no activó la burbuja

        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in TARGET_PACKAGES) return

        runCatching {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW_BUBBLE))
        }
    }

    override fun onInterrupt() {}
}

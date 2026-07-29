package com.linkbubble.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Sincronización simple con Firestore:
 * - Cada cambio local (crear/editar/borrar) se refleja en la nube al toque, si hay sesión iniciada.
 * - Al iniciar sesión (o al abrir la burbuja ya logueado) se hace una fusión: lo que está en la nube
 *   y no está localmente se agrega/actualiza local, y todo lo local se sube también.
 * Esto es un respaldo/restauración entre dispositivos, no una sincronización en tiempo real con
 * resolución de conflictos: si el mismo dato se edita offline en dos dispositivos a la vez, gana
 * el último que sincroniza.
 */
class SyncRepository(private val db: AppDatabase) {

    private val firestore = FirebaseFirestore.getInstance()

    private fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private fun categoriesRef(uid: String) =
        firestore.collection("users").document(uid).collection("categories")

    private fun linksRef(uid: String) =
        firestore.collection("users").document(uid).collection("links")

    // ---------- Subir un solo cambio (se llama después de cada escritura local) ----------

    fun pushCategory(category: Category) {
        val uid = uid() ?: return
        val data = mapOf(
            "name" to category.name,
            "color" to category.color,
            "parentId" to category.parentId,
            "createdAt" to category.createdAt
        )
        categoriesRef(uid).document(category.id).set(data)
    }

    fun pushLink(link: LinkItem) {
        val uid = uid() ?: return
        val data = mapOf(
            "categoryId" to link.categoryId,
            "url" to link.url,
            "title" to link.title,
            "checked" to link.checked,
            "createdAt" to link.createdAt
        )
        linksRef(uid).document(link.id).set(data)
    }

    fun deleteCategoryRemote(categoryId: String) {
        val uid = uid() ?: return
        categoriesRef(uid).document(categoryId).delete()
        // Además borra en cascada: subcategorías de esa categoría y los links de todo lo anterior.
        categoriesRef(uid).whereEqualTo("parentId", categoryId).get()
            .addOnSuccessListener { children ->
                children.documents.forEach { it.reference.delete() }
            }
        linksRef(uid).whereEqualTo("categoryId", categoryId).get()
            .addOnSuccessListener { links ->
                links.documents.forEach { it.reference.delete() }
            }
    }

    fun deleteLinkRemote(linkId: String) {
        val uid = uid() ?: return
        linksRef(uid).document(linkId).delete()
    }

    // ---------- Fusión completa (subir todo lo local + bajar todo lo remoto) ----------

    suspend fun fullMerge() {
        val uid = uid() ?: return

        // 1) Subir todo lo local a la nube.
        val localCategories = db.categoryDao().getAllOnce()
        val localLinks = db.linkDao().getAllOnce()
        localCategories.forEach { pushCategory(it) }
        localLinks.forEach { pushLink(it) }

        // 2) Bajar todo lo remoto y agregarlo/actualizarlo localmente (no borra nada local).
        val remoteCategoriesSnap = categoriesRef(uid).get().await()
        val remoteCategories = remoteCategoriesSnap.documents.mapNotNull { doc ->
            val name = doc.getString("name") ?: return@mapNotNull null
            Category(
                id = doc.id,
                name = name,
                color = doc.getString("color") ?: "#6200EE",
                parentId = doc.getString("parentId"),
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
            )
        }
        // Primero las categorías padre (parentId null) para que las hijas encuentren su FK al insertar.
        remoteCategories.filter { it.parentId == null }.forEach { db.categoryDao().insert(it) }
        remoteCategories.filter { it.parentId != null }.forEach { db.categoryDao().insert(it) }

        val remoteLinksSnap = linksRef(uid).get().await()
        val remoteLinks = remoteLinksSnap.documents.mapNotNull { doc ->
            val categoryId = doc.getString("categoryId") ?: return@mapNotNull null
            val url = doc.getString("url") ?: return@mapNotNull null
            LinkItem(
                id = doc.id,
                categoryId = categoryId,
                url = url,
                title = doc.getString("title") ?: url,
                checked = doc.getBoolean("checked") ?: false,
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
            )
        }
        remoteLinks.forEach { db.linkDao().insert(it) }
    }
}

# LinkBubble

App Android nativa (Kotlin) que muestra un **icono flotante** que te acompaña por encima de cualquier app. Al tocarlo se abre un panel tipo acordeón:

```
Categoría (5) (+) [⋮]
   [ ✅ ] enlace... (tocar copia, ⋮ borra)
   [   ] enlace...
Otra categoría (2) (+) [⋮]
+ Nueva categoría
```

- Tocar el nombre de una categoría la expande/colapsa.
- El **+** de la categoría abre el diálogo para añadir un link a esa categoría.
- El **⋮** de la categoría la elimina (y sus links).
- La casilla marca/desmarca el link.
- Tocar el texto del link lo **copia al portapapeles**.
- El **⋮** del link lo elimina.
- También puedes compartir una URL desde el navegador con "Compartir → LinkBubble" y quedará precargada.

## Estructura del proyecto

```
LinkBubble/
├── app/
│   ├── src/main/java/com/linkbubble/app/
│   │   ├── MainActivity.kt          -> pantalla para dar permiso e iniciar/parar la burbuja
│   │   ├── data/                    -> Room (Category, LinkItem, DAOs, DB)
│   │   ├── service/BubbleService.kt -> el corazón: burbuja + panel flotantes
│   │   └── ui/                      -> adaptador del panel + pantallas para añadir categoría/link
│   └── src/main/res/                -> layouts, colores, iconos
└── .github/workflows/build.yml      -> compila el APK automáticamente
```

## Cómo compilar el APK (sin instalar nada en tu PC)

1. Crea un repositorio nuevo en GitHub (puede ser privado).
2. Sube esta carpeta tal cual al repo:
   ```
   cd LinkBubble
   git init
   git add .
   git commit -m "LinkBubble inicial"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/TU_REPO.git
   git push -u origin main
   ```
3. Entra a la pestaña **Actions** de tu repo en GitHub. El workflow "Build APK" se ejecuta solo al hacer push a `main`.
4. Cuando termine (icono verde ✅), entra al run finalizado y descarga el artefacto **LinkBubble-debug-apk** al final de la página. Es un .zip que contiene el `.apk`.
5. Pasa el APK a tu celular (Drive, cable, Telegram...) e instálalo (activa "orígenes desconocidos" si te lo pide).

## Primer uso en el celular

1. Abre la app → botón **"Conceder permiso de superposición"** → actívalo para LinkBubble.
   - Alternativa con Shizuku/aShell, sin tocar Ajustes:
     ```
     adb shell appops set com.linkbubble.app SYSTEM_ALERT_WINDOW allow
     ```
2. Botón **"Iniciar burbuja"** → aparece el icono flotante (arrástralo donde quieras).
3. Toca el icono para abrir/cerrar el panel. Crea tu primera categoría con "+ Nueva categoría" y luego añade links con el "+" de esa categoría.

## Notas técnicas

- `minSdk 26` (Android 8+), `compileSdk`/`targetSdk 34`.
- Los datos se guardan localmente con Room (SQLite), no hay servidor ni internet involucrado.
- El servicio corre en primer plano (notificación fija de baja prioridad) para que Android no lo mate.
- Este proyecto no se compiló ni probó en un dispositivo real desde este entorno (no hay Android SDK aquí); es muy probable que compile bien en GitHub Actions, pero si el log de Actions marca algún error, pégamelo y lo corregimos.

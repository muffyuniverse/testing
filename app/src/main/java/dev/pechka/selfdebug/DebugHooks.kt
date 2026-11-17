package dev.pechka.selfdebug

import android.content.Context
import org.json.JSONObject

/**
 * App-specific RPC methods on top of the generated Pechka debug provider.
 * Registered once from MainActivity; handlers run on the binder thread, so blocking work is fine.
 */
object DebugHooks {

    @Volatile
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext

        PechkaDebugProvider.register(
            "app.version",
            "Version name, code, last update status and whether this app may install packages",
        ) { _, _ ->
            JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("lastStatus", Updater.lastStatus())
                .put("lastCheck", Updater.lastCheck())
                .put("manifestUrl", Updater.manifestUrl(app))
                .put("canInstall", app.packageManager.canRequestPackageInstalls())
        }

        PechkaDebugProvider.register(
            "app.check_update",
            "Fetch the update manifest and report the decision; params: {install:true} to also install",
        ) { params, _ ->
            Updater.checkBlocking(app, params.optBoolean("install", false))
        }

        PechkaDebugProvider.register(
            "app.install_update",
            "Download the newest APK from the manifest and open the system installer",
        ) { _, _ ->
            Updater.downloadAndInstallBlocking(app)
        }

        PechkaDebugProvider.register(
            "app.install_downloaded",
            "Open the system installer for an APK that was already downloaded",
        ) { _, _ ->
            Updater.installDownloaded(app)
        }

        PechkaDebugProvider.register(
            "app.set_manifest_url",
            "Point the updater at another manifest URL; params: {url}",
        ) { params, _ ->
            val url = params.optString("url", "")
            if (url.isNotEmpty()) Updater.setManifestUrl(app, url)
            JSONObject().put("manifestUrl", Updater.manifestUrl(app))
        }
    }
}

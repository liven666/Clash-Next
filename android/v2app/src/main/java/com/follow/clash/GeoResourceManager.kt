package com.follow.clash

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

object GeoResourceManager {
    data class Resource(val fileName: String, val expectedSize: Long)

    val resources = listOf(
        Resource("GEOIP.metadb", 9_643_689L),
        Resource("ASN.mmdb", 10_789_175L),
        Resource("GEOIP.dat", 20_512_850L),
        Resource("GEOSITE.dat", 4_433_793L),
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installLock = Any()
    private lateinit var context: Context

    fun init(context: Context) {
        if (!::context.isInitialized) {
            this.context = context.applicationContext
        }
    }

    fun prepareBundled() {
        worker.execute {
            runCatching { ensureInstalled() }
        }
    }

    fun existingCount(): Int = resources.count {
        File(ServiceController.geoDirectory(), it.fileName).length() == it.expectedSize
    }

    fun ensureInstalled(force: Boolean = false) {
        check(::context.isInitialized) { "GeoResourceManager 尚未初始化" }
        synchronized(installLock) {
            for (resource in resources) {
                install(resource, force)
            }
        }
    }

    fun restoreAll(
        onProgress: (String, Int, Int) -> Unit,
        onComplete: (Boolean, String) -> Unit,
    ) {
        worker.execute {
            runCatching {
                synchronized(installLock) {
                    resources.forEachIndexed { index, resource ->
                        mainHandler.post {
                            onProgress(resource.fileName, index + 1, resources.size)
                        }
                        install(resource, force = true)
                    }
                }
            }.onSuccess {
                mainHandler.post { onComplete(true, "内置 Geo 资源已恢复") }
            }.onFailure {
                mainHandler.post { onComplete(false, it.message ?: "内置资源恢复失败") }
            }
        }
    }

    private fun install(resource: Resource, force: Boolean) {
        val destination = File(ServiceController.geoDirectory(), resource.fileName)
        if (!force && destination.length() == resource.expectedSize) return

        val temp = File(destination.parentFile, "${resource.fileName}.bundled")
        context.assets.open(resource.fileName).use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
        }
        require(temp.length() == resource.expectedSize) {
            "${resource.fileName} 内置资源校验失败"
        }
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }
}

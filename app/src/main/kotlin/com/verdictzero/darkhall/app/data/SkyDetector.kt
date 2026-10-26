package com.verdictzero.darkhall.app.data

import android.content.Context
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.sky.SkyMask
import com.verdictzero.darkhall.core.sky.SkyModel
import java.io.File
import java.security.MessageDigest

/**
 * Finds each photo's sky with the bundled model (loaded once) and remembers the result on disk,
 * so the network runs only the first time a photo's sky is needed.
 */
object SkyDetector {
    @Volatile private var model: SkyModel? = null

    private fun model(context: Context): SkyModel = model ?: synchronized(this) {
        model ?: context.assets.open("sky/deeplab_mnv2_ade20k.bin").use { SkyModel.read(it) }.also { model = it }
    }

    private fun cacheFile(context: Context, key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "sky").apply { mkdirs() }, "$hash.sky")
    }

    /** The photo's sky if it has been found before. */
    fun cached(context: Context, key: String): SkyMask? =
        runCatching { cacheFile(context, key).takeIf { it.exists() }?.readBytes()?.let { SkyMask.fromBytes(it) } }.getOrNull()

    /**
     * Finds the sky in the photo whose preview is [pv] (any size, whole crop): renders it at
     * the camera's own settings ([defaults]) without geometry, as the model expects a plain
     * photo, runs the model and caches the result under [key].
     */
    fun detect(context: Context, key: String, pv: LinearImage, ctx: RenderContext, defaults: DevelopParams, meta: RawMetadata): SkyMask {
        cached(context, key)?.let { return it }
        val dev = Developer()
        val argb = dev.render(pv, ctx, defaults, Frame(0, 0, pv.width.toFloat() / meta.crop.width))
        dev.release()
        val mask = SkyMask.detect(model(context), argb, pv.width, pv.height, meta.orientation)
        runCatching { cacheFile(context, key).writeBytes(mask.toBytes()) }
        return mask
    }
}

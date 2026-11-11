/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Animated PNG (APNG) support. Android decodes PNG but plays no APNG animation, and desktop Firefox's
 * "animated" themes are APNG. [ApngDecoder.decode] turns each frame into a standalone PNG for
 * [BitmapFactory] and composites it per the APNG `dispose_op` / `blend_op` rules into full frames;
 * [ApngDrawable] plays them with their delays, forever (desktop ignores `num_plays` for themes too).
 */
internal object ApngDecoder {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val DISPOSE_BACKGROUND = 1
    private const val DISPOSE_PREVIOUS = 2
    private const val BLEND_OVER = 1

    /** Frame memory cap; larger animations play their first frame only. */
    private const val MAX_FRAME_BYTES = 48L * 1024 * 1024
    private const val MIN_DELAY_MS = 20
    private const val DEFAULT_DELAY_MS = 100

    class Frames(val bitmaps: List<Bitmap>, val delaysMs: List<Int>)

    private class Chunk(val type: String, val data: ByteArray)

    private class FrameControl(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val delayMs: Int,
        val dispose: Int,
        val blend: Int,
    )

    fun isApng(bytes: ByteArray): Boolean {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) return false
        return chunks(bytes).takeWhile { it.type != "IDAT" }.any { it.type == "acTL" }
    }

    /** Full frames of an APNG, or null when it is not one or cannot be decoded within the memory cap. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun decode(bytes: ByteArray): Frames? {
        if (!isApng(bytes)) return null
        val all = chunks(bytes)
        val ihdr = all.first { it.type == "IHDR" }.data
        val width = ByteBuffer.wrap(ihdr, 0, 8).int
        val height = ByteBuffer.wrap(ihdr, 4, 4).int
        // Chunks a frame needs to decode on its own: everything ancillary before the image data.
        val shared = all.takeWhile { it.type != "IDAT" && it.type != "fcTL" }
            .filter { it.type != "IHDR" && it.type != "acTL" }

        val controls = ArrayList<FrameControl>()
        val datas = ArrayList<ByteArrayOutputStream>()
        var current: ByteArrayOutputStream? = null
        for (chunk in all) {
            when (chunk.type) {
                "fcTL" -> {
                    val b = ByteBuffer.wrap(chunk.data)
                    b.position(4) // skip the sequence number
                    val w = b.int
                    val h = b.int
                    val x = b.int
                    val y = b.int
                    val num = b.short.toInt() and 0xFFFF
                    val den = (b.short.toInt() and 0xFFFF).let { if (it == 0) 100 else it }
                    val dispose = b.get().toInt()
                    val blend = b.get().toInt()
                    val delay = if (num == 0) DEFAULT_DELAY_MS else maxOf(MIN_DELAY_MS, num * 1000 / den)
                    controls.add(FrameControl(w, h, x, y, delay, dispose, blend))
                    current = ByteArrayOutputStream().also { datas.add(it) }
                }
                // IDAT belongs to the first frame only when an fcTL came before it.
                "IDAT" -> current?.write(chunk.data)
                "fdAT" -> current?.write(chunk.data, 4, chunk.data.size - 4)
            }
        }
        if (controls.isEmpty() || controls.size != datas.size) return null
        if (controls.size.toLong() * width * height * 4 > MAX_FRAME_BYTES) return null

        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val source = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val frames = ArrayList<Bitmap>()
        for ((i, fc) in controls.withIndex()) {
            val png = framePng(ihdr, fc.width, fc.height, shared, datas[i].toByteArray())
            val piece = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
            val previous = if (fc.dispose == DISPOSE_PREVIOUS) canvasBitmap.copy(Bitmap.Config.ARGB_8888, true) else null
            canvas.drawBitmap(piece, fc.x.toFloat(), fc.y.toFloat(), if (fc.blend == BLEND_OVER) null else source)
            piece.recycle()
            frames.add(canvasBitmap.copy(Bitmap.Config.ARGB_8888, false))
            when (fc.dispose) {
                DISPOSE_BACKGROUND -> canvas.drawRect(
                    Rect(fc.x, fc.y, fc.x + fc.width, fc.y + fc.height),
                    clear,
                )
                DISPOSE_PREVIOUS -> previous?.let {
                    canvas.drawBitmap(it, 0f, 0f, source)
                    it.recycle()
                }
            }
        }
        canvasBitmap.recycle()
        return Frames(frames, controls.map { it.delayMs })
    }

    private fun chunks(bytes: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var i = 8
        while (i + 8 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, i, 4).int
            if (length < 0 || i + 12 + length > bytes.size) break
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            out.add(Chunk(type, bytes.copyOfRange(i + 8, i + 8 + length)))
            i += 12 + length
            if (type == "IEND") break
        }
        return out
    }

    /** A standalone PNG for one frame: the stream's header with the frame's size, shared chunks, its data. */
    private fun framePng(ihdr: ByteArray, w: Int, h: Int, shared: List<Chunk>, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val header = ihdr.copyOf()
        ByteBuffer.wrap(header).putInt(0, w).putInt(4, h)
        writeChunk(out, "IHDR", header)
        for (c in shared) writeChunk(out, c.type, c.data)
        writeChunk(out, "IDAT", data)
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}

/** Plays [ApngDecoder.Frames] by elapsed time, scheduling its next frame through its callback. */
internal class ApngDrawable(private val frames: ApngDecoder.Frames) : Drawable(), Animatable, Runnable {
    private val totalMs = frames.delaysMs.sum().coerceAtLeast(1)
    private val start = SystemClock.uptimeMillis()
    private var running = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun getIntrinsicWidth() = frames.bitmaps.first().width

    override fun getIntrinsicHeight() = frames.bitmaps.first().height

    private fun frameAt(elapsed: Long): Pair<Int, Long> {
        var t = (elapsed % totalMs).toInt()
        for ((i, d) in frames.delaysMs.withIndex()) {
            if (t < d) return i to (d - t).toLong()
            t -= d
        }
        return 0 to frames.delaysMs.first().toLong()
    }

    override fun draw(canvas: Canvas) {
        val (index, untilNext) = frameAt(SystemClock.uptimeMillis() - start)
        canvas.drawBitmap(frames.bitmaps[index], null, bounds, paint)
        if (running) {
            unscheduleSelf(this)
            scheduleSelf(this, SystemClock.uptimeMillis() + untilNext)
        }
    }

    override fun run() = invalidateSelf()

    override fun start() {
        running = true
        invalidateSelf()
    }

    override fun stop() {
        running = false
        unscheduleSelf(this)
    }

    override fun isRunning() = running

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

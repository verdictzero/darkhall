package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.raw.OrfParser
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Builds minimal synthetic ORF containers to make sure every MakerNote header
 * variant is recognised: "OLYMPUS\0II" (TG-6 era) and "OM SYSTEM\0\0\0II"
 * (OM Digital Solutions bodies such as the TG-7).
 */
class MakerNoteHeaderTest {

    private class Builder {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        fun bytes(b: ByteArray) = out.write(b)
        val size get() = out.size()
        fun pad(to: Int) { while (size < to) u8(0) }
    }

    private class Entry(val tag: Int, val type: Int, val count: Int, val value: Long)

    private fun Builder.ifd(entries: List<Entry>) {
        u16(entries.size)
        for (e in entries) { u16(e.tag); u16(e.type); u32(e.count.toLong()); u32(e.value) }
        u32(0)
    }

    /** Short values stored inline in the 4-byte value field (little endian). */
    private fun inlineShorts(a: Int, b: Int = 0) = (a.toLong() and 0xFFFF) or ((b.toLong() and 0xFFFF) shl 16)

    private fun buildOrf(omSystemHeader: Boolean): ByteArray {
        val b = Builder()
        b.bytes("IIRO".toByteArray()); b.u32(8)
        // IFD0 at 8: width, height, bps, compression, make, model, strip offset, strip bytes, exif pointer
        val makeOff = 200; val modelOff = 232; val exifOff = 300; val mnOff = 400
        b.ifd(listOf(
            Entry(0x0100, 4, 1, 4040), Entry(0x0101, 4, 1, 3016), Entry(0x0102, 3, 1, 16), Entry(0x0103, 3, 1, 1),
            Entry(0x010F, 2, 21, makeOff.toLong()), Entry(0x0110, 2, 5, modelOff.toLong()),
            Entry(0x0111, 4, 1, 100000), Entry(0x0117, 4, 1, 12000000), Entry(0x8769, 4, 1, exifOff.toLong()),
        ))
        b.pad(makeOff); b.bytes("OM Digital Solutions\u0000".toByteArray())
        b.pad(modelOff); b.bytes("TG-7\u0000".toByteArray())
        b.pad(exifOff)
        val mnLen = 600
        b.ifd(listOf(Entry(0x8827, 3, 1, 1600), Entry(0x927C, 7, mnLen, mnOff.toLong())))
        b.pad(mnOff)
        // MakerNote (all offsets relative to mnOff)
        val sub = 100 // ImageProcessing sub-IFD, relative
        val cs = 200  // CameraSettings sub-IFD, relative
        if (omSystemHeader) {
            b.bytes("OM SYSTEM\u0000\u0000\u0000II".toByteArray()); b.u16(4) // IFD at +16
        } else {
            b.bytes("OLYMPUS\u0000II".toByteArray()); b.u16(3) // IFD at +12
        }
        b.ifd(listOf(Entry(0x2020, 13, 1, cs.toLong()), Entry(0x2040, 13, 1, sub.toLong())))
        b.pad(mnOff + sub)
        val wbOff = 300; val blackOff = 320
        b.ifd(listOf(
            Entry(0x0100, 3, 4, wbOff.toLong()), Entry(0x0600, 3, 4, blackOff.toLong()),
            Entry(0x0611, 3, 1, inlineShorts(12)),
            Entry(0x0612, 3, 1, inlineShorts(8)), Entry(0x0613, 3, 1, inlineShorts(8)),
            Entry(0x0614, 4, 1, 4000), Entry(0x0615, 4, 1, 3000),
        ))
        b.pad(mnOff + cs)
        b.ifd(listOf(Entry(0x0100, 4, 1, 1), Entry(0x0101, 4, 1, 500), Entry(0x0102, 4, 1, 4)))
        b.pad(mnOff + wbOff); b.u16(512); b.u16(384); b.u16(256); b.u16(256)
        b.pad(mnOff + blackOff); b.u16(255); b.u16(254); b.u16(253); b.u16(252)
        b.pad(mnOff + 500); b.u8(0xFF); b.u8(0xD8); b.u8(0xFF); b.u8(0xD9)
        b.pad(mnOff + mnLen)
        b.pad(200000)
        return b.out.toByteArray()
    }

    private fun check(omSystem: Boolean) {
        val meta = OrfParser.parse(buildOrf(omSystem))
        assertEquals("TG-7", meta.model)
        assertEquals(1600, meta.iso)
        assertEquals(2.0f, meta.cameraWb[0], 1e-5f)
        assertEquals(1.5f, meta.cameraWb[2], 1e-5f)
        // file order R, Gr, Gb, B -> positions of the GRBG cell: Gr, R, B, Gb
        assertEquals(listOf(254, 255, 252, 253), meta.blackLevels.toList())
        assertEquals(4000, meta.crop.width); assertEquals(8, meta.crop.left)
        assertEquals(400L + 500, meta.previewOffset)
        assertEquals(4, meta.previewLength)
        assertNotNull(meta)
    }

    @Test fun olympusHeader() = check(omSystem = false)
    @Test fun omSystemHeader() = check(omSystem = true)
}

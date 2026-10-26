package com.verdictzero.darkhall.core.export

import java.io.BufferedOutputStream
import java.io.File

/**
 * Truevision TGA, 24-bit, run-length encoded, top-left origin, with the TGA 2.0 footer.
 * TGA has no place for camera metadata.
 */
object TgaWriter {

    fun write(src: RowSource, file: File, progress: ((Float) -> Unit)? = null) {
        require(src.bits == 8) { "TGA is 8 bits per channel" }
        require(src.width <= 65535 && src.height <= 65535) { "too large for TGA" }
        BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
            val header = ByteArray(18)
            header[2] = 10 // run-length encoded true colour
            header[12] = src.width.toByte(); header[13] = (src.width shr 8).toByte()
            header[14] = src.height.toByte(); header[15] = (src.height shr 8).toByte()
            header[16] = 24
            header[17] = 0x20 // origin top left
            out.write(header)
            val row = IntArray(3 * src.width)
            val px = IntArray(src.width)
            val packet = ByteArray(1 + 128 * 3)
            for (y in 0 until src.height) {
                src.row(y, row)
                for (x in 0 until src.width) px[x] = (row[3 * x] shl 16) or (row[3 * x + 1] shl 8) or row[3 * x + 2]
                // Packets never cross a row: runs of 2+ equal pixels, else literal stretches.
                var x = 0
                while (x < src.width) {
                    var run = 1
                    while (x + run < src.width && run < 128 && px[x + run] == px[x]) run++
                    if (run >= 2) {
                        out.write(0x80 or (run - 1))
                        putBgr(out, px[x])
                        x += run
                    } else {
                        var lit = 1
                        while (x + lit < src.width && lit < 128 &&
                            !(x + lit + 1 < src.width && px[x + lit] == px[x + lit + 1])
                        ) lit++
                        packet[0] = (lit - 1).toByte()
                        for (k in 0 until lit) {
                            val p = px[x + k]
                            packet[1 + 3 * k] = p.toByte(); packet[2 + 3 * k] = (p shr 8).toByte(); packet[3 + 3 * k] = (p shr 16).toByte()
                        }
                        out.write(packet, 0, 1 + 3 * lit)
                        x += lit
                    }
                }
                if (progress != null && y % 64 == 0) progress(y.toFloat() / src.height)
            }
            // TGA 2.0 footer: no extension or developer area.
            out.write(ByteArray(8))
            out.write("TRUEVISION-XFILE.".toByteArray(Charsets.US_ASCII))
            out.write(0)
        }
    }

    private fun putBgr(out: BufferedOutputStream, p: Int) {
        out.write(p and 0xFF); out.write((p shr 8) and 0xFF); out.write((p shr 16) and 0xFF)
    }
}

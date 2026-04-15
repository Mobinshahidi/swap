package com.lanshare.app

import android.graphics.Bitmap

object QrCodeGenerator {
    private const val VERSION = 4
    private const val SIZE = 33
    private const val DATA_CODEWORDS = 64
    private const val EC_CODEWORDS_PER_BLOCK = 18
    private const val BLOCKS = 2

    fun render(content: String, targetSizePx: Int): Bitmap {
        val data = encodeData(content)
        val matrix = buildMatrix(data)
        return renderBitmap(matrix, targetSizePx)
    }

    private fun encodeData(content: String): ByteArray {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 62) { "Content too long for QR version 4-M" }

        val bits = mutableListOf<Int>()
        appendBits(bits, 0b0100, 4)
        appendBits(bits, bytes.size, 8)
        for (b in bytes) appendBits(bits, b.toInt() and 0xFF, 8)
        val maxBits = DATA_CODEWORDS * 8
        val terminator = minOf(4, maxBits - bits.size)
        repeat(terminator) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)

        val out = ArrayList<Int>(DATA_CODEWORDS)
        var i = 0
        while (i < bits.size) {
            var v = 0
            repeat(8) { j -> v = (v shl 1) or bits[i + j] }
            out.add(v)
            i += 8
        }
        var pad = true
        while (out.size < DATA_CODEWORDS) {
            out.add(if (pad) 0xEC else 0x11)
            pad = !pad
        }

        val b1 = out.subList(0, 32).toIntArray()
        val b2 = out.subList(32, 64).toIntArray()
        val e1 = reedSolomon(b1, EC_CODEWORDS_PER_BLOCK)
        val e2 = reedSolomon(b2, EC_CODEWORDS_PER_BLOCK)

        val stream = ArrayList<Int>(100)
        for (k in 0 until 32) {
            stream.add(b1[k])
            stream.add(b2[k])
        }
        for (k in 0 until EC_CODEWORDS_PER_BLOCK) {
            stream.add(e1[k])
            stream.add(e2[k])
        }
        return stream.map { it.toByte() }.toByteArray()
    }

    private fun buildMatrix(codewords: ByteArray): Array<IntArray> {
        val m = Array(SIZE) { IntArray(SIZE) { -1 } }
        val reserved = Array(SIZE) { BooleanArray(SIZE) }

        placeFinder(m, reserved, 0, 0)
        placeFinder(m, reserved, SIZE - 7, 0)
        placeFinder(m, reserved, 0, SIZE - 7)
        placeSeparators(m, reserved)

        for (i in 8 until SIZE - 8) {
            set(m, reserved, 6, i, (i % 2 == 0).toInt())
            set(m, reserved, i, 6, (i % 2 == 0).toInt())
        }

        placeAlignment(m, reserved, 26, 26)
        set(m, reserved, 8, 25, 1)

        reserveFormatAreas(reserved)
        placeData(m, reserved, codewords)

        applyMask0(m, reserved)
        placeFormatBits(m, reserved)
        return m
    }

    private fun placeFinder(m: Array<IntArray>, r: Array<BooleanArray>, x: Int, y: Int) {
        for (dy in 0 until 7) {
            for (dx in 0 until 7) {
                val xx = x + dx
                val yy = y + dy
                val dark = dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)
                set(m, r, xx, yy, dark.toInt())
            }
        }
    }

    private fun placeSeparators(m: Array<IntArray>, r: Array<BooleanArray>) {
        for (i in 0..7) {
            set(m, r, 7, i, 0)
            set(m, r, i, 7, 0)

            set(m, r, SIZE - 8, i, 0)
            set(m, r, SIZE - 1 - i, 7, 0)

            set(m, r, 7, SIZE - 1 - i, 0)
            set(m, r, i, SIZE - 8, 0)
        }
    }

    private fun placeAlignment(m: Array<IntArray>, r: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val x = cx + dx
                val y = cy + dy
                val adx = kotlin.math.abs(dx)
                val ady = kotlin.math.abs(dy)
                val dark = adx == 2 || ady == 2 || (adx == 0 && ady == 0)
                set(m, r, x, y, dark.toInt())
            }
        }
    }

    private fun reserveFormatAreas(r: Array<BooleanArray>) {
        for (i in 0..8) {
            if (i != 6) {
                r[8][i] = true
                r[i][8] = true
            }
        }
        for (i in 0..7) r[SIZE - 1 - i][8] = true
        for (i in 0..7) r[8][SIZE - 1 - i] = true
        r[8][8] = true
    }

    private fun placeData(m: Array<IntArray>, r: Array<BooleanArray>, codewords: ByteArray) {
        val bits = mutableListOf<Int>()
        codewords.forEach { b ->
            val v = b.toInt() and 0xFF
            for (i in 7 downTo 0) bits.add((v ushr i) and 1)
        }

        var bitIndex = 0
        var col = SIZE - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            val rows = if (upward) (SIZE - 1 downTo 0) else (0 until SIZE)
            for (row in rows) {
                for (c in 0..1) {
                    val x = col - c
                    val y = row
                    if (r[x][y]) continue
                    val bit = if (bitIndex < bits.size) bits[bitIndex++] else 0
                    m[x][y] = bit
                }
            }
            upward = !upward
            col -= 2
        }
    }

    private fun applyMask0(m: Array<IntArray>, r: Array<BooleanArray>) {
        for (x in 0 until SIZE) {
            for (y in 0 until SIZE) {
                if (r[x][y]) continue
                if ((x + y) % 2 == 0) m[x][y] = m[x][y] xor 1
            }
        }
    }

    private fun placeFormatBits(m: Array<IntArray>, r: Array<BooleanArray>) {
        val fmt = 0b101010000010010
        for (i in 0..5) set(m, r, 8, i, (fmt ushr i) and 1)
        set(m, r, 8, 7, (fmt ushr 6) and 1)
        set(m, r, 8, 8, (fmt ushr 7) and 1)
        set(m, r, 7, 8, (fmt ushr 8) and 1)
        for (i in 9..14) set(m, r, 14 - i, 8, (fmt ushr i) and 1)

        for (i in 0..7) set(m, r, SIZE - 1 - i, 8, (fmt ushr i) and 1)
        for (i in 8..14) set(m, r, 8, SIZE - 15 + i, (fmt ushr i) and 1)
    }

    private fun renderBitmap(matrix: Array<IntArray>, targetSizePx: Int): Bitmap {
        val quiet = 4
        val full = SIZE + quiet * 2
        val scale = maxOf(1, targetSizePx / full)
        val bmpSize = full * scale
        val bmp = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888)

        for (y in 0 until bmpSize) {
            for (x in 0 until bmpSize) {
                bmp.setPixel(x, y, 0xFFFFFFFF.toInt())
            }
        }
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val color = if (matrix[x][y] == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                val px = (x + quiet) * scale
                val py = (y + quiet) * scale
                for (yy in 0 until scale) {
                    for (xx in 0 until scale) {
                        bmp.setPixel(px + xx, py + yy, color)
                    }
                }
            }
        }
        return bmp
    }

    private fun reedSolomon(data: IntArray, ecLen: Int): IntArray {
        val gen = rsGenerator(ecLen)
        val rem = IntArray(ecLen)
        for (d in data) {
            val factor = d xor rem[0]
            for (i in 0 until ecLen - 1) rem[i] = rem[i + 1]
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) {
                rem[i] = rem[i] xor gfMul(gen[i], factor)
            }
        }
        return rem
    }

    private fun rsGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor gfMul(poly[j], gfPow(2, i))
                next[j + 1] = next[j + 1] xor poly[j]
            }
            poly = next
        }
        return poly.copyOfRange(1, poly.size)
    }

    private fun gfPow(a: Int, e: Int): Int {
        var r = 1
        repeat(e) { r = gfMul(r, a) }
        return r
    }

    private fun gfMul(x: Int, y: Int): Int {
        var a = x
        var b = y
        var r = 0
        while (b > 0) {
            if ((b and 1) != 0) r = r xor a
            a = a shl 1
            if (a and 0x100 != 0) a = a xor 0x11D
            b = b ushr 1
        }
        return r and 0xFF
    }

    private fun appendBits(bits: MutableList<Int>, value: Int, count: Int) {
        for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
    }

    private fun set(m: Array<IntArray>, r: Array<BooleanArray>, x: Int, y: Int, v: Int) {
        if (x !in 0 until SIZE || y !in 0 until SIZE) return
        m[x][y] = v
        r[x][y] = true
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0
}

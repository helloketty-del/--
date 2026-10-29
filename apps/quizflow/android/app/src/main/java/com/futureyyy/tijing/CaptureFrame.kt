package com.futureyyy.tijing

data class CaptureFrame(
    val dataUrl: String,
    val signature: FrameSignature,
)

class FrameSignature private constructor(
    private val words: LongArray,
) {
    fun hammingDistance(other: FrameSignature): Int {
        require(words.size == other.words.size) { "FrameSignature 位数不匹配" }
        return words.indices.sumOf { index ->
            (words[index] xor other.words[index]).countOneBits()
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FrameSignature &&
            words.contentEquals(other.words)

    override fun hashCode(): Int = words.contentHashCode()

    companion object {
        const val GRID_SIZE = 32
        const val BIT_COUNT = GRID_SIZE * GRID_SIZE
        const val TOP_CROP_PERCENT = 12
        const val BOTTOM_CROP_PERCENT = 8

        fun fromBits(bits: BooleanArray): FrameSignature {
            require(bits.size == BIT_COUNT) { "FrameSignature 必须包含 $BIT_COUNT 位" }
            val words = LongArray(BIT_COUNT / Long.SIZE_BITS)
            bits.forEachIndexed { index, set ->
                if (set) {
                    val wordIndex = index / Long.SIZE_BITS
                    words[wordIndex] = words[wordIndex] or (1L shl (index % Long.SIZE_BITS))
                }
            }
            return FrameSignature(words)
        }

        fun fromLuminanceSamples(samples: IntArray): FrameSignature {
            require(samples.size == BIT_COUNT) { "亮度样本必须是 ${GRID_SIZE}x$GRID_SIZE" }
            require(samples.all { it in 0..255 }) { "亮度必须位于 0..255" }
            val sum = samples.sumOf { it.toLong() }
            return fromBits(BooleanArray(BIT_COUNT) { index ->
                samples[index].toLong() * BIT_COUNT > sum
            })
        }

        fun fromLuminancePixels(
            width: Int,
            height: Int,
            luminance: IntArray,
        ): FrameSignature {
            requirePixelBuffer(width, height, luminance.size)
            return fromLuminanceSamples(sampleCentralGrid(width, height) { x, y ->
                luminance[y * width + x].also { value ->
                    require(value in 0..255) { "亮度必须位于 0..255" }
                }
            })
        }

        fun fromArgbPixels(
            width: Int,
            height: Int,
            pixels: IntArray,
        ): FrameSignature {
            requirePixelBuffer(width, height, pixels.size)
            return fromLuminanceSamples(sampleCentralGrid(width, height) { x, y ->
                val pixel = pixels[y * width + x]
                val red = pixel ushr 16 and 0xff
                val green = pixel ushr 8 and 0xff
                val blue = pixel and 0xff
                (299 * red + 587 * green + 114 * blue) / 1000
            })
        }

        fun cropTop(height: Int): Int {
            require(height > 0) { "height 必须大于 0" }
            return (height.toLong() * TOP_CROP_PERCENT / 100L).toInt()
        }

        fun cropBottomExclusive(height: Int): Int {
            require(height > 0) { "height 必须大于 0" }
            return height - (height.toLong() * BOTTOM_CROP_PERCENT / 100L).toInt()
        }

        private fun sampleCentralGrid(
            width: Int,
            height: Int,
            luminanceAt: (x: Int, y: Int) -> Int,
        ): IntArray {
            val top = cropTop(height)
            val bottom = cropBottomExclusive(height)
            val contentHeight = bottom - top
            require(contentHeight > 0) { "裁剪后的图像高度必须大于 0" }
            return IntArray(BIT_COUNT) { index ->
                val row = index / GRID_SIZE
                val column = index % GRID_SIZE
                val x = (((column * 2L + 1L) * width) / (GRID_SIZE * 2L))
                    .toInt()
                    .coerceIn(0, width - 1)
                val y = (top + ((row * 2L + 1L) * contentHeight / (GRID_SIZE * 2L)).toInt())
                    .coerceIn(top, bottom - 1)
                luminanceAt(x, y)
            }
        }

        private fun requirePixelBuffer(width: Int, height: Int, size: Int) {
            require(width > 0 && height > 0) { "图像尺寸必须大于 0" }
            val requiredSize = width.toLong() * height.toLong()
            require(requiredSize <= Int.MAX_VALUE && size >= requiredSize) { "像素数量与图像尺寸不匹配" }
        }
    }
}

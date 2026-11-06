package app.pockettavern.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only files that can move are handed to the animation decoder, at sticker size. */
class StickerAnimationTest {
    private fun webp(chunk: String, flags: Int = 0) =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + chunk.toByteArray() + ByteArray(4) + byteArrayOf(flags.toByte()) + ByteArray(11)

    @Test fun gifsAndAnimatedWebpMayMoveOtherImagesNever() {
        assertTrue(mayAnimate("GIF89a".toByteArray() + ByteArray(26)))
        assertTrue(mayAnimate("GIF87a".toByteArray()))
        assertTrue(mayAnimate(webp("VP8X", flags = 0x02)))
        assertTrue("other flags do not hide the animation bit", mayAnimate(webp("VP8X", flags = 0x12)))
        assertFalse("extended WebP without the animation bit", mayAnimate(webp("VP8X", flags = 0x10)))
        assertFalse("simple lossy WebP", mayAnimate(webp("VP8 ")))
        assertFalse("PNG", mayAnimate(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertFalse("JPEG", mayAnimate(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())))
        assertFalse("too short to tell", mayAnimate("RIFF".toByteArray()))
        assertFalse(mayAnimate(ByteArray(0)))
    }

    @Test fun animationsAreShrunkToStickerSizeButNeverEnlarged() {
        assertEquals(384 to 216, animatedTargetSize(1280, 720, 384))
        assertEquals(216 to 384, animatedTargetSize(720, 1280, 384))
        assertEquals(240 to 240, animatedTargetSize(240, 240, 384))
        assertEquals(384 to 1, animatedTargetSize(4000, 2, 384))
        assertEquals(0 to 0, animatedTargetSize(0, 0, 384))
    }
}

package app.pockettavern

import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.test.InstrumentationTestCase
import android.util.Base64
import app.pockettavern.ui.decodeAnimatedSticker
import java.io.File
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue

/** On a real Android 9+ system an animated GIF sticker plays, and a one-frame GIF stays still. */
@Suppress("DEPRECATION")
class StickerAnimationInstrumentedTest : InstrumentationTestCase() {
    private fun gif(name: String, base64: String): File =
        File(instrumentation.targetContext.cacheDir, name).apply { writeBytes(Base64.decode(base64, Base64.DEFAULT)) }

    fun testAnimatedGifPlaysAndStillGifDoesNot() {
        // Two 8×8 frames, looping, made with Pillow for this test.
        val moving = gif("moving.gif", "R0lGODlhCAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQBCgABACwAAAAACAAIAIEAAP8AAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAA7")
        val still = gif("still.gif", "R0lGODdhCAAIAIEAAP8AAAAAAAAAAAAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAA7")
        try {
            val drawable = decodeAnimatedSticker(moving)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertTrue(drawable is AnimatedImageDrawable) else assertNull(drawable)
            assertNull(decodeAnimatedSticker(still))
        } finally {
            moving.delete()
            still.delete()
        }
    }
}

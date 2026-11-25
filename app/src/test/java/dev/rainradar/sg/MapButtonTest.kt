package dev.rainradar.sg

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The round zoom and location buttons over the map must draw their icon in the middle.
 * Material's default icon-button padding and start gravity pushed it off-centre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // SDK 35 under Robolectric needs Java 21; CI runs Java 17.
class MapButtonTest {
    private lateinit var root: View
    private var density = 1f

    @Before
    fun layOutMainScreen() {
        val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        density = ctx.resources.displayMetrics.density
        root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null)
        root.measure(
            MeasureSpec.makeMeasureSpec((411 * density).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    @Test
    fun zoomInIconIsCentred() = assertCentred(R.id.zoom_in)

    @Test
    fun zoomOutIconIsCentred() = assertCentred(R.id.zoom_out)

    @Test
    fun locateIconIsCentred() = assertCentred(R.id.locate)

    private fun assertCentred(id: Int) {
        val b = root.findViewById<MaterialButton>(id)
        val name = b.resources.getResourceEntryName(id)

        val icon = b.icon
        assertNotNull("$name icon", icon)

        assertEquals("$name width", (40 * density).toInt(), b.width)
        assertEquals("$name height", (40 * density).toInt(), b.height)

        // The icon is drawn at paddingStart + its bounds, and vertically centred between the
        // top and bottom compound paddings.
        val iconCentreX = b.compoundPaddingLeft + icon.bounds.exactCenterX()
        val contentTop = b.compoundPaddingTop
        val contentBottom = b.height - b.compoundPaddingBottom
        val iconCentreY = (contentTop + contentBottom) / 2f
        assertEquals("$name icon x centre", b.width / 2f, iconCentreX, 1f)
        assertEquals("$name icon y centre", b.height / 2f, iconCentreY, 1f)
    }
}

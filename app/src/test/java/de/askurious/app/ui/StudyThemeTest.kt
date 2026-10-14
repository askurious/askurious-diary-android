package de.askurious.app.ui

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyThemeTest {
    private fun luminance(c:Color):Double {
        fun lin(v:Float)=if(v<=.04045) v/12.92 else Math.pow((v+.055)/1.055,2.4)
        return .2126*lin(c.red)+.7152*lin(c.green)+.0722*lin(c.blue)
    }
    private fun contrast(a:Color,b:Color)=(maxOf(luminance(a),luminance(b))+.05)/(minOf(luminance(a),luminance(b))+.05)
    @Test fun studyColoursCannotMakeNativeTextUnreadable() {
        for (dark in listOf(false,true)) for (surface in listOf("#ffffff","#777777","#17233f","#11291b","#f4eee2","#e6efe9")) {
            val look=JSONObject().put("colors",JSONObject().put("surface",surface).put("accent","#999999").put("text",surface))
            val palette=studyColors(de.askurious.app.data.Look.fromJson(look),dark)
            for (ink in listOf(palette.onSurface,palette.onSurfaceVariant,palette.primary,palette.error)) {
                assertTrue("$surface dark=$dark",contrast(ink,palette.surface)>=4.49)
            }
            assertTrue(contrast(palette.onPrimary,palette.primary)>=4.49)
        }
    }
}

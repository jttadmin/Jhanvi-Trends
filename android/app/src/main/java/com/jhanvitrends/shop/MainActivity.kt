package com.jhanvitrends.shop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import com.jhanvitrends.shop.data.Api
import com.jhanvitrends.shop.data.Session
import com.jhanvitrends.shop.ui.JhanviApp
import com.jhanvitrends.shop.ui.JhanviTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Demo products use generated SVG artwork; uploaded product photos are JPEG/PNG/WebP.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(SvgDecoder.Factory()) }
                .crossfade(true)
                .build(),
        )
        val session = Session(applicationContext)
        val api = Api(BuildConfig.BASE_URL, session)
        setContent {
            JhanviTheme { JhanviApp(api, session) }
        }
    }
}

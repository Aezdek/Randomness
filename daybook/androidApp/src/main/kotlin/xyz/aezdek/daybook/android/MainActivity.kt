package xyz.aezdek.daybook.android

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.aezdek.daybook.ui.DaybookApp

class MainActivity : ComponentActivity() {

    private var reduceMotion by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val daybook = (application as DaybookApplication).daybook
        setContent {
            DaybookApp(daybook, systemDark = isSystemInDarkTheme(), reduceMotion = reduceMotion)
        }
    }

    override fun onResume() {
        super.onResume()
        // "Remove animations" in Accessibility sets the animator scale to 0.
        reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

package io.github.holego.checkprint

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.github.holego.checkprint.ui.CheckPrintApp
import io.github.holego.checkprint.ui.CheckPrintTheme

/** AppCompatActivity (not ComponentActivity) so per-app locale switching works below Android 13. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CheckPrintTheme {
                CheckPrintApp()
            }
        }
    }
}

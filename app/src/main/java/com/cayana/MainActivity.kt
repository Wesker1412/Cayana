package com.cayana

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cayana.ui.navigation.CayanaNavHost
import com.cayana.ui.theme.CayanaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CayanaTheme {
                CayanaNavHost()
            }
        }
    }
}

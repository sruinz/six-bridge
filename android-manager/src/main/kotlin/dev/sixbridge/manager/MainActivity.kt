package dev.sixbridge.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.sixbridge.manager.install.ManagerAppVisibility

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SixBridgeManagerApp()
        }
    }

    override fun onStart() {
        super.onStart()
        ManagerAppVisibility.onActivityStarted()
    }

    override fun onStop() {
        ManagerAppVisibility.onActivityStopped()
        super.onStop()
    }
}

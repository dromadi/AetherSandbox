package com.aether.sandbox.media

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var pipeline by remember { mutableStateOf<SyntheticMediaPipeline?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    var mirrored by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var status by remember(uri) { mutableStateOf("Menyiapkan video…") }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (resumed) {
            key(uri) {
                AndroidView(modifier = Modifier.fillMaxWidth().height(220.dp),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                    pipeline?.close()
                                    pipeline = SyntheticMediaPipeline(context.applicationContext, uri, Surface(surface)) { status = it }
                                    playing = true
                                }
                                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    pipeline?.close(); pipeline = null
                                    return true
                                }
                            }
                        }
                    },
                    update = { view -> view.rotation = rotation.toFloat(); view.scaleX = if (mirrored) -1f else 1f },
                    onRelease = { pipeline?.close(); pipeline = null },
                )
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { playing = pipeline?.togglePlayback() ?: true }) { Text(if (playing) "Pause" else "Play") }
            OutlinedButton(onClick = { pipeline?.seekToStart() }) { Text("Ulang") }
            OutlinedButton(onClick = { rotation = (rotation + 90) % 360 }) { Text("Putar") }
            OutlinedButton(onClick = { mirrored = !mirrored }) { Text("Cermin") }
        }
    }
}

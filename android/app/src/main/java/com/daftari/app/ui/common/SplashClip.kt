package com.daftari.app.ui.common

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.daftari.app.R
import com.daftari.app.ui.theme.Df

/** Set by tests so screenshots show the poster frame instead of a video surface. */
object ClipConfig { var useVideo = true }

/**
 * Madyan's own Daftari animation (book opens, files fly out, book closes), processed to a pure-black background with
 * feathered edges so it melts into the app's #000. Plays muted on a TextureView; [loop] for loading states.
 * On the white theme it sits inside a black rounded tile (the clip is designed for black).
 */
@Composable
fun DaftariClip(size: Dp, loop: Boolean = true, modifier: Modifier = Modifier, onFinished: (() -> Unit)? = null) {
    val tile = !Df.c.isDark
    Box(
        modifier.size(size).then(if (tile) Modifier.clip(RoundedCornerShape(size * 0.22f)).background(Color.Black) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (LocalInspectionMode.current || !ClipConfig.useVideo) {
            Image(painterResource(R.drawable.splash_poster), null, Modifier.fillMaxSize())
        } else {
            ClipVideo(loop, onFinished)
        }
    }
}

@Composable
private fun ClipVideo(loop: Boolean, onFinished: (() -> Unit)?) {
    val context = LocalContext.current
    val finished by rememberUpdatedState(onFinished)
    var started by remember { mutableStateOf(false) }
    val holder = remember { arrayOfNulls<MediaPlayer>(1) }
    Box(Modifier.fillMaxSize()) {
        // poster under the video until the first frame renders: no flash, no box
        if (!started) Image(painterResource(R.drawable.splash_poster), null, Modifier.fillMaxSize())
        AndroidView(
            modifier = Modifier.fillMaxSize().alpha(if (started) 1f else 0.01f),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            runCatching {
                                val mp = MediaPlayer.create(context, R.raw.daftari_splash) ?: return
                                mp.setSurface(Surface(st))
                                mp.isLooping = loop
                                mp.setVolume(0f, 0f)
                                mp.setOnInfoListener { _, what, _ ->
                                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) started = true
                                    false
                                }
                                mp.setOnCompletionListener { finished?.invoke() }
                                mp.start()
                                holder[0] = mp
                            }.onFailure { finished?.invoke() }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            holder[0]?.runCatching { stop(); release() }; holder[0] = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }
    DisposableEffect(Unit) { onDispose { holder[0]?.runCatching { release() }; holder[0] = null } }
}

/** Loading state: the clip, small, centered, with an optional line under it. */
@Composable
fun ClipLoading(text: String? = null, size: Dp = 120.dp) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        DaftariClip(size)
        if (text != null) T(text, color = Df.c.muted)
    }
}

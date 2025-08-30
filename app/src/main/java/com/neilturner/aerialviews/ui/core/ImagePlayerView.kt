package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import coil.EventListener
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transition.TransitionTarget
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.share.DiskShare
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.ImmichAuthType
import com.neilturner.aerialviews.models.enums.PhotoScale
import com.neilturner.aerialviews.models.enums.ProgressBarLocation
import com.neilturner.aerialviews.models.enums.ProgressBarType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.ImmichMediaPrefs
import com.neilturner.aerialviews.models.prefs.SambaMediaPrefs
import com.neilturner.aerialviews.models.prefs.WebDavMediaPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.ui.overlays.ProgressBarEvent
import com.neilturner.aerialviews.ui.overlays.ProgressState
import com.neilturner.aerialviews.utils.SambaHelper
import com.neilturner.aerialviews.utils.ServerConfig
import com.neilturner.aerialviews.utils.SslHelper
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kosert.flowbus.GlobalBus
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.EnumSet
import kotlin.time.Duration.Companion.milliseconds
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable

class ImagePlayerView :
    AppCompatImageView,
    EventListener {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var listener: OnImagePlayerEventListener? = null
    private var finishedRunnable = Runnable { listener?.onImageFinished() }
    private var errorRunnable = Runnable { listener?.onImageError() }
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val progressBar =
        GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED && GeneralPrefs.progressBarType != ProgressBarType.VIDEOS

    private val imageLoader: ImageLoader by lazy {
        ImageLoader
            .Builder(context)
            .eventListener(this)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }.okHttpClient {
                buildOkHttpClient()
            }.build()
    }

    private fun buildOkHttpClient(): OkHttpClient {
        val serverConfig = ServerConfig("", ImmichMediaPrefs.validateSsl)
        val okHttpClient = SslHelper().createOkHttpClient(serverConfig)
        return okHttpClient
            .newBuilder()
            .addInterceptor(ApiKeyInterceptor())
            .build()
    }

    private class ApiKeyInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val originalRequest = chain.request()
            val newRequest =
                when (ImmichMediaPrefs.authType) {
                    ImmichAuthType.API_KEY -> {
                        originalRequest
                            .newBuilder()
                            .addHeader("X-API-Key", ImmichMediaPrefs.apiKey)
                            .build()
                    }
                    else -> originalRequest
                }
            return chain.proceed(newRequest)
        }
    }

    init {
        val scaleType =
            try {
                ScaleType.valueOf(GeneralPrefs.photoScale.toString())
            } catch (e: Exception) {
                Timber.e(e)
                GeneralPrefs.photoScale = PhotoScale.CENTER_CROP
                ScaleType.valueOf(PhotoScale.CENTER_CROP.toString())
            }
        this.scaleType = scaleType
    }

    fun release() {
        removeCallbacks(finishedRunnable)
        removeCallbacks(errorRunnable)
        listener = null
    }

    override fun onSuccess(
        request: ImageRequest,
        result: SuccessResult,
    ) {
        setupFinishedRunnable()
    }

    override fun onError(
        request: ImageRequest,
        result: ErrorResult,
    ) {
        Timber.e(result.throwable, "Exception while loading image: ${result.throwable.message}")
        onPlayerError()
    }

    fun setImage(media: AerialMedia) {
        Timber.i("Image URL: ${media.uri} (${media.source})")
        when (media.source) {
            AerialMediaSource.SAMBA -> {
                coroutineScope.launch { loadSambaImage(media.uri) }
            }
            AerialMediaSource.WEBDAV -> {
                coroutineScope.launch { if(media.byteArray!=null) {
                    loadWebDavImage(media.byteArray!!)
                    media.byteArray = null
                } }
            }
            else -> {
                coroutineScope.launch { loadImage(media.uri) }
            }
        }
    }

    private suspend fun loadImage(uri: Uri) {
        val request =
            ImageRequest
                .Builder(context)
                .data(uri)
                .target(this)
                .build()
        imageLoader.execute(request)
    }

    private suspend fun loadSambaImage(uri: Uri) {
        val request =
            ImageRequest
                .Builder(context)
                .target(this)

        try {
            val byteArray = byteArrayFromSambaFile(uri)
            request.data(byteArray)
        } catch (ex: Exception) {
            Timber.e(ex, "Exception while getting byte array from SMB share: ${ex.message}")
            listener?.onImageError()
            return
        }

        imageLoader.execute(request.build())
    }

    private suspend fun loadWebDavImage(byteArray: ByteArray) {
        val request =
            ImageRequest
                .Builder(context)
                .crossfade(true)
                .target(object : TransitionTarget {
                    override val drawable get() = this@ImagePlayerView.drawable
                    override val view get() = this@ImagePlayerView
                    override fun onSuccess(result: Drawable) {
                        this@ImagePlayerView.setImageDrawable(result)
                        (result as? Animatable)?.start()
                    }
                })

        try {
            // val byteArray = byteArrayFromWebDavFile(uri)
            request.data(byteArray)
        } catch (ex: Exception) {
            Timber.e(ex, "Exception while getting byte array from WebDAV resource: ${ex.message}")
            listener?.onImageError()
            return
        }

        imageLoader.execute(request.build())
    }

    fun stop() {
        removeCallbacks(finishedRunnable)
        setImageBitmap(null)
    }

    private suspend fun byteArrayFromWebDavFile(uri: Uri): ByteArray =
        withContext(Dispatchers.IO) {
            val client = OkHttpSardine()
            client.setCredentials(WebDavMediaPrefs.userName, WebDavMediaPrefs.password)
            return@withContext client.get(uri.toString()).readBytes()
        }

    private suspend fun byteArrayFromSambaFile(uri: Uri): ByteArray =
        withContext(Dispatchers.IO) {
            val shareNameAndPath = SambaHelper.parseShareAndPathName(uri)
            val shareName = shareNameAndPath.first
            val path = shareNameAndPath.second

            val config = SambaHelper.buildSmbConfig()
            val smbClient = SMBClient(config)
            val authContext = SambaHelper.buildAuthContext(SambaMediaPrefs.userName, SambaMediaPrefs.password, SambaMediaPrefs.domainName)

            smbClient.connect(SambaMediaPrefs.hostName).use { connection ->
                val session = connection?.authenticate(authContext)
                val share = session?.connectShare(shareName) as DiskShare
                val shareAccess = hashSetOf<SMB2ShareAccess>()
                shareAccess.add(SMB2ShareAccess.ALL.iterator().next())
                val file =
                    share.openFile(
                        path,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        null,
                        shareAccess,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                return@withContext file.inputStream.readBytes()
            }
        }

    private fun setupFinishedRunnable() {
        removeCallbacks(finishedRunnable)
        listener?.onImagePrepared()
        val duration = GeneralPrefs.slideshowSpeed.toLong() * 1000
        val delay = duration - GeneralPrefs.mediaFadeOutDuration.toLong()
        postDelayed(finishedRunnable, delay)
        if (progressBar) GlobalBus.post(ProgressBarEvent(ProgressState.START, 0, delay))
        Timber.i("Delay: ${delay.milliseconds} (duration: ${duration.milliseconds})")
    }

    private fun onPlayerError() {
        removeCallbacks(finishedRunnable)
        postDelayed(errorRunnable, ScreenController.ERROR_DELAY)
    }

    fun setOnPlayerListener(listener: ScreenController) {
        this.listener = listener
    }

    interface OnImagePlayerEventListener {
        fun onImageFinished()

        fun onImageError()

        fun onImagePrepared()
    }
}

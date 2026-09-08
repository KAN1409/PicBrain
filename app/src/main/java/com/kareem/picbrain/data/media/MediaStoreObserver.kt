package com.kareem.picbrain.data.media

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

class MediaStoreObserver(
    private val resolver: ContentResolver,
    private val onMediaChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private var registered = false

    fun start() {
        if (registered) return
        resolver.registerContentObserver(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            true,
            this
        )
        registered = true
    }

    fun stop() {
        if (!registered) return
        resolver.unregisterContentObserver(this)
        registered = false
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        onMediaChanged()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        onMediaChanged()
    }
}

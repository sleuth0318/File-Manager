package com.goodwy.filemanager.ftp

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object FtpServerState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(FtpServerUiState) -> Unit>()

    @Volatile
    var current: FtpServerUiState = FtpServerUiState()
        private set

    fun setState(state: FtpServerUiState) {
        current = state
        notifyListeners(state)
    }

    fun update(update: (FtpServerUiState) -> FtpServerUiState) {
        setState(update(current))
    }

    fun registerListener(listener: (FtpServerUiState) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun unregisterListener(listener: (FtpServerUiState) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(state: FtpServerUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it(state) }
        } else {
            mainHandler.post {
                listeners.forEach { it(state) }
            }
        }
    }
}

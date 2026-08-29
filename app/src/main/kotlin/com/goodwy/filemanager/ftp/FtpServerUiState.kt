package com.goodwy.filemanager.ftp

data class FtpServerUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val error: String = ""
)

package com.goodwy.filemanager.ftp

import java.security.SecureRandom

object FtpPasswordManager {
    private val secureRandom = SecureRandom()

    @Volatile
    var currentPassword: String = generatePassword()
        private set

    fun regeneratePassword(): String {
        currentPassword = generatePassword()
        return currentPassword
    }

    private fun generatePassword(): String {
        return secureRandom.nextInt(1_000_000).toString().padStart(6, '0')
    }
}

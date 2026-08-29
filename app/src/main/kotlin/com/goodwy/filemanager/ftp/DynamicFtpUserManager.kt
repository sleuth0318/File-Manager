package com.goodwy.filemanager.ftp

import android.content.Context
import com.goodwy.filemanager.extensions.config
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission

class DynamicFtpUserManager(
    private val context: Context,
    private val homeDirectory: String,
    private val onSuccessfulLogin: () -> Unit
) : UserManager {
    override fun authenticate(authentication: Authentication): User {
        val usernamePasswordAuthentication = authentication as? UsernamePasswordAuthentication
            ?: throw AuthenticationFailedException("Unsupported authentication type")

        val expectedUsername = context.config.ftpUsername
        val expectedPassword = FtpPasswordManager.currentPassword
        val usernameMatches = usernamePasswordAuthentication.username == expectedUsername
        val passwordMatches = usernamePasswordAuthentication.password == expectedPassword

        if (!usernameMatches || !passwordMatches) {
            throw AuthenticationFailedException("Invalid username or password")
        }

        val user = createUser(expectedUsername)
        onSuccessfulLogin()
        return user
    }

    override fun getUserByName(username: String): User? {
        return if (doesExist(username)) createUser(username) else null
    }

    override fun getAllUserNames(): Array<String> {
        return arrayOf(context.config.ftpUsername)
    }

    override fun delete(username: String) {
        throw UnsupportedOperationException("FTP users are managed by the app settings")
    }

    override fun save(user: User) {
        throw UnsupportedOperationException("FTP users are managed by the app settings")
    }

    override fun doesExist(username: String): Boolean {
        return username == context.config.ftpUsername
    }

    override fun getAdminName(): String {
        return context.config.ftpUsername
    }

    override fun isAdmin(username: String): Boolean {
        return username == context.config.ftpUsername
    }

    private fun createUser(username: String): BaseUser {
        return BaseUser().apply {
            name = username
            password = FtpPasswordManager.currentPassword
            homeDirectory = this@DynamicFtpUserManager.homeDirectory
            enabled = true
            authorities = listOf(
                WritePermission(),
                ConcurrentLoginPermission(0, 0)
            )
        }
    }
}

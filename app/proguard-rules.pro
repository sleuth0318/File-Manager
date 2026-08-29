-dontnote android.net.http.*
-dontnote org.apache.http.**
-keep class com.goodwy.** { *; }
-dontwarn com.goodwy.**
#Goodwy
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

# --- FTP SERVER RELEASE KEEP RULES ---

# Apache FtpServer uses command/config classes and resources reflectively.
-keep class org.apache.ftpserver.** { *; }
-keep interface org.apache.ftpserver.** { *; }
-keep enum org.apache.ftpserver.** { *; }

# Apache MINA is used by Apache FtpServer and internally creates filters/handlers.
-keep class org.apache.mina.** { *; }
-keep interface org.apache.mina.** { *; }
-keep enum org.apache.mina.** { *; }

# Keep app FTP feature classes stable.
-keep class com.goodwy.filemanager.services.FtpServerService { *; }
-keep class com.goodwy.filemanager.ftp.** { *; }

# Keep class metadata/signatures used by reflection.
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*

# Optional dependencies referenced by Apache MINA / Apache FtpServer but unused here.
-dontwarn javax.security.sasl.**
-dontwarn org.ietf.jgss.**
-dontwarn org.springframework.**
-dontwarn org.slf4j.impl.**
-dontwarn org.slf4j.**

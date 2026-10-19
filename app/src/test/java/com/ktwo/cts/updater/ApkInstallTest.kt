package com.ktwo.cts.updater

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ApkInstallTest.WindowsFileProvider::class])
class ApkInstallTest {
    // FileProvider's Android '/' path matching cannot handle Windows canonical paths.
    // Keep the URI conversion contract here; device FileProvider uses the manifest paths.
    @Implements(FileProvider::class)
    class WindowsFileProvider {
        companion object {
            @JvmStatic @Implementation
            fun getUriForFile(context: Context, authority: String, file: File): Uri {
                require(file.isFile)
                return Uri.parse("content://$authority/downloads/${file.name}")
            }
        }
    }

    private fun install(context: Context, uri: Uri) {
        val method = UpdateChecker::class.java.getDeclaredMethod(
            "installApkFromUri", Context::class.java, Uri::class.java
        )
        method.isAccessible = true
        try {
            method.invoke(UpdateChecker, context, uri)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test fun fileUriIsConvertedToReadableContentUriBeforeLaunch() {
        val app = RuntimeEnvironment.getApplication()
        val apk = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "test-update.apk")
        apk.parentFile!!.mkdirs()
        apk.writeText("test APK payload")
        var launched: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        org.robolectric.Shadows.shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        install(context, Uri.fromFile(apk))
        val intent = requireNotNull(launched)
        assertEquals("content", intent.data!!.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk), intent.data)
    }

    @Test fun downloadManagerContentUriIsPreserved() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        var launched: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        val uri = Uri.parse("content://downloads/all_downloads/42")
        install(context, uri)
        assertEquals(uri, launched!!.data)
    }
}

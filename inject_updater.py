import os, re, glob

repo = os.environ["REPO"]
m_path = "app/src/main/AndroidManifest.xml"
if os.path.exists(m_path):
    txt = open(m_path).read()
    for p in ["android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"]:
        if p not in txt:
            txt = txt.replace("<application", f'<uses-permission android:name="{p}" />\n    <application', 1)
    if "auto_updater_paths" not in txt:
        prov = '        <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.autoupdater.provider" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/auto_updater_paths" /></provider>\n    </application>'
        txt = txt.replace("</application>", prov, 1)
    open(m_path, "w").write(txt)

os.makedirs("app/src/main/res/xml", exist_ok=True)
open("app/src/main/res/xml/auto_updater_paths.xml", "w").write('<?xml version="1.0" encoding="utf-8"?>\n<paths><cache-path name="updates" path="." /></paths>\n')

files = glob.glob("app/src/main/**/MainActivity.kt", recursive=True) + glob.glob("app/src/main/**/MainActivity.java", recursive=True)
if not files:
    exit(1)

tf = files.pop(0)
src = open(tf).read()
pm = re.search(r'^\s*(package\s+[^\r\n;]+)', src, re.M)
pkg_line = pm.group(1).strip() if pm else "package com.example"

code = f'''{pkg_line}
import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.*
import kotlin.concurrent.thread

object AutoAppUpdater {{
    private const val API_URL = "https://github.com{repo}/releases/latest"
    private var done = false

    @JvmStatic fun checkForUpdate(ctx: Context) {{
        if (done) return; done = true
        thread {{
            try {{
                val c = (URL(API_URL).openConnection() as HttpURLConnection).apply {{
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "YV-TV-Remote-Updater")
                }}
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name").removePrefix("v")
                val notes = j.optString("body", "New update available.")
                val assets = j.getJSONArray("assets")
                var apkUrl = ""
                for (i in 0 until assets.length()) {{
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {{
                        apkUrl = a.getString("browser_download_url")
                        break
                    }}
                }}
                val latestCode = tag.split(".").lastOrNull()?.toIntOrNull() ?: 0
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val currentCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode

                if (latestCode > currentCode && apkUrl.isNotEmpty()) {{
                    Handler(Looper.getMainLooper()).postDelayed({{
                        try {{
                            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Update Available (v$tag)")
                                .setMessage("$notes\\n\\nTap Update Now to download and install inside the app.")
                                .setPositiveButton("Update Now") {{ _, _ -> startInAppUpdate(ctx, apkUrl) }}
                                .setNegativeButton("Later", null)
                                .setCancelable(true)
                                .show()
                        }} catch (e: Exception) {{
                            Toast.makeText(ctx, "Update v$tag available! Dialog err: ${{e.message}}", Toast.LENGTH_LONG).show()
                        }}
                    }}, 1500L)
                }}
            }} catch (_: Exception) {{}}
        }}
    }}

    private fun startInAppUpdate(ctx: Context, apkUrl: String) {{
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {{
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Permission Required")
                .setMessage("Allow 'Install unknown apps' to update inside the app.")
                .setPositiveButton("Allow") {{ _, _ ->
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${{ctx.packageName}}")))
                }}
                .setNegativeButton("Cancel", null)
                .show()
            return
        }}
        @Suppress("DEPRECATION")
        val dlg = ProgressDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert).apply {{
            setTitle("Downloading Update")
            setMessage("Downloading latest APK...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }}
        thread {{
            try {{
                var finalUrl = apkUrl
                var conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {{
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "YV-TV-Remote-Updater")
                }}
                while (conn.responseCode in 301..308) {{
                    finalUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {{
                        instanceFollowRedirects = false
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "YV-TV-Remote-Updater")
                    }}
                }}
                val total = conn.contentLength
                val outFile = File(ctx.cacheDir, "app-update.apk")
                conn.inputStream.use {{ inp ->
                    outFile.outputStream().use {{ out ->
                        val buf = ByteArray(8192)
                        var dl = 0
                        var r: Int
                        while (inp.read(buf).also {{ r = it }} != -1) {{
                            out.write(buf, 0, r)
                            dl += r
                            if (total > 0) Handler(Looper.getMainLooper()).post {{ dlg.progress = ((dl * 100L) / total).toInt() }}
                        }}
                    }}
                }}
                Handler(Looper.getMainLooper()).post {{
                    dlg.dismiss()
                    val uri = FileProvider.getUriForFile(ctx, "${{ctx.packageName}}.autoupdater.provider", outFile)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {{
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }})
                }}
            }} catch (e: Exception) {{
                Handler(Looper.getMainLooper()).post {{
                    dlg.dismiss()
                    AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Update Failed")
                        .setMessage(e.message ?: "Download error")
                        .setPositiveButton("OK", null)
                        .show()
                }}
            }}
        }}
    }}
}}
'''

out_file = os.path.join(os.path.dirname(tf), "AutoAppUpdater.kt")
open(out_file, "w").write(code)
print("Created", out_file)

if "AutoAppUpdater.checkForUpdate" not in src:
    rep = r'\1\n        AutoAppUpdater.checkForUpdate(this)' if tf.endswith(".kt") else r'\1\n        AutoAppUpdater.checkForUpdate(this);'
    s2, n = re.subn(r'(super\.onCreate\([^\)]*\);?)', rep, src, count=1, flags=re.DOTALL)
    if n > 0:
        open(tf, "w").write(s2)
        print("Injected AutoAppUpdater into", tf)

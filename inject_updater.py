import os, re, glob
import xml.etree.ElementTree as ET

repo = os.environ.get("REPO", "").strip().strip("/")
if "/" not in repo:
    raise ValueError(f"Expected REPO in 'owner/repo' format, got: {repo!r}")

m_path = "app/src/main/AndroidManifest.xml"
launcher_activity = None

if os.path.exists(m_path):
    txt = open(m_path, encoding="utf-8").read()
    for p in [
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
    ]:
        if p not in txt:
            txt = txt.replace("<application", f'<uses-permission android:name="{p}" />\n    <application', 1)
    if "usesCleartextTraffic" not in txt:
        txt = txt.replace("<application", '<application android:usesCleartextTraffic="true"', 1)
    if "auto_updater_paths" not in txt:
        prov = '        <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.autoupdater.provider" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/auto_updater_paths" /></provider>\n    </application>'
        txt = txt.replace("</application>", prov, 1)
    open(m_path, "w", encoding="utf-8").write(txt)

    try:
        root = ET.fromstring(txt)
        ns = {"android": "http://schemas.android.com/apk/res/android"}
        for act in root.findall(".//activity"):
            for intent in act.findall("intent-filter"):
                actions = [a.attrib.get(f"{{{ns['android']}}}name") for a in intent.findall("action")]
                cats = [c.attrib.get(f"{{{ns['android']}}}name") for c in intent.findall("category")]
                if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in cats:
                    name = act.attrib.get(f"{{{ns['android']}}}name", "")
                    if name:
                        launcher_activity = name.split(".")[-1]
                        break
            if launcher_activity:
                break
    except Exception as e:
        print("Manifest parse warning:", e)

os.makedirs("app/src/main/res/xml", exist_ok=True)
open("app/src/main/res/xml/auto_updater_paths.xml", "w", encoding="utf-8").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<paths><cache-path name="updates" path="." /></paths>\n'
)

candidates = []
if launcher_activity:
    candidates.extend(glob.glob(f"app/src/main/**/{launcher_activity}.kt", recursive=True))
    candidates.extend(glob.glob(f"app/src/main/**/{launcher_activity}.java", recursive=True))

if not candidates:
    candidates.extend(glob.glob("app/src/main/**/MainActivity.kt", recursive=True))
    candidates.extend(glob.glob("app/src/main/**/MainActivity.java", recursive=True))

if not candidates:
    for f in glob.glob("app/src/main/**/*Activity.kt", recursive=True) + glob.glob("app/src/main/**/*Activity.java", recursive=True):
        if "onCreate" in open(f, encoding="utf-8", errors="ignore").read():
            candidates.append(f)

if not candidates:
    raise FileNotFoundError("Could not locate any launcher or MainActivity file to inject AutoAppUpdater.")

tf = candidates[0]
src = open(tf, encoding="utf-8").read()
pm = re.search(r'^\s*(package\s+[^\r\n;]+)', src, re.M)
pkg_line = pm.group(1).strip() if pm else "package com.example"

code = f'''{pkg_line}
import android.app.*
import android.content.*
import android.net.*
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.*
import kotlin.concurrent.thread

object AutoAppUpdater {{
    private const val TAG = "AutoAppUpdater"
    private const val API_URL = "https://api.github.com/repos/{repo}/releases/latest"
    private const val DIRECT_MANIFEST_URL = "https://github.com/{repo}/releases/latest/download/update-manifest.json"
    private var done = false

    private fun findActivity(ctx: Context): Activity? {{
        var cur: Context? = ctx
        while (cur is ContextWrapper) {{
            if (cur is Activity) return cur
            cur = cur.baseContext
        }}
        return null
    }}

    private fun canShowDialog(ctx: Context): Boolean {{
        val act = findActivity(ctx) ?: return false
        return !act.isFinishing && !act.isDestroyed
    }}

    private fun hasNetwork(ctx: Context): Boolean {{
        return try {{
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {{
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }} else {{
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }}
        }} catch (_: Exception) {{
            true
        }}
    }}

    private fun fetchStringFollowingRedirects(urlStr: String, acceptHeader: String? = null): String? {{
        var currentUrl = urlStr
        var redirects = 0
        while (redirects <= 6) {{
            val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {{
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "YV-TV-Remote-Updater")
                if (!acceptHeader.isNullOrBlank()) {{
                    setRequestProperty("Accept", acceptHeader)
                }}
            }}
            try {{
                val code = c.responseCode
                if (code in 301..308) {{
                    val loc = c.getHeaderField("Location")?.trim().orEmpty()
                    if (loc.isEmpty()) return null
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }}
                if (code !in 200..299) {{
                    Log.w(TAG, "HTTP $code for $currentUrl")
                    return null
                }}
                return c.inputStream.bufferedReader().use {{ it.readText() }}
            }} finally {{
                c.disconnect()
            }}
        }}
        return null
    }}

    @JvmStatic fun checkForUpdate(ctx: Context) {{
        if (done) return
        done = true
        thread {{
            try {{
                // Brief pause so Wi-Fi / network stack settles on cold app launch
                Thread.sleep(2000L)
                if (!hasNetwork(ctx)) {{
                    Log.w(TAG, "No active internet connection detected.")
                    return@thread
                }}

                var latestCode = 0
                var tag = ""
                var notes = "New update available."
                var apkUrl = ""

                // Primary: Direct release asset download (avoids GitHub 60/hr unauthenticated API rate limit)
                val directManifest = fetchStringFollowingRedirects(DIRECT_MANIFEST_URL)
                if (!directManifest.isNullOrBlank()) {{
                    val mJson = JSONObject(directManifest)
                    latestCode = mJson.optInt("versionCode", 0)
                    tag = mJson.optString("versionName").trim()
                    val mNotes = mJson.optString("notes").trim()
                    if (mNotes.isNotEmpty()) notes = mNotes
                    apkUrl = mJson.optString("apkUrl").trim()
                }}

                // Fallback: GitHub Releases REST API
                if (latestCode <= 0 || apkUrl.isEmpty()) {{
                    val body = fetchStringFollowingRedirects(API_URL, "application/vnd.github+json")
                    if (!body.isNullOrBlank()) {{
                        val j = JSONObject(body)
                        tag = j.optString("tag_name").removePrefix("v").removePrefix("V")
                        notes = j.optString("body", notes)
                        val assets = j.optJSONArray("assets")
                        var manifestUrl = ""
                        if (assets != null) {{
                            for (i in 0 until assets.length()) {{
                                val a = assets.optJSONObject(i) ?: continue
                                val name = a.optString("name")
                                val dl = a.optString("browser_download_url")
                                if (name == "update-manifest.json" && dl.isNotBlank()) {{
                                    manifestUrl = dl
                                }} else if (name.endsWith(".apk", ignoreCase = true) && apkUrl.isEmpty()) {{
                                    apkUrl = dl
                                }}
                            }}
                        }}
                        latestCode = tag.split(".").lastOrNull()?.toIntOrNull() ?: 0
                        if (manifestUrl.isNotEmpty()) {{
                            val mText = fetchStringFollowingRedirects(manifestUrl)
                            if (!mText.isNullOrBlank()) {{
                                val mJson = JSONObject(mText)
                                val mCode = mJson.optInt("versionCode", 0)
                                if (mCode > 0) latestCode = mCode
                                val mVer = mJson.optString("versionName").trim()
                                if (mVer.isNotEmpty()) tag = mVer
                                val mNotes = mJson.optString("notes").trim()
                                if (mNotes.isNotEmpty()) notes = mNotes
                                val mApk = mJson.optString("apkUrl").trim()
                                if (mApk.isNotEmpty()) apkUrl = mApk
                            }}
                        }}
                    }}
                }}

                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val currentCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
                Log.i(TAG, "Current versionCode=$currentCode, Latest versionCode=$latestCode")

                if (latestCode > currentCode && apkUrl.isNotEmpty()) {{
                    Handler(Looper.getMainLooper()).post {{
                        if (!canShowDialog(ctx)) return@post
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
                    }}
                }}
            }} catch (e: Exception) {{
                Log.e(TAG, "Update check failed: ${{e.message}}", e)
            }}
        }}
    }}

    private fun startInAppUpdate(ctx: Context, apkUrl: String) {{
        if (!canShowDialog(ctx)) return
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
            var conn: HttpURLConnection? = null
            try {{
                var finalUrl = apkUrl
                var redirects = 0
                while (true) {{
                    conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {{
                        instanceFollowRedirects = false
                        connectTimeout = 20000
                        readTimeout = 45000
                        useCaches = false
                        setRequestProperty("User-Agent", "YV-TV-Remote-Updater")
                    }}
                    val code = conn.responseCode
                    if (code in 301..308 && redirects < 6) {{
                        val nextUrl = conn.getHeaderField("Location")?.trim().orEmpty()
                        conn.disconnect()
                        conn = null
                        if (nextUrl.isEmpty()) throw IllegalStateException("Empty redirect location")
                        finalUrl = URL(URL(finalUrl), nextUrl).toString()
                        redirects++
                    }} else {{
                        break
                    }}
                }}
                val activeConn = conn ?: throw IllegalStateException("Connection closed")
                if (activeConn.responseCode !in 200..299) {{
                    throw IllegalStateException("HTTP ${{activeConn.responseCode}}")
                }}
                val total = activeConn.contentLength
                val outFile = File(ctx.cacheDir, "app-update.apk")
                if (outFile.exists()) outFile.delete()
                activeConn.inputStream.use {{ inp ->
                    outFile.outputStream().use {{ out ->
                        val buf = ByteArray(8192)
                        var dl = 0
                        var r: Int
                        while (inp.read(buf).also {{ r = it }} != -1) {{
                            out.write(buf, 0, r)
                            dl += r
                            if (total > 0) Handler(Looper.getMainLooper()).post {{
                                try {{ if (dlg.isShowing) dlg.progress = ((dl * 100L) / total).toInt() }} catch (_: Exception) {{}}
                            }}
                        }}
                    }}
                }}
                Handler(Looper.getMainLooper()).post {{
                    try {{ if (dlg.isShowing) dlg.dismiss() }} catch (_: Exception) {{}}
                    val uri = FileProvider.getUriForFile(ctx, "${{ctx.packageName}}.autoupdater.provider", outFile)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {{
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }})
                }}
            }} catch (e: Exception) {{
                Log.e(TAG, "APK download failed: ${{e.message}}", e)
                Handler(Looper.getMainLooper()).post {{
                    try {{ if (dlg.isShowing) dlg.dismiss() }} catch (_: Exception) {{}}
                    if (canShowDialog(ctx)) {{
                        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Update Failed")
                            .setMessage(e.message ?: "Download error")
                            .setPositiveButton("OK", null)
                            .show()
                    }}
                }}
            }} finally {{
                conn?.disconnect()
            }}
        }}
    }}
}}
'''

out_file = os.path.join(os.path.dirname(tf), "AutoAppUpdater.kt")
open(out_file, "w", encoding="utf-8").write(code)
print("Created", out_file)

if "AutoAppUpdater.checkForUpdate" not in src:
    rep = r'\1\n        AutoAppUpdater.checkForUpdate(this)' if tf.endswith(".kt") else r'\1\n        AutoAppUpdater.checkForUpdate(this);'
    s2, n = re.subn(r'(super\.onCreate\([^\)]*\);?)', rep, src, count=1, flags=re.DOTALL)
    if n > 0:
        open(tf, "w", encoding="utf-8").write(s2)
        print("Injected AutoAppUpdater into", tf)
    else:
        raise RuntimeError(f"Found {tf} but could not locate super.onCreate(...) to inject AutoAppUpdater.")
else:
    print("AutoAppUpdater already present in", tf)

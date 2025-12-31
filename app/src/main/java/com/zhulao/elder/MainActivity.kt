package com.zhulao.elder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination
import org.vosk.Model
import org.vosk.Recognizer
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private var audioRecord: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null
    private var recognizer: Recognizer? = null
    private var running = false
    private var model: Model? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val voiceLock = Any() // 增加线程锁防止竞态条件导致的闪退
    private val reqAudio = 100
    private val reqContacts = 101
    private val reqCall = 102

    // SOS 触发逻辑
    private var lastTapTime = 0L
    private var tapCount = 0
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var screenToggleCount = 0
    private var lastScreenToggleTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 全局异常捕获... (省略，保持不变)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e.printStackTrace()
            try {
                val logFile = File(filesDir, "crash.log")
                FileOutputStream(logFile, true).use { out ->
                    val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())
                    out.write("\n[$time] Crash on thread ${t.name}:\n".toByteArray())
                    out.write(e.stackTraceToString().toByteArray())
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        setContentView(R.layout.activity_main)
        
        // 注册屏幕开关广播监听（电源键 SOS）
        registerScreenReceiver()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        findViewById<android.view.View>(R.id.btnVoice).setOnClickListener { toggleVoice() }
        findViewById<android.view.View>(R.id.btn110).setOnClickListener { callNumber("110") }
        findViewById<android.view.View>(R.id.btn120).setOnClickListener { callNumber("120") }
        findViewById<android.view.View>(R.id.btn119).setOnClickListener { callNumber("119") }
        findViewById<android.view.View>(R.id.btnContacts).setOnClickListener { pickBestContactAndCall() }
        findViewById<android.view.View>(R.id.btnSettings).setOnClickListener { onSettings() }
        requestAllPermissions()
        
        // SOS 屏幕三击监听
        val rootView = findViewById<android.view.View>(android.R.id.content)
        rootView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 500) {
                tapCount++
            } else {
                tapCount = 1
            }
            lastTapTime = now
            if (tapCount >= 3) {
                tapCount = 0
                triggerSOS()
            }
        }
        
        // 自动加载模型逻辑优化
        // 异步检查和加载模型，避免阻塞主线程导致白屏
        statusText.text = "正在初始化..."
        Thread {
            if (loadModelIfNeeded()) {
                runOnUiThread { statusText.text = "离线模型已就绪" }
            } else {
                initModelAsync()
            }
        }.start()
    }

    private fun registerScreenReceiver() {
        val filter = android.content.IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val now = System.currentTimeMillis()
                if (now - lastScreenToggleTime < 1000) {
                    screenToggleCount++
                } else {
                    screenToggleCount = 1
                }
                lastScreenToggleTime = now
                // 3次开关（ON-OFF-ON 或 OFF-ON-OFF 等，实际上用户连续按需要计算次数）
                // 连续按3次电源键通常会触发多次广播，设置阈值为3
                if (screenToggleCount >= 3) {
                    screenToggleCount = 0
                    triggerSOS()
                }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun triggerSOS() {
        val i = Intent(this, SOSActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(i)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (screenReceiver != null) unregisterReceiver(screenReceiver)
    }

    private fun initModelAsync() {
        // 1. 尝试从Assets复制
        runOnUiThread { statusText.text = "正在检查内置模型..." }
        // 强制尝试解压一次，因为可能 assets 已经有了
        try {
            val list = assets.list("") ?: emptyArray()
            if (list.contains("vosk-model-small-cn-0.22.zip")) {
                 if (copyModelFromAssets()) {
                    val localZip = File(filesDir, "model.zip")
                    installModelFromZip(localZip)
                    return
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }

        // 2. 检查外部存储 (Android/data/.../files/model.zip)
        val extDir = getExternalFilesDir(null)
        if (extDir != null) {
            val extZip = File(extDir, "model.zip")
            if (extZip.exists() && extZip.length() > 1024 * 1024) {
                 runOnUiThread { statusText.text = "发现外部存储模型包，准备解压..." }
                 installModelFromZip(extZip)
                 return
            }
        }

        // 3. 提示下载
        runOnUiThread { 
            statusText.text = "未找到离线模型，即将下载..." 
            downloadModel()
        }
    }

    private fun copyModelFromAssets(): Boolean {
        return try {
            val list = assets.list("") ?: emptyArray()
            if (!list.contains("vosk-model-small-cn-0.22.zip")) {
                return false
            }
            
            val inStream = assets.open("vosk-model-small-cn-0.22.zip")
            val tmpZip = File(filesDir, "model.zip")
            
            // 如果文件已存在且大小合理，跳过复制? 为了保险起见，还是覆盖吧，或者检查大小
            // 这里简单处理：直接覆盖
            
            FileOutputStream(tmpZip).use { out ->
                val buf = ByteArray(8192)
                var n = inStream.read(buf)
                while (n != -1) {
                    out.write(buf, 0, n)
                    n = inStream.read(buf)
                }
            }
            inStream.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun requestAllPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_CALL_LOG)
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 200)
        }
    }

    private fun loadModelIfNeeded(): Boolean {
        // 此函数现在只负责在后台线程中初始化model，不再直接返回结果给主线程判断
        // 因为 new Model() 是耗时操作，必须在后台执行
        
        // 1. Check Internal Storage
        var dir = File(filesDir, "model")
        if (dir.exists()) {
            val modelPath = findModelPath(dir)
            if (modelPath != null) {
                try {
                    model = Model(modelPath) // 耗时操作
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        // 2. Check External Storage (User accessible)
        val extDir = getExternalFilesDir(null)
        if (extDir != null) {
            dir = File(extDir, "model")
            if (dir.exists()) {
                val modelPath = findModelPath(dir)
                if (modelPath != null) {
                    try {
                        model = Model(modelPath) // 耗时操作
                        return true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return false
    }

    private fun findModelPath(rootDir: File): String? {
        // 1. 尝试通过 conf/model.conf 定位模型根目录 (最准确)
        var modelRoot: File? = null
        rootDir.walkTopDown().forEach { file ->
            if (file.isDirectory && file.name == "conf" && File(file, "model.conf").exists()) {
                modelRoot = file.parentFile
                return@forEach
            }
        }

        if (modelRoot != null) {
            // 检查根目录下是否有 final.mdl
            val finalMdl = File(modelRoot, "final.mdl")
            if (!finalMdl.exists()) {
                // 如果根目录没有，尝试在子目录找（例如 am/final.mdl）并移动上来
                // 这修复了部分模型结构不标准的问题
                val subMdl = modelRoot!!.walkTopDown().find { 
                    it.name == "final.mdl" && it.isFile
                }
                
                if (subMdl != null) {
                    try {
                        runOnUiThread { statusText.text = "正在修复模型结构..." }
                        subMdl.copyTo(finalMdl, overwrite = true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // 只要 conf 存在，且尽量修复了 final.mdl，就返回这个根目录
            // 即使 final.mdl 移动失败，也试着返回根目录，因为 Vosk 主要是从根目录找 conf
            if (finalMdl.exists()) {
                return modelRoot!!.absolutePath
            }
        }

        // 2. 兜底：如果找不到 conf 目录，退回到寻找任意 final.mdl
        // 这可能导致 "Failed to create a model" 如果缺少 conf，但总比找不到强
        var bestPath: String? = null
        rootDir.walkTopDown().forEach {
            if (File(it, "final.mdl").exists()) {
                 bestPath = it.absolutePath
                 return@forEach // 找到即止
            }
        }
        return bestPath
    }

    private fun toggleVoice() {
        if (running) {
            stopVoice()
        } else {
            startVoice()
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            return audioManager?.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager?.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), reqAudio)
            return
        }
        if (model == null) {
            statusText.text = "未检测到离线模型"
            return
        }
        
        if (!requestAudioFocus()) {
            statusText.text = "无法获取麦克风焦点"
            return
        }
        
        try {
            // 用户要求移除识别库限制，直接使用全量模型
            recognizer = Recognizer(model, 16000.0f)
            val rate = 16000
            val bufSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (_: Exception) {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            }
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                statusText.text = "麦克风初始化失败"
                return
            }

            val session = audioRecord!!.audioSessionId
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(session)
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(session)
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(session)
            statusText.text = "正在聆听…"
            synchronized(voiceLock) {
                running = true
            }
            audioRecord?.startRecording()
            Thread {
                val buffer = ShortArray(bufSize / 2)
                while (true) {
                    synchronized(voiceLock) {
                        if (!running) return@Thread
                    }
                    
                    try {
                        val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (n > 0) {
                            var ok = false
                            var res = ""
                            synchronized(voiceLock) {
                                if (running && recognizer != null) {
                                    ok = recognizer!!.acceptWaveForm(buffer, n)
                                    if (ok) {
                                        res = recognizer!!.getResult()
                                    } else {
                                        // partial result logic if needed, but for now just full result
                                    }
                                }
                            }
                            
                            if (ok && res.isNotEmpty()) {
                                val text = parseText(res)
                                if (text.isNotEmpty()) {
                                    runOnUiThread { statusText.text = "“$text”" }
                                    processTranscript(text)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Don't crash thread
                    }
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
            statusText.text = "语音启动失败: ${e.message}"
            stopVoice()
        }
    }

    private fun stopVoice() {
        synchronized(voiceLock) {
            running = false
            try { recognizer?.close() } catch (_: Exception) {}
            recognizer = null
        }
        abandonAudioFocus()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        ns = null; agc = null; aec = null
        statusText.text = "语音未开启"
    }

    private fun parseText(res: String): String {
        val s = Regex("\"text\"\\s*:\\s*\"(.*?)\"").find(res)?.groupValues?.get(1) ?: ""
        return s.trim().lowercase(Locale.ROOT)
    }

    private fun processTranscript(text: String) {
        val t = cleanTranscript(text)
        
        if (t.contains("救命")) {
            triggerSOS()
            return
        }

        if (t.contains("110") || t.contains("报警")) { callNumber("110"); return }
        if (t.contains("120") || t.contains("救护")) { callNumber("120"); return }
        if (t.contains("119") || t.contains("火警")) { callNumber("119"); return }
        
        val mapped = mapDialect(t)
        
        val contacts = ContactManager.getAllContacts(this)
        
        val exactMatches = mutableListOf<Pair<Contact, String>>()
        for (c in contacts) {
            if (mapped.contains(c.name)) {
                exactMatches.add(c to c.name)
            }
            for (k in c.keywords) {
                if (mapped.contains(k)) {
                    exactMatches.add(c to k)
                }
            }
        }
        if (exactMatches.isNotEmpty()) {
            val best = exactMatches.maxBy { it.second.length }
            callNumber(best.first.phone)
            return
        }

        val candidates = mutableListOf<MatchCandidate>()
        val pinyinMappedVariants = pinyinVariants(mapped)
        val hasLetters = pinyinMappedVariants.any { v -> v.any { ch -> ch in 'a'..'z' } }

        if (hasLetters) {
            for (c in contacts) {
                val targets = linkedSetOf<String>()
                targets.add(c.name)
                targets.addAll(c.keywords)

                for (target in targets) {
                    if (target.isBlank()) continue
                    val pinyinTargetVariants = pinyinVariants(target)
                    if (pinyinTargetVariants.isEmpty()) continue

                    val containMatched = pinyinMappedVariants.any { pm ->
                        pinyinTargetVariants.any { pt ->
                            pt.length >= 2 && pm.contains(pt)
                        }
                    }

                    if (containMatched) {
                        candidates.add(MatchCandidate(c.phone, 1, 0, target.length))
                    } else {
                        var dist = Int.MAX_VALUE
                        for (pm in pinyinMappedVariants) {
                            for (pt in pinyinTargetVariants) {
                                val d = fuzzyDistance(pm, pt)
                                if (d < dist) dist = d
                                if (dist == 0) break
                            }
                            if (dist == 0) break
                        }
                        val threshold = fuzzyThresholdForNameLen(target.length)
                        if (dist <= threshold) {
                            candidates.add(MatchCandidate(c.phone, 2, dist, target.length))
                        }
                    }
                }
            }
        } else {
            for (c in contacts) {
                val targets = linkedSetOf<String>()
                targets.add(c.name)
                targets.addAll(c.keywords)

                for (target in targets) {
                    if (target.isBlank()) continue
                    if (target.length <= 3) continue

                    val dist = fuzzyDistance(mapped, target)
                    val threshold = fuzzyThresholdForNameLen(target.length)
                    if (dist <= threshold) {
                        candidates.add(MatchCandidate(c.phone, 2, dist, target.length))
                    }
                }
            }
        }

        if (candidates.isEmpty()) return

        val sorted = candidates
            .distinctBy { Triple(it.phone, it.score, it.distance) }
            .sortedWith(compareBy<MatchCandidate> { it.score }
                .thenBy { it.distance }
                .thenByDescending { it.targetLen })

        val best = sorted.first()
        val second = sorted.getOrNull(1)

        val isConfident = when {
            best.score == 1 -> second == null || second.score > 1
            else -> second == null || best.score < second.score || best.distance + 1 < second.distance
        }

        if (isConfident) {
            callNumber(best.phone)
        } else {
            runOnUiThread { statusText.text = "没听清，请再说一遍" }
        }
    }

    private fun cleanTranscript(text: String): String {
        var res = text.replace(" ", "")
        val noise = listOf(
            "打给", "给", "打电话", "电话", "手机",
            "呼叫", "叫", "联系", "找", "拨打", "拨",
            "一下", "一个", "号码", "那个",
            "啊", "吧", "呢", "哈", "咧", "昂", "么"
        )
        for (w in noise) {
            res = res.replace(w, "")
        }
        return res
    }

    private data class MatchCandidate(
        val phone: String,
        val score: Int,
        val distance: Int,
        val targetLen: Int
    )

    private fun normalizePinyin(text: String): String {
        return text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    }

    private fun dialectFuzzyPinyin(text: String): String {
        var p = text.lowercase(Locale.ROOT)
        p = p.replace("zh", "z").replace("ch", "c").replace("sh", "s")
        p = p.replace("ng", "n")
        return p
    }

    private fun pinyinVariants(text: String): List<String> {
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()

        val fmt = HanyuPinyinOutputFormat().apply {
            toneType = HanyuPinyinToneType.WITHOUT_TONE
            vCharType = HanyuPinyinVCharType.WITH_V
        }

        val maxVariants = 8
        var variants: List<String> = listOf("")

        for (ch in raw) {
            val options = mutableListOf<String>()
            val arr: Array<String>? = try {
                PinyinHelper.toHanyuPinyinStringArray(ch, fmt)
            } catch (_: BadHanyuPinyinOutputFormatCombination) {
                null
            } catch (_: Exception) {
                null
            }

            if (arr != null && arr.isNotEmpty()) {
                for (p in arr) {
                    if (!p.isNullOrBlank()) options.add(p)
                }
            } else {
                options.add(ch.toString())
            }

            val uniq = options.distinct()
            val next = ArrayList<String>(maxVariants)
            for (prefix in variants) {
                for (opt in uniq) {
                    if (next.size >= maxVariants) break
                    next.add(prefix + opt)
                }
                if (next.size >= maxVariants) break
            }
            variants = next.distinct()
            if (variants.isEmpty()) break
        }

        val normalized = variants
            .map { normalizePinyin(dialectFuzzyPinyin(it)) }
            .filter { it.isNotEmpty() }
            .distinct()

        if (normalized.isNotEmpty()) return normalized

        return emptyList()
    }

    private fun fuzzyThresholdForNameLen(nameLen: Int): Int {
        return when {
            nameLen <= 3 -> 0
            nameLen <= 5 -> 1
            else -> 2
        }
    }

    private fun fuzzyDistance(text: String, target: String): Int {
        if (text.isEmpty() || target.isEmpty()) return Int.MAX_VALUE
        if (text.length < 2 || target.length < 2) return Int.MAX_VALUE

        val windowSize = (target.length + 2).coerceAtMost(text.length)
        var best = Int.MAX_VALUE
        val maxStart = (text.length - 2).coerceAtLeast(0)
        for (start in 0..maxStart) {
            val end = (start + windowSize).coerceAtMost(text.length)
            if (end <= start) break
            val sub = text.substring(start, end)
            val dist = levenshtein(sub, target)
            if (dist < best) best = dist
            if (best == 0) return 0
        }
        return best
    }

    private fun toPinyin(text: String): String {
        return pinyinVariants(text).firstOrNull() ?: text
    }

    private fun isFuzzyMatch(text: String, target: String): Boolean {
        if (target.length < 2) return false // 单字不进行模糊匹配，防止误触
        
        // 简单的滑动窗口匹配
        val windowSize = target.length + 1 // 允许比目标词多一个字（例如插入了杂音）
        for (i in 0..text.length - target.length) {
            val end = (i + windowSize).coerceAtMost(text.length)
            val sub = text.substring(i, end)
            // 计算编辑距离
            val dist = levenshtein(sub, target)
            // 允许误差：长度<=3允许1个错误，长度>3允许2个错误
            val threshold = if (target.length <= 3) 1 else 2
            if (dist <= threshold) {
                return true
            }
        }
        return false
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length

        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1) { 0 }

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }

    private fun mapDialect(s: String): String {
        var r = s
        // 基础数字映射
        r = r.replace("一一零", "110").replace("一二零", "120").replace("一一九", "119")
        r = r.replace("幺二零", "120").replace("幺一零", "110").replace("幺一九", "119")
        r = r.replace("报警", "110").replace("救护车", "120").replace("着火", "119")
        
        return r
    }

    private fun callNumber(num: String) {
        stopVoice()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$num"))
            startActivity(i)
        } else {
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))
            startActivity(i)
        }
    }

    private fun pickBestContactAndCall() {
        // 由于系统通讯录不可用，改为直接跳转到联系人管理
        onSettings()
    }

    override fun onResume() {
        super.onResume()
        // 检查模型文件是否存在，如果被删除则重置内存状态
        val modelDir = File(filesDir, "model")
        if (!modelDir.exists() && model != null) {
            model?.close()
            model = null
            statusText.text = "模型文件丢失，正在重新初始化..."
            initModelAsync()
        } else if (modelDir.exists() && model == null) {
             Thread {
                if (loadModelIfNeeded()) {
                    runOnUiThread { statusText.text = "离线模型已就绪" }
                }
            }.start()
        }
    }

    private fun onSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun downloadModel() {
        statusText.text = "开始下载离线模型…"
        runOnUiThread {
            progressBar.visibility = android.view.View.VISIBLE
            progressBar.progress = 0
        }
        Thread {
            try {
                val url = URL("https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip")
                val conn = url.openConnection() as HttpURLConnection
                conn.connect()
                val length = conn.contentLength
                val tmpZip = File(filesDir, "model.zip")
                
                runOnUiThread {
                    if (length > 0) progressBar.max = length
                    else progressBar.isIndeterminate = true
                }

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(tmpZip).use { out ->
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            total += n
                            val p = total
                            runOnUiThread {
                                if (length > 0) {
                                    progressBar.progress = p
                                    val pct = (p * 100L / length).toInt()
                                    statusText.text = "下载中…$pct%"
                                } else {
                                    statusText.text = "下载中…${p / 1024}KB"
                                }
                            }
                        }
                    }
                }
                
                installModelFromZip(tmpZip)

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    statusText.text = "下载失败: ${e.message}"
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }.start()
    }

    private fun installModelFromZip(zipFile: File) {
        try {
            runOnUiThread {
                statusText.text = "解压模型中…"
                progressBar.isIndeterminate = true
            }

            val target = File(filesDir, "model")
            if (!target.exists()) target.mkdirs()
            
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val f = File(target, entry.name)
                    // 防止Zip Slip漏洞
                    if (!f.canonicalPath.startsWith(target.canonicalPath)) {
                        throw SecurityException("Zip Path Traversal detected")
                    }
                    if (entry.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zis.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            
            // 解压完成后删除zip (如果是下载的)
            // if (zipFile.name == "model.zip") zipFile.delete() 
            // 暂时保留以便调试或下次直接使用
            
            val modelPath = findModelPath(target)
            if (modelPath != null) {
                try {
                    model = Model(modelPath)
                    runOnUiThread {
                        statusText.text = "离线模型已就绪"
                        progressBar.visibility = android.view.View.GONE
                        toggleVoice()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "模型加载失败: ${e.message}\n路径: $modelPath"
                        progressBar.visibility = android.view.View.GONE
                    }
                }
            } else {
                runOnUiThread {
                    statusText.text = "模型校验失败: 未找到final.mdl"
                    progressBar.visibility = android.view.View.GONE
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                statusText.text = "安装失败: ${e.message}"
                progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun tryCopyModelFromAssets(): Boolean {
        return try {
            val inStream = assets.open("vosk-model-small-cn-0.22.zip")
            val tmpZip = File(filesDir, "model.zip")
            FileOutputStream(tmpZip).use { out ->
                val buf = ByteArray(8192)
                var n = inStream.read(buf)
                while (n != -1) {
                    out.write(buf, 0, n)
                    n = inStream.read(buf)
                }
            }
            inStream.close()
            val target = File(filesDir, "model")
            if (!target.exists()) target.mkdirs()
            ZipInputStream(tmpZip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val f = File(target, entry.name)
                    if (entry.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val m = zis.read(buf)
                                if (m == -1) break
                                out.write(buf, 0, m)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            tmpZip.delete()
            val modelPath = findModelPath(target)
            if (modelPath != null) {
                model = Model(modelPath)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}

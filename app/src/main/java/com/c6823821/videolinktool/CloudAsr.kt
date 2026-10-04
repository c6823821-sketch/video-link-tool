package com.c6823821.videolinktool

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cloud transcription backing the 转文字 function.
 * Needs no account and no third party signing service: the request
 * signature is computed locally, and every endpoint is a mainland China host, so
 * no VPN is involved.
 *
 * Flow: upload_sign -> get STS keys -> sign a VOD upload request -> PUT the audio
 * -> submit an audio_subtitle job -> poll until the utterances come back.
 */
object CloudAsr {
    private const val API_BASE = "https://lv-pc-api-sinfonlinec.ulikecam.com/lv/v1"
    private const val APPVR = "6.6.0"
    private const val PF = "4"
    private const val SPACE_NAME = "lv-mac-recognition"
    private const val WORDS_PER_LINE = 16
    private const val UA_API = "Cronet/TTNetVersion:d4572e53 2024-06-12 QuicVersion:4bf243e0 2023-04-17"
    private const val UA_UP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/81.0.4044.138 Safari/537.36 Thea/1.0.1"
    private const val QUERY_TIMEOUT_MS = 180_000L

    data class Segment(val start: Double, val end: Double, val text: String)

    fun transcribe(audioPath: String, onProgress: (Int, String) -> Unit): List<Segment> {
        val binary = File(audioPath).takeIf { it.exists() }?.readBytes()
            ?: throw IllegalStateException("音频文件不存在")
        if (binary.isEmpty()) throw IllegalStateException("音频是空的")
        val tdid = deviceId()
        onProgress(2, "正在准备识别...")
        val crc = crc32Hex(binary)
        val storeUri = upload(binary, crc, tdid)
        onProgress(50, "正在识别语音...")

        val songs = JSONArray().put(
            JSONObject().put("end_time", 6000).put("id", "").put("start_time", 0)
        )
        val payload = JSONObject()
            .put("adjust_endtime", 200)
            .put("audio", storeUri)
            .put("caption_type", 2)
            .put("client_request_id", requestId())
            .put("max_lines", 1)
            .put("songs_info", songs)
            .put("words_per_line", WORDS_PER_LINE)
        val submit = apiPost("audio_subtitle/submit", payload, tdid)
        val id = submit.optJSONObject("data")?.optString("id").orEmpty()
        if (id.isBlank()) throw IllegalStateException("识别任务提交失败")

        val deadline = System.currentTimeMillis() + QUERY_TIMEOUT_MS
        var percent = 52
        while (true) {
            val query = apiPost(
                "audio_subtitle/query",
                JSONObject().put("id", id).put("pack_options", JSONObject().put("need_attribute", true)),
                tdid,
            )
            val segments = parseSegments(query)
            if (segments.isNotEmpty()) return segments
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("识别超时")
            onProgress(percent.coerceAtMost(96), "正在识别语音...")
            percent += 2
            Thread.sleep(1200)
        }
    }

    /** Utterance gaps decide the punctuation, exactly like reading a real subtitle track. */
    fun compose(segments: List<Segment>): String {
        val builder = StringBuilder()
        segments.forEachIndexed { index, segment ->
            val text = segment.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            if (index > 0 && builder.isNotEmpty()) {
                val previous = segments[index - 1]
                val gap = segment.start - previous.end
                builder.append(if (gap >= 0.55) '.' else ',')
            }
            builder.append(text)
        }
        return convertMarks(builder.toString())
    }

    private fun convertMarks(text: String): String {
        val builder = StringBuilder()
        val questionTails = listOf("吗", "呢", "吧", "么")
        val exclaimTails = listOf("啊", "呀", "哇", "啦", "哦", "唉", "哎")
        val questionWords = listOf(
            "为什么", "什么", "怎么", "哪儿", "哪里", "哪个", "哪些", "多少", "几个", "几点",
            "多久", "多大", "能不能", "是不是", "有没有", "谁",
        )
        var start = 0
        text.forEachIndexed { index, ch ->
            if (ch == '.' || ch == ',') {
                val clause = text.substring(start, index)
                val tail = clause.takeLast(8)
                val mark = when {
                    ch == ',' -> '，'
                    questionTails.any { clause.endsWith(it) } || questionWords.any { tail.contains(it) } -> '？'
                    exclaimTails.any { clause.endsWith(it) } -> '！'
                    else -> '。'
                }
                builder.append(clause).append(mark)
                start = index + 1
            }
        }
        if (start < text.length) {
            val clause = text.substring(start)
            val tail = clause.takeLast(8)
            val mark = when {
                questionTails.any { clause.endsWith(it) } || questionWords.any { tail.contains(it) } -> '？'
                exclaimTails.any { clause.endsWith(it) } -> '！'
                else -> '。'
            }
            builder.append(clause).append(mark)
        }
        return builder.toString()
    }

    private fun parseSegments(json: JSONObject): List<Segment> {
        val utterances = json.optJSONObject("data")?.optJSONArray("utterances") ?: return emptyList()
        val out = ArrayList<Segment>(utterances.length())
        for (i in 0 until utterances.length()) {
            val item = utterances.optJSONObject(i) ?: continue
            val text = item.optString("text").trim()
            if (text.isEmpty()) continue
            val start = item.optDouble("start_time", 0.0) / 1000.0
            val end = item.optDouble("end_time", 0.0) / 1000.0
            if (end > start) out.add(Segment(start, end, text))
        }
        return out
    }

    private fun upload(binary: ByteArray, crc: String, tdid: String): String {
        val data = apiPost("upload_sign", JSONObject().put("biz", "pc-recognition"), tdid)
            .optJSONObject("data") ?: throw IllegalStateException("识别服务返回异常")
        val accessKey = data.optString("access_key_id")
        val secretKey = data.optString("secret_access_key")
        val sessionToken = data.optString("session_token")
        if (accessKey.isBlank() || secretKey.isBlank() || sessionToken.isBlank()) {
            throw IllegalStateException("识别服务返回异常")
        }

        val params = "Action=ApplyUploadInner&FileSize=" + binary.size +
            "&FileType=object&IsInner=1&SpaceName=" + SPACE_NAME +
            "&Version=2020-11-19&s=5y0udbjapi"
        val amzDate = amzDate()
        val dateStamp = amzDate.substring(0, 8)
        val headers = linkedMapOf(
            "x-amz-date" to amzDate,
            "x-amz-security-token" to sessionToken,
        )
        val signature = awsSignature(secretKey, params, headers)
        val authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + dateStamp +
            "/cn/vod/aws4_request, SignedHeaders=x-amz-date;x-amz-security-token, Signature=" + signature

        val applyRequest = Request.Builder()
            .url("https://vod.bytedanceapi.com/?" + params)
            .header("x-amz-date", amzDate)
            .header("x-amz-security-token", sessionToken)
            .header("authorization", authorization)
            .get()
            .build()
        val apply = HttpClient.client.newCall(applyRequest).execute().use { response ->
            JSONObject(response.body?.string().orEmpty())
        }
        val address = apply.optJSONObject("Result")?.optJSONObject("UploadAddress")
            ?: throw IllegalStateException("获取上传地址失败")
        val info = address.optJSONArray("StoreInfos")?.optJSONObject(0)
            ?: throw IllegalStateException("上传信息不完整")
        val host = address.optJSONArray("UploadHosts")?.optString(0).orEmpty()
        val storeUri = info.optString("StoreUri")
        val uploadAuth = info.optString("Auth")
        val uploadId = info.optString("UploadID")
        if (host.isBlank() || storeUri.isBlank() || uploadAuth.isBlank() || uploadId.isBlank()) {
            throw IllegalStateException("上传信息不完整")
        }

        val partRequest = Request.Builder()
            .url("https://" + host + "/" + storeUri + "?partNumber=1&uploadID=" + uploadId)
            .header("User-Agent", UA_UP)
            .header("Authorization", uploadAuth)
            .header("Content-CRC32", crc)
            .put(binary.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        HttpClient.client.newCall(partRequest).execute().use { response ->
            val body = JSONObject(response.body?.string().orEmpty())
            if (body.optInt("success", -1) != 0) throw IllegalStateException("音频上传被拒绝")
        }

        val doneRequest = Request.Builder()
            .url("https://" + host + "/" + storeUri + "?uploadID=" + uploadId)
            .header("User-Agent", UA_UP)
            .header("Authorization", uploadAuth)
            .header("Content-CRC32", crc)
            .post(("1:" + crc).toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        HttpClient.client.newCall(doneRequest).execute().use { it.body?.close() }
        return storeUri
    }

    private fun apiPost(path: String, payload: JSONObject, tdid: String): JSONObject {
        val seconds = (System.currentTimeMillis() / 1000).toString()
        val signedPath = "/lv/v1/" + path
        val tail = if (signedPath.length >= 7) signedPath.substring(signedPath.length - 7) else signedPath
        val raw = "9e2c|" + tail + "|" + PF + "|" + APPVR + "|" + seconds + "|" + tdid + "|11ac"
        val request = Request.Builder()
            .url(API_BASE + "/" + path)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", UA_API)
            .header("appvr", APPVR)
            .header("device-time", seconds)
            .header("pf", PF)
            .header("sign", md5Hex(raw))
            .header("sign-ver", "1")
            .header("tdid", tdid)
            .build()
        return HttpClient.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IllegalStateException("识别服务返回异常（HTTP " + response.code + "）")
            if (json.optString("ret") != "0") {
                throw IllegalStateException("识别服务异常 (ret=" + json.optString("ret") + ")")
            }
            json
        }
    }

    private fun awsSignature(
        secretKey: String,
        requestParameters: String,
        headers: LinkedHashMap<String, String>,
    ): String {
        val canonicalHeaders = headers.entries.joinToString("") { it.key + ":" + it.value + "\n" }
        val signedHeaders = headers.keys.joinToString(";")
        val payloadHash = sha256Hex("")
        val canonicalRequest = listOf("GET", "/", requestParameters, canonicalHeaders, signedHeaders, payloadHash)
            .joinToString("\n")
        val amz = headers["x-amz-date"].orEmpty()
        val dateStamp = amz.substring(0, 8)
        val scope = dateStamp + "/cn/vod/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", amz, scope, sha256Hex(canonicalRequest)).joinToString("\n")
        var key = hmacSha256(("AWS4" + secretKey).toByteArray(Charsets.UTF_8), dateStamp)
        key = hmacSha256(key, "cn")
        key = hmacSha256(key, "vod")
        key = hmacSha256(key, "aws4_request")
        return hmacSha256(key, stringToSign).toHex()
    }

    private fun deviceId(): String {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return (390 + year % 10).toString() + "3278516897751"
    }

    private fun requestId(): String {
        val random = java.util.Random()
        return String.format(
            Locale.US,
            "%08x-%04x-%04x-%04x-%012x",
            (System.currentTimeMillis() / 1000).toInt(),
            android.os.Process.myPid() and 0xFFFF,
            random.nextInt(0x10000),
            random.nextInt(0x10000),
            random.nextLong() and 0xFFFFFFFFFFFFL,
        )
    }

    private fun amzDate(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun crc32Hex(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return String.format(Locale.US, "%08x", crc.value)
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun hmacSha256(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) builder.append(String.format(Locale.US, "%02x", byte))
        return builder.toString()
    }
}
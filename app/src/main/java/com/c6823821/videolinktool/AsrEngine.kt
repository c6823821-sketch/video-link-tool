package com.c6823821.videolinktool

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader

object AsrEngine {
    fun transcribe(
        context: Context,
        wavPath: String,
        onProgress: (Int, String) -> Unit,
    ): String {
        onProgress(88, "检查/下载离线语音模型...")
        val paths = ModelManager.prepare(context, onProgress)
        onProgress(92, "加载语音模型...")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = paths.model.absolutePath,
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = threads,
                provider = "cpu",
            ),
        )
        onProgress(95, "读取音频...")
        val wave = WaveReader.readWave(wavPath)
        val recognizer = OfflineRecognizer(assetManager = null, config = config)
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(wave.samples, wave.sampleRate)
                onProgress(98, "识别中...")
                recognizer.decode(stream)
                val result = recognizer.getResult(stream).text.trim()
                if (result.isBlank()) throw IllegalStateException("没有识别到文字，可能视频没有语音")
                onProgress(100, "识别完成")
                return result
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }
}

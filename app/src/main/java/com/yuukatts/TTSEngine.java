package com.yuukatts;

import android.content.res.AssetManager;
import android.util.Log;

import com.yuukatts.tokenizer.WordPieceTokenizer;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * YuukaTTS 引擎 —— PyTorch Mobile 版本。
 *
 * 三个 TorchScript 模型（CPU 版），forward 签名从模型内部提取：
 *
 *   BERT:  forward(input_ids, attention_mask, token_type_ids, word2ph) → Tensor
 *   SSL:   forward(ref_audio) → Tensor
 *   GPT:   forward(ssl_content, ref_audio_sr, ref_seq, text_seq, ref_bert, text_bert, top_k) → Tensor
 *
 * 其中 GPT-SoVITS 内部已包含参考音频重采样（32000→16000）、SV 说话人特征提取、
 * T2S 语义 token 生成 和 VITS 声码器，一个 forward 输出最终波形。
 */
public class TTSEngine {

    private static final String TAG = "YuukaTTS";

    private Module bertModel;
    private Module sslModel;
    private Module gptSovitsModel;

    private WordPieceTokenizer tokenizer;

    private boolean loaded = false;
    private int sampleRate = 32000; // GPT-SoVITS 输出 32kHz
    private int sslSampleRate = 16000; // SSL 模型期望 16kHz
    private int maxTextLen = 200; // 最大文本长度

    // 推理参数
    private float speedParam = 1.0f;
    private int topKParam = 15;
    private float tempParam = 0.8f;

    public boolean isLoaded() { return loaded; }
    public int getSampleRate() { return sampleRate; }
    public float getSpeedParam() { return speedParam; }
    public int getTopKParam() { return topKParam; }
    public float getTempParam() { return tempParam; }
    public WordPieceTokenizer getTokenizer() { return tokenizer; }

    /**
     * 从指定目录加载三个模型文件和 vocab.txt。
     * 目录下应有:
     *   bert_model_cpu.pt (或 bert_model.pt)
     *   ssl_model_cpu.pt (或 ssl_model.pt)
     *   gpt_sovits_model_cpu.pt (或 gpt_sovits_model.pt)
     *   vocab.txt
     */
    public void loadModels(String dirPath, AssetManager assets) throws Exception {
        if (loaded) close();

        File dir = new File(dirPath);

        // 自动匹配文件名（兼容 _cpu 后缀或不带后缀）
        File bertFile = findModelFile(dir, "bert_model");
        File sslFile = findModelFile(dir, "ssl_model");
        File gptFile = findModelFile(dir, "gpt_sovits_model");

        if (bertFile == null) throw new RuntimeException("缺少 bert_model*.pt");
        if (sslFile == null) throw new RuntimeException("缺少 ssl_model*.pt");
        if (gptFile == null) throw new RuntimeException("缺少 gpt_sovits_model*.pt");

        // ── 加载 tokenizer ──
        tokenizer = new WordPieceTokenizer();
        // 先尝试从模型目录加载 vocab.txt
        File vocabFile = new File(dir, "vocab.txt");
        if (vocabFile.exists()) {
            try (FileInputStream fis = new FileInputStream(vocabFile)) {
                tokenizer.loadVocab(fis);
                Log.i(TAG, "从模型目录加载 vocab.txt: " + tokenizer.getVocabSize() + " tokens");
            }
        } else if (assets != null) {
            // 从 assets 加载
            try {
                InputStream is = assets.open("vocab.txt");
                tokenizer.loadVocab(is);
                is.close();
                Log.i(TAG, "从 assets 加载 vocab.txt: " + tokenizer.getVocabSize() + " tokens");
            } catch (IOException e) {
                throw new RuntimeException("找不到 vocab.txt（模型目录和 assets 都没有）");
            }
        } else {
            throw new RuntimeException("找不到 vocab.txt");
        }

        // ── 加载模型 ──
        Log.i(TAG, "加载 BERT: " + bertFile.getName() + " (" + (bertFile.length() / 1048576) + " MB)");
        bertModel = Module.load(bertFile.getAbsolutePath());

        Log.i(TAG, "加载 SSL: " + sslFile.getName() + " (" + (sslFile.length() / 1048576) + " MB)");
        sslModel = Module.load(sslFile.getAbsolutePath());

        Log.i(TAG, "加载 GPT-SoVITS: " + gptFile.getName() + " (" + (gptFile.length() / 1048576) + " MB)");
        gptSovitsModel = Module.load(gptFile.getAbsolutePath());

        loaded = true;
        Log.i(TAG, "✅ 三个模型 + tokenizer 加载完成");
    }

    private File findModelFile(File dir, String prefix) {
        // 尝试 _cpu.pt, .pt 等多种后缀
        String[] suffixes = {"_cpu.pt", ".pt"};
        for (String suf : suffixes) {
            File f = new File(dir, prefix + suf);
            if (f.exists() && f.length() > 1024) return f;
        }
        // 模糊匹配
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(prefix) && f.getName().endsWith(".pt") && f.length() > 1024) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * 全流程推理。
     *
     * @param text          要合成的日文文本（如 "こんにちは"）
     * @param refAudioPath  参考音频文件路径（WAV，任意采样率，GPT 内部重采样）
     * @param refText       参考音频对应的文本（可选，用于 BERT 编码参考文本）
     * @param speed         语速（后处理，0.5-2.5）
     * @param topK          top_k 采样
     * @param temperature   温度（暂未使用）
     * @return 音频采样 float[]（32kHz）
     */
    public float[] synthesize(
            String text,
            String refAudioPath,
            String refText,
            float speed,
            int topK,
            float temperature
    ) throws Exception {
        if (!loaded) throw new IllegalStateException("模型未加载");
        this.speedParam = speed;
        this.topKParam = topK;
        this.tempParam = temperature;

        Log.i(TAG, "━━━ 推理开始 ━━━");
        Log.i(TAG, "text: \"" + text + "\"");
        Log.i(TAG, "ref_audio: " + refAudioPath);
        Log.i(TAG, "ref_text: \"" + (refText != null ? refText : "(null)") + "\"");
        Log.i(TAG, "speed=" + speed + " topK=" + topK);

        // ── Step 0: Tokenize 文本 ──
        int[] targetTokenIds = tokenizer.encode(text, maxTextLen);
        long[] targetIdsLong = toLongArray(targetTokenIds);

        int[] refTokenIds;
        if (refText != null && !refText.isEmpty()) {
            refTokenIds = tokenizer.encodeReference(refText, maxTextLen);
        } else {
            // 没有参考文本时，用目标文本自己的 token IDs
            refTokenIds = targetTokenIds;
        }
        long[] refIdsLong = toLongArray(refTokenIds);

        Log.i(TAG, "目标 tokens: " + targetTokenIds.length + "  参考 tokens: " + refTokenIds.length);

        // ── Step 1: BERT → text_bert ──
        int seqLen = targetTokenIds.length;
        int phoneLen = seqLen - 2;  // BERT slices [1:-1], so word2ph = seqLen-2
        if (phoneLen < 1) phoneLen = 1;
        long[] attnMask = new long[seqLen];
        long[] typeIds = new long[seqLen];
        long[] word2ph = new long[phoneLen];
        Arrays.fill(attnMask, 1L);
        // word2ph: 每个 BERT token 对应几个 phone（1D，不 2D）
        // word2ph[i] = phone 数量；简单情景每个 token 对应 1 个 phone
        Arrays.fill(word2ph, 1L);

        Tensor inputIds = Tensor.fromBlob(targetIdsLong, new long[]{1, seqLen});
        Tensor attnMaskT = Tensor.fromBlob(attnMask, new long[]{1, seqLen});
        Tensor typeIdsT = Tensor.fromBlob(typeIds, new long[]{1, seqLen});
        Tensor word2phT = Tensor.fromBlob(word2ph, new long[]{phoneLen});  // 1D, phoneLen

        IValue bertResult = bertModel.forward(
                IValue.from(inputIds),
                IValue.from(attnMaskT),
                IValue.from(typeIdsT),
                IValue.from(word2phT)
        );
        Tensor textBert = bertResult.toTensor();
        Log.i(TAG, "BERT text_bert shape: " + Arrays.toString(textBert.shape()));

        // ── Step 1b: BERT → ref_bert ──
        int refLen = refTokenIds.length;
        int refPhoneLen = refLen - 2;
        if (refPhoneLen < 1) refPhoneLen = 1;
        long[] refAttnMask = new long[refLen];
        long[] refTypeIds = new long[refLen];
        long[] refWord2ph = new long[refPhoneLen];
        Arrays.fill(refAttnMask, 1L);
        Arrays.fill(refWord2ph, 1L);

        Tensor refInputIds = Tensor.fromBlob(refIdsLong, new long[]{1, refLen});
        Tensor refAttnMaskT = Tensor.fromBlob(refAttnMask, new long[]{1, refLen});
        Tensor refTypeIdsT = Tensor.fromBlob(refTypeIds, new long[]{1, refLen});
        Tensor refWord2phT = Tensor.fromBlob(refWord2ph, new long[]{refPhoneLen});  // 1D tensor

        IValue refBertResult = bertModel.forward(
                IValue.from(refInputIds),
                IValue.from(refAttnMaskT),
                IValue.from(refTypeIdsT),
                IValue.from(refWord2phT)
        );
        Tensor refBert = refBertResult.toTensor();
        Log.i(TAG, "BERT ref_bert shape: " + Arrays.toString(refBert.shape()));

        // ── Step 2: 加载参考音频 ──
        float[] refAudio = loadRefAudio(refAudioPath);
        Tensor refAudioTensor = Tensor.fromBlob(refAudio, new long[]{1, refAudio.length});
        Log.i(TAG, "参考音频: " + refAudio.length + " samples @ " + sslSampleRate + "Hz = " +
                (refAudio.length / (float) sslSampleRate) + " 秒");

        // ── Step 3: SSL → ssl_content ──
        IValue sslResult = sslModel.forward(IValue.from(refAudioTensor));
        Tensor sslContent = sslResult.toTensor();
        Log.i(TAG, "SSL ssl_content shape: " + Arrays.toString(sslContent.shape()));

        // ── Step 4: GPT-SoVITS 联级推理 ──
        // forward(ssl_content, ref_audio_sr, ref_seq, text_seq, ref_bert, text_bert, top_k) → Tensor
        Tensor topKTensor = Tensor.fromBlob(new long[]{topK}, new long[]{1});

        // ref_seq / text_seq 需要是 LongTensor (int64)
        Tensor refSeqT = Tensor.fromBlob(refIdsLong, new long[]{1, refLen});
        Tensor textSeqT = Tensor.fromBlob(targetIdsLong, new long[]{1, seqLen});

        IValue gptResult = gptSovitsModel.forward(
                IValue.from(sslContent),     // ssl_content
                IValue.from(refAudioTensor), // ref_audio_sr (原始音频，内部重采样)
                IValue.from(refSeqT),        // ref_seq
                IValue.from(textSeqT),       // text_seq
                IValue.from(refBert),        // ref_bert
                IValue.from(textBert),       // text_bert
                IValue.from(topKTensor)      // top_k
        );
        Tensor audioTensor = gptResult.toTensor();
        float[] audio = audioTensor.getDataAsFloatArray();

        Log.i(TAG, "GPT-SoVITS 输出: " + audio.length + " samples @ " + sampleRate + "Hz = " +
                String.format("%.2f", audio.length / (float) sampleRate) + " 秒");

        // ── 后处理: 语速调整 ──
        if (Math.abs(speed - 1.0f) > 0.01f) {
            audio = adjustSpeed(audio, speed);
            Log.i(TAG, "语速调整后: " + audio.length + " samples");
        }

        return audio;
    }

    /**
     * 将 float[] 音频编码为 WAV 格式字节。
     */
    public byte[] audioToWav(float[] audio) {
        int actualRate = sampleRate;
        byte[] pcm = new byte[audio.length * 2];
        for (int i = 0; i < audio.length; i++) {
            float s = Math.max(-1f, Math.min(1f, audio[i]));
            short val = (short) (s * 32767);
            pcm[i * 2] = (byte) (val & 0xFF);
            pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }

        byte[] header = createWavHeader(pcm.length, actualRate, 1, 16);
        byte[] wav = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcm, 0, wav, header.length, pcm.length);
        return wav;
    }

    public void close() {
        loaded = false;
        if (bertModel != null) { bertModel.destroy(); bertModel = null; }
        if (sslModel != null) { sslModel.destroy(); sslModel = null; }
        if (gptSovitsModel != null) { gptSovitsModel.destroy(); gptSovitsModel = null; }
    }

    // ─── 内部辅助方法 ────────────────────────────────────────

    private long[] toLongArray(int[] arr) {
        long[] out = new long[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i];
        return out;
    }

    /**
     * 加载参考音频为 float[]。
     * 支持 WAV (16-bit PCM, mono/stereo) 和 raw PCM。
     * 返回归一化到 [-1, 1] 的 float 数组。
     */
    private float[] loadRefAudio(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "无参考音频路径");
            throw new RuntimeException("需要提供参考音频文件");
        }

        File f = new File(path);
        if (!f.exists()) throw new RuntimeException("参考音频文件不存在: " + path);

        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] raw = new byte[(int) f.length()];
            int read = fis.read(raw);

            // 检查 WAV 头
            if (read > 12 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F') {
                return loadWavSamples(raw, read);
            }
            // raw PCM (16-bit mono), 假设 16kHz
            int sampleCount = read / 2;
            float[] samples = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int lo = raw[i * 2] & 0xFF;
                int hi = raw[i * 2 + 1];
                short val = (short) ((hi << 8) | lo);
                samples[i] = val / 32768f;
            }
            return samples;
        }
    }

    private float[] loadWavSamples(byte[] data, int len) {
        // 读取 WAV 头获取参数
        int channels = readShortLE(data, 22);
        int sr = readIntLE(data, 24);
        int bitsPerSample = readShortLE(data, 34);

        // 找 data chunk
        int dataOffset = 36;
        while (dataOffset + 8 < len) {
            if (data[dataOffset] == 'd' && data[dataOffset+1] == 'a'
                    && data[dataOffset+2] == 't' && data[dataOffset+3] == 'a') {
                dataOffset += 8;
                break;
            }
            int chunkSize = readIntLE(data, dataOffset + 4);
            dataOffset += 8 + chunkSize;
        }

        int bytesPerSample = bitsPerSample / 8;
        int totalSamples = (len - dataOffset) / bytesPerSample;
        float[] samples = new float[totalSamples / channels]; // mono mixdown

        if (bitsPerSample == 16) {
            for (int i = 0; i < totalSamples; i += channels) {
                int pos = dataOffset + i * 2;
                int lo = data[pos] & 0xFF;
                int hi = data[pos + 1];
                short val = (short) ((hi << 8) | lo);
                float s = val / 32768f;
                // mixdown to mono
                for (int ch = 1; ch < channels; ch++) {
                    pos = dataOffset + (i + ch) * 2;
                    lo = data[pos] & 0xFF;
                    hi = data[pos + 1];
                    val = (short) ((hi << 8) | lo);
                    s += val / 32768f;
                }
                samples[i / channels] = s / channels;
            }
        } else if (bitsPerSample == 32) {
            for (int i = 0; i < totalSamples; i += channels) {
                int pos = dataOffset + i * 4;
                int ival = readIntLE(data, pos);
                float s = ival / 2147483648f;
                for (int ch = 1; ch < channels; ch++) {
                    pos = dataOffset + (i + ch) * 4;
                    ival = readIntLE(data, pos);
                    s += ival / 2147483648f;
                }
                samples[i / channels] = s / channels;
            }
        }

        Log.i(TAG, "WAV: " + sr + "Hz " + channels + "ch " + bitsPerSample + "bit → " +
                samples.length + " mono samples");

        // 如果采样率不是 16kHz，重采样
        if (sr != sslSampleRate && sr > 0) {
            samples = resample(samples, sr, sslSampleRate);
            Log.i(TAG, "重采样 " + sr + " → " + sslSampleRate + "Hz: " + samples.length + " samples");
        }

        return samples;
    }

    private float[] resample(float[] input, int srcRate, int dstRate) {
        if (srcRate == dstRate) return input;
        double ratio = (double) dstRate / srcRate;
        int outLen = (int) (input.length * ratio);
        float[] output = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcPos = i / ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;
            if (srcIdx + 1 < input.length) {
                output[i] = (float) (input[srcIdx] * (1 - frac) + input[srcIdx + 1] * frac);
            } else if (srcIdx < input.length) {
                output[i] = input[srcIdx];
            }
        }
        return output;
    }

    /**
     * 简单语速调整：线性插值伸缩。
     */
    private float[] adjustSpeed(float[] audio, float speed) {
        int newLen = Math.round(audio.length / speed);
        float[] result = new float[newLen];
        for (int i = 0; i < newLen; i++) {
            float srcPos = i * speed;
            int srcIdx = (int) srcPos;
            float frac = srcPos - srcIdx;
            if (srcIdx + 1 < audio.length) {
                result[i] = audio[srcIdx] * (1f - frac) + audio[srcIdx + 1] * frac;
            } else if (srcIdx < audio.length) {
                result[i] = audio[srcIdx];
            }
        }
        return result;
    }

    // ─── WAV header ───

    private static byte[] createWavHeader(int dataSize, int sampleRate, int channels, int bitsPerSample) {
        byte[] h = new byte[44];
        int total = dataSize + 36;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        writeIntLE(h, 4, total);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        writeIntLE(h, 16, 16);
        writeShortLE(h, 20, (short) 1);
        writeShortLE(h, 22, (short) channels);
        writeIntLE(h, 24, sampleRate);
        writeIntLE(h, 28, byteRate);
        writeShortLE(h, 32, (short) blockAlign);
        writeShortLE(h, 34, (short) bitsPerSample);
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        writeIntLE(h, 40, dataSize);
        return h;
    }

    private static void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off+1] = (byte)(v>>8);
        b[off+2] = (byte)(v>>16); b[off+3] = (byte)(v>>24);
    }
    private static void writeShortLE(byte[] b, int off, short v) {
        b[off] = (byte) v; b[off+1] = (byte)(v>>8);
    }
    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8)
             | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
    }
    private static int readShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8);
    }
}

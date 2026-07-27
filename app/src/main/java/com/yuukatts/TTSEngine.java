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
 * YuukaTTS 引擎 —— PyTorch Mobile（Lite）版。
 *
 * 内存优化策略：串行加载，同一时刻内存中最多只有一个大模型。
 * 顺序：BERT forward → destroy → SSL forward → destroy → GPT-SoVITS forward → destroy
 *
 * 三个 TorchScript 模型（CPU 版）：
 *   BERT:  forward(input_ids, attention_mask, token_type_ids, word2ph) → Tensor
 *   SSL:   forward(ref_audio) → Tensor
 *   GPT:   forward(ssl_content, ref_audio_sr, ref_seq, text_seq, ref_bert, text_bert, top_k) → Tensor
 */
public class TTSEngine {

    private static final String TAG = "YuukaTTS";

    // 模型文件路径（加载 → forward → 销毁，不持有 Module 引用）
    private String bertPath;
    private String sslPath;
    private String gptSovitsPath;

    private WordPieceTokenizer tokenizer;

    private boolean ready = false;  // 模型路径 + tokenizer 就绪
    private int sampleRate = 32000;
    private int sslSampleRate = 16000;
    private int refSrcSampleRate = 16000;
    private int maxTextLen = 200;

    // 推理参数
    private float speedParam = 1.0f;
    private int topKParam = 15;
    private float tempParam = 0.8f;

    public boolean isReady() { return ready; }
    public int getSampleRate() { return sampleRate; }
    public float getSpeedParam() { return speedParam; }
    public int getTopKParam() { return topKParam; }
    public float getTempParam() { return tempParam; }
    public WordPieceTokenizer getTokenizer() { return tokenizer; }

    /**
     * 初始化：只记录模型路径 + 加载 tokenizer。
     * 不加载任何 PyTorch 模型到内存。
     */
    public void init(String dirPath, AssetManager assets) throws Exception {
        File dir = new File(dirPath);

        File bertFile = findModelFile(dir, "bert_model");
        File sslFile = findModelFile(dir, "ssl_model");
        File gptFile = findModelFile(dir, "gpt_sovits_model");

        if (bertFile == null) throw new RuntimeException("缺少 bert_model*.pt");
        if (sslFile == null) throw new RuntimeException("缺少 ssl_model*.pt");
        if (gptFile == null) throw new RuntimeException("缺少 gpt_sovits_model*.pt");

        bertPath = bertFile.getAbsolutePath();
        sslPath = sslFile.getAbsolutePath();
        gptSovitsPath = gptFile.getAbsolutePath();

        Log.i(TAG, "模型路径确认:");
        Log.i(TAG, "  BERT: " + bertPath + " (" + (bertFile.length() / 1048576) + " MB)");
        Log.i(TAG, "  SSL:  " + sslPath + " (" + (sslFile.length() / 1048576) + " MB)");
        Log.i(TAG, "  GPT:  " + gptSovitsPath + " (" + (gptFile.length() / 1048576) + " MB)");

        // ── 加载 tokenizer ──
        tokenizer = new WordPieceTokenizer();
        File vocabFile = new File(dir, "vocab.txt");
        if (vocabFile.exists()) {
            try (FileInputStream fis = new FileInputStream(vocabFile)) {
                tokenizer.loadVocab(fis);
                Log.i(TAG, "从模型目录加载 vocab.txt: " + tokenizer.getVocabSize() + " tokens");
            }
        } else if (assets != null) {
            try (InputStream is = assets.open("vocab.txt")) {
                tokenizer.loadVocab(is);
                Log.i(TAG, "从 assets 加载 vocab.txt: " + tokenizer.getVocabSize() + " tokens");
            }
        } else {
            throw new RuntimeException("找不到 vocab.txt");
        }

        ready = true;
        Log.i(TAG, "✅ 初始化完成（模型未加载到内存，推理时按需串行加载）");
    }

    private File findModelFile(File dir, String prefix) {
        String[] suffixes = {"_cpu_v4.ptl", "_cpu.ptl", ".ptl", "_cpu_v4.pt", "_cpu.pt", ".pt"};
        for (String suf : suffixes) {
            File f = new File(dir, prefix + suf);
            if (f.exists() && f.length() > 1024) return f;
        }
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
     * 全流程推理 —— 串行加载与卸载。
     *
     * 流程：加载 BERT → forward → destroy →
     *       加载 SSL  → forward → destroy →
     *       加载 GPT  → forward → destroy →
     *       返回音频
     */
    public float[] synthesize(
            String text,
            String refAudioPath,
            String refText,
            float speed,
            int topK,
            float temperature
    ) throws Exception {
        if (!ready) throw new IllegalStateException("模型未初始化");
        this.speedParam = speed;
        this.topKParam = topK;
        this.tempParam = temperature;

        Log.i(TAG, "━━━ 推理开始 ━━━");
        Log.i(TAG, "text: \"" + text + "\"");
        Log.i(TAG, "ref_audio: " + refAudioPath);

        // ── Step 0: Tokenize ──
        int[] targetTokenIds = tokenizer.encode(text, maxTextLen);
        long[] targetIdsLong = toLongArray(targetTokenIds);

        int[] refTokenIds;
        if (refText != null && !refText.isEmpty()) {
            refTokenIds = tokenizer.encodeReference(refText, maxTextLen);
        } else {
            refTokenIds = targetTokenIds;
        }
        long[] refIdsLong = toLongArray(refTokenIds);

        Log.i(TAG, "目标 tokens: " + targetTokenIds.length + "  参考 tokens: " + refTokenIds.length);

        // ════════════════════════════════════════════
        // Phase 1: BERT（加载 → 推理 → 销毁）
        // ════════════════════════════════════════════
        Log.i(TAG, "--- Phase 1: BERT ---");
        Module bertModel = loadModelSafely(bertPath, "BERT");
        Tensor textBert = null;
        Tensor refBert = null;

        try {
            // 目标文本 BERT
            int seqLen = targetTokenIds.length;
            int phoneLen = seqLen - 2;
            if (phoneLen < 1) phoneLen = 1;
            long[] attnMask = new long[seqLen];
            long[] typeIds = new long[seqLen];
            long[] word2ph = new long[phoneLen];
            Arrays.fill(attnMask, 1L);
            Arrays.fill(word2ph, 1L);

            Tensor inputIds = Tensor.fromBlob(targetIdsLong, new long[]{1, seqLen});
            Tensor attnMaskT = Tensor.fromBlob(attnMask, new long[]{1, seqLen});
            Tensor typeIdsT = Tensor.fromBlob(typeIds, new long[]{1, seqLen});
            Tensor word2phT = Tensor.fromBlob(word2ph, new long[]{phoneLen});

            textBert = bertModel.forward(
                    IValue.from(inputIds), IValue.from(attnMaskT),
                    IValue.from(typeIdsT), IValue.from(word2phT)
            ).toTensor();
            Log.i(TAG, "text_bert: " + Arrays.toString(textBert.shape()));

            // 参考文本 BERT
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
            Tensor refWord2phT = Tensor.fromBlob(refWord2ph, new long[]{refPhoneLen});

            refBert = bertModel.forward(
                    IValue.from(refInputIds), IValue.from(refAttnMaskT),
                    IValue.from(refTypeIdsT), IValue.from(refWord2phT)
            ).toTensor();
            Log.i(TAG, "ref_bert: " + Arrays.toString(refBert.shape()));

        } finally {
            // 销毁 BERT，释放 1.3GB
            destroyModel(bertModel, "BERT");
        }

        // ════════════════════════════════════════════
        // Phase 2: 参考音频处理 + SSL
        // ════════════════════════════════════════════
        Log.i(TAG, "--- Phase 2: 参考音频 + SSL ---");

        float[] refAudioSrc = loadRefAudioRaw(refAudioPath);
        float[] refAudio16k = refSrcSampleRate == sslSampleRate
                ? refAudioSrc
                : resample(refAudioSrc, refSrcSampleRate, sslSampleRate);
        float[] refAudio32k = (refSrcSampleRate == 32000) ? refAudioSrc
                : resample(refAudioSrc, refSrcSampleRate, 32000);

        // SSL 推理
        Module sslModel = loadModelSafely(sslPath, "SSL");
        Tensor sslContent;
        try {
            Tensor refAudio16kTensor = Tensor.fromBlob(refAudio16k, new long[]{1, refAudio16k.length});
            sslContent = sslModel.forward(IValue.from(refAudio16kTensor)).toTensor();
            Log.i(TAG, "ssl_content: " + Arrays.toString(sslContent.shape()));
        } finally {
            destroyModel(sslModel, "SSL");
        }

        // ════════════════════════════════════════════
        // Phase 3: GPT-SoVITS（加载 → 推理 → 销毁）
        // ════════════════════════════════════════════
        Log.i(TAG, "--- Phase 3: GPT-SoVITS ---");

        Module gptModel = loadModelSafely(gptSovitsPath, "GPT-SoVITS");
        try {
            int refLen = refTokenIds.length;
            int seqLen = targetTokenIds.length;
            Tensor refAudio32kTensor = Tensor.fromBlob(refAudio32k, new long[]{1, refAudio32k.length});
            Tensor topKTensor = Tensor.fromBlob(new long[]{topK}, new long[]{1});
            Tensor refSeqT = Tensor.fromBlob(refIdsLong, new long[]{1, refLen});
            Tensor textSeqT = Tensor.fromBlob(targetIdsLong, new long[]{1, seqLen});

            IValue gptResult = gptModel.forward(
                    IValue.from(sslContent),
                    IValue.from(refAudio32kTensor),
                    IValue.from(refSeqT),
                    IValue.from(textSeqT),
                    IValue.from(refBert),
                    IValue.from(textBert),
                    IValue.from(topKTensor)
            );
            Tensor audioTensor = gptResult.toTensor();
            float[] audio = audioTensor.getDataAsFloatArray();

            Log.i(TAG, "输出: " + audio.length + " samples @ " + sampleRate + "Hz = " +
                    String.format("%.2f", audio.length / (float) sampleRate) + " 秒");

            // 后处理
            if (Math.abs(speed - 1.0f) > 0.01f) {
                audio = adjustSpeed(audio, speed);
                Log.i(TAG, "语速调整后: " + audio.length + " samples");
            }

            return audio;
        } finally {
            destroyModel(gptModel, "GPT-SoVITS");
        }
    }

    // ─── 模型加载/销毁辅助 ───

    private Module loadModelSafely(String path, String name) throws Exception {
        Runtime rt = Runtime.getRuntime();
        Log.i(TAG, "加载 " + name + ": " + new File(path).getName()
                + " (free=" + (rt.freeMemory() / 1048576) + "MB)");
        System.gc();
        // 尝试禁用 NNPack（Android 上可能触发 "Mismatched Tensor types"）
        try {
            System.setProperty("org.pytorch.backend.nnpack", "disabled");
        } catch (Exception ignored) {}
        try {
            return Module.load(path);
        } catch (OutOfMemoryError e) {
            throw new RuntimeException("内存不足加载 " + name + " (" 
                    + (new File(path).length() / 1048576) + " MB)\n"
                    + "可用: " + (rt.freeMemory() / 1048576) + " MB\n"
                    + "请关闭后台应用后重试");
        }
    }

    private void destroyModel(Module model, String name) {
        if (model != null) {
            try {
                model.destroy();
                System.gc();
                Log.i(TAG, name + " 已销毁，free=" + (Runtime.getRuntime().freeMemory() / 1048576) + "MB");
            } catch (Exception e) {
                Log.w(TAG, name + " 销毁异常: " + e.getMessage());
            }
        }
    }

    // ─── 音频处理 ───

    public byte[] audioToWav(float[] audio) {
        byte[] pcm = new byte[audio.length * 2];
        for (int i = 0; i < audio.length; i++) {
            float s = Math.max(-1f, Math.min(1f, audio[i]));
            short val = (short) (s * 32767);
            pcm[i * 2] = (byte) (val & 0xFF);
            pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }
        byte[] header = createWavHeader(pcm.length, sampleRate, 1, 16);
        byte[] wav = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcm, 0, wav, header.length, pcm.length);
        return wav;
    }

    public void close() {
        ready = false;
    }

    // ─── Token 工具 ───

    private long[] toLongArray(int[] arr) {
        long[] out = new long[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i];
        return out;
    }

    // ─── 参考音频加载 ───

    private float[] loadRefAudioRaw(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new RuntimeException("需要提供参考音频文件");
        }
        File f = new File(path);
        if (!f.exists()) throw new RuntimeException("参考音频文件不存在: " + path);

        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] raw = new byte[(int) f.length()];
            int read = fis.read(raw);

            if (read > 12 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F') {
                float[] result = loadWavSamplesNoResample(raw, read);
                Log.i(TAG, "WAV raw: " + refSrcSampleRate + "Hz " + result.length + " samples = " +
                        (result.length / (float) refSrcSampleRate) + "s");
                return result;
            }
            refSrcSampleRate = 16000;
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

    private float[] loadWavSamplesNoResample(byte[] data, int len) {
        int channels = readShortLE(data, 22);
        int sr = readIntLE(data, 24);
        int bitsPerSample = readShortLE(data, 34);
        refSrcSampleRate = sr;

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
        float[] samples = new float[totalSamples / channels];

        if (bitsPerSample == 16) {
            for (int i = 0; i < totalSamples; i += channels) {
                int pos = dataOffset + i * 2;
                int lo = data[pos] & 0xFF;
                int hi = data[pos + 1];
                short val = (short) ((hi << 8) | lo);
                float s = val / 32768f;
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
        return samples;
    }

    // ─── 重采样 ───

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

    // ─── WAV header / byte 操作 ───

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

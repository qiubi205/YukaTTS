package com.yuukatts;

import android.util.Log;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.util.Arrays;

/**
 * YuukaTTS 引擎 —— PyTorch Mobile 版本。
 * 加载三个 TorchScript 模型（BERT / SSL / GPT-SoVITS），进行联级推理：
 *
 *  文本 → BERT (tokens → bert_features)
 *       → SSL (ref_audio → ssl_features)
 *       → GPT-SoVITS (text_tokens + bert_feats + ssl_feats + ref_audio → waveform)
 *
 * 模型文件放在 assets/ 或通过文件路径指定。
 */
public class TTSEngine {

    private static final String TAG = "YuukaTTS";

    private Module bertModel;
    private Module sslModel;
    private Module gptSovitsModel;

    private boolean loaded = false;
    private String modelDir;

    private float speedParam = 1.0f;
    private int topKParam = 15;
    private float tempParam = 0.8f;
    private int sampleRate = 24000;

    public boolean isLoaded() { return loaded; }
    public int getSampleRate() { return sampleRate; }
    public float getSpeedParam() { return speedParam; }
    public int getTopKParam() { return topKParam; }
    public float getTempParam() { return tempParam; }

    /**
     * 从指定目录加载三个模型文件。
     * 目录下应有: bert_model.pt, ssl_model.pt, gpt_sovits_model.pt
     */
    public void loadModels(String dirPath) throws Exception {
        if (loaded) close();

        File dir = new File(dirPath);
        File bertFile = new File(dir, "bert_model.pt");
        File sslFile = new File(dir, "ssl_model.pt");
        File gptFile = new File(dir, "gpt_sovits_model.pt");

        if (!bertFile.exists()) throw new RuntimeException("缺少 bert_model.pt");
        if (!sslFile.exists()) throw new RuntimeException("缺少 ssl_model.pt");
        if (!gptFile.exists()) throw new RuntimeException("缺少 gpt_sovits_model.pt");

        Log.i(TAG, "加载 BERT 模型: " + bertFile.getAbsolutePath());
        bertModel = Module.load(bertFile.getAbsolutePath());

        Log.i(TAG, "加载 SSL 模型: " + sslFile.getAbsolutePath());
        sslModel = Module.load(sslFile.getAbsolutePath());

        Log.i(TAG, "加载 GPT-SoVITS 模型: " + gptFile.getAbsolutePath());
        gptSovitsModel = Module.load(gptFile.getAbsolutePath());

        modelDir = dirPath;
        loaded = true;
        Log.i(TAG, "三个模型加载完成 ✅");
    }

    /**
     * 全流程推理。
     *
     * @param text          要合成的日文文本（如 "こんにちは"）
     * @param refAudioPath  参考音频文件路径（16kHz mono WAV）
     * @param refText       参考音频对应的文本（可选，可为 null）
     * @param speed         语速（0.5-2.5）
     * @param topK          top_k 采样
     * @param temperature   温度
     * @return 音频采样 float[]（24kHz）
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

        // 参数传给 gpt_sovits_model（我们假设 exported 脚本 export 时保留了 speed/top_k/temp 占位参数）
        // 实际 export 可能没有，就只在客户端做后处理

        Log.i(TAG, "推理: text=\"" + text + "\" speed=" + speed + " topK=" + topK + " temp=" + temperature);

        // ---- Step 1: 文本 tokenize + BERT ----
        int[] tokenIds = textToTokenIds(text);
        long[] tokenIdsLong = new long[tokenIds.length];
        for (int i = 0; i < tokenIds.length; i++) tokenIdsLong[i] = tokenIds[i];

        Tensor textTensor = Tensor.fromBlob(tokenIdsLong, new long[]{1, tokenIds.length});
        IValue bertOutput = bertModel.forward(IValue.from(textTensor));
        Tensor bertFeats = bertOutput.toTensor(); // shape: [1, seq_len, 768]

        Log.i(TAG, "BERT 输出 shape: " + Arrays.toString(bertFeats.shape()));

        // ---- Step 2: 加载参考音频 + SSL ----
        float[] refAudio = loadRefAudio(refAudioPath); // 载入为 float[]
        Tensor refTensor = Tensor.fromBlob(refAudio, new long[]{1, refAudio.length});
        IValue sslOutput = sslModel.forward(IValue.from(refTensor));
        Tensor sslFeats = sslOutput.toTensor(); // shape: [1, ssl_len, 768]

        Log.i(TAG, "SSL 输出 shape: " + Arrays.toString(sslFeats.shape()));

        // ---- Step 3: GPT-SoVITS 联级推理 ----
        // 传参顺序根据实际 export 脚本确定。GPT-SoVITS 的 export_torch_script.py
        // 通常输出顺序: (text_tokens, bert_feats, ssl_feats, ref_audio_wav) 或类似
        IValue result = gptSovitsModel.forward(
                IValue.from(textTensor),
                IValue.from(bertFeats),
                IValue.from(sslFeats),
                IValue.from(refTensor)
        );
        Tensor audioTensor = result.toTensor(); // shape: [1, audio_len]

        float[] audio = audioTensor.getDataAsFloatArray();

        Log.i(TAG, "GPT-SoVITS 输出: " + audio.length + " samples (" +
                (audio.length / (float) sampleRate) + " 秒)");

        // ---- 后处理: 语速调整（简单重采样） ----
        if (Math.abs(speed - 1.0f) > 0.01f) {
            audio = adjustSpeed(audio, speed);
        }

        return audio;
    }

    /**
     * 将 float[] 音频编码为 WAV 格式字节。
     */
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
        loaded = false;
    }

    // ─── 内部辅助方法 ────────────────────────────────────────

    /**
     * 基础日文 tokenizer：直接按 UTF-16 codepoint 映射。
     *  实际 GPT-SoVITS 用 BERT Japanese tokenizer (WordPiece)，
     *  需要 tokenizer.json / vocab 到客户端的映射。
     *  这里先用简版字符 token 映射，后续可替换为完整 tokenizer。
     */
    private int[] textToTokenIds(String text) {
        int[] ids = new int[text.codePointCount(0, text.length())];
        int idx = 0;
        for (int i = 0; i < text.length(); ) {
            ids[idx++] = text.codePointAt(i);
            i += Character.charCount(text.codePointAt(i));
        }
        return ids;
    }

    /**
     * 加载参考音频为 float[]（假设 16kHz mono WAV/raw）。
     */
    private float[] loadRefAudio(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            // 返回一个静默的 dummy（实际推理需要真实参考音频）
            Log.w(TAG, "无参考音频路径，使用静默填充");
            return new float[8000]; // 0.5s @ 16kHz
        }

        File f = new File(path);
        if (!f.exists()) {
            Log.w(TAG, "参考音频文件不存在: " + path);
            return new float[8000];
        }

        // 简化 WAV loader (16-bit PCM, 1ch)
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] raw = new byte[(int) f.length()];
            int read = fis.read(raw);
            // 跳过 44 字节 WAV 头
            int dataOffset = 44;
            int sampleCount = (read - dataOffset) / 2;
            float[] samples = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int lo = raw[dataOffset + i * 2] & 0xFF;
                int hi = raw[dataOffset + i * 2 + 1];
                short val = (short) ((hi << 8) | lo);
                samples[i] = val / 32768f;
            }
            return samples;
        }
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

    // WAV header
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
}

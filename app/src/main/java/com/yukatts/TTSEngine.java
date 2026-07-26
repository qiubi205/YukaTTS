package com.yukatts;

import android.content.Context;
import android.util.Log;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.*;

/**
 * Generic ONNX TTS Engine.
 * Loads any ONNX model from a file path, inspects its inputs,
 * and runs inference with provided tensor data.
 */
public class TTSEngine {

    private static final String TAG = "YukaTTS_Engine";

    /** Info about one model input for UI display / configuration */
    public static class ModelInputInfo {
        public final String name;
        public final OnnxJavaType type;
        public final long[] shape;
        public final long numElements;
        public final String shapeStr;

        ModelInputInfo(String name, OnnxJavaType type, long[] shape) {
            this.name = name;
            this.type = type;
            this.shape = shape;
            long n = 1;
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < shape.length; i++) {
                if (i > 0) sb.append(",");
                if (shape[i] == -1) {
                    sb.append("?");
                } else {
                    sb.append(shape[i]);
                    n *= shape[i];
                }
            }
            sb.append(")");
            this.shapeStr = sb.toString();
            this.numElements = n;
        }
    }

    /** Info about one model output */
    public static class ModelOutputInfo {
        public final String name;
        public final OnnxJavaType type;
        public final long[] shape;
        public final String shapeStr;

        ModelOutputInfo(String name, OnnxJavaType type, long[] shape) {
            this.name = name;
            this.type = type;
            this.shape = shape;
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < shape.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(shape[i]);
            }
            sb.append(")");
            this.shapeStr = sb.toString();
        }
    }

    // Native audio utils
    static {
        try {
            System.loadLibrary("yuka_tts_bridge");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native bridge not loaded: " + e.getMessage());
        }
    }

    private native boolean nativeNormalizeAudio(float[] audio, float targetPeak);
    private native byte[] nativeFloatToPCM16(float[] audio);
    private native byte[] nativeCreateWavHeader(int dataSize, int sampleRate, int channels, int bitsPerSample);

    private OrtEnvironment env;
    private OrtSession session;
    private boolean loaded = false;
    private String modelPath;

    // Model metadata (populated after load)
    private final LinkedHashMap<String, ModelInputInfo> inputInfos = new LinkedHashMap<>();
    private final LinkedHashMap<String, ModelOutputInfo> outputInfos = new LinkedHashMap<>();
    private int sampleRate = 24000; // default, overridable

    /**
     * Load an ONNX model from a file path.
     */
    public void loadModel(String filePath) throws Exception {
        if (loaded) close();

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(4);
        options.setCPUArenaAllocator(true);

        session = env.createSession(filePath, options);
        modelPath = filePath;
        loaded = true;

        // Read input info
        inputInfos.clear();
        for (Map.Entry<String, NodeInfo> entry : session.getInputInfo().entrySet()) {
            String name = entry.getKey();
            NodeInfo node = entry.getValue();
            OnnxJavaType type = node.getInfo() instanceof TensorInfo ? ((TensorInfo) node.getInfo()).type : null;
            long[] shape = node.getInfo() instanceof TensorInfo ? ((TensorInfo) node.getInfo()).getShape() : new long[0];
            inputInfos.put(name, new ModelInputInfo(name, type, shape));
        }

        // Read output info
        outputInfos.clear();
        for (Map.Entry<String, NodeInfo> entry : session.getOutputInfo().entrySet()) {
            String name = entry.getKey();
            NodeInfo node = entry.getValue();
            OnnxJavaType type = node.getInfo() instanceof TensorInfo ? ((TensorInfo) node.getInfo()).type : null;
            long[] shape = node.getInfo() instanceof TensorInfo ? ((TensorInfo) node.getInfo()).getShape() : new long[0];
            outputInfos.put(name, new ModelOutputInfo(name, type, shape));
        }

        Log.i(TAG, "Model loaded: " + filePath);
        for (ModelInputInfo info : inputInfos.values()) {
            Log.i(TAG, "  Input: " + info.name + " " + info.shapeStr + " type=" + info.type);
        }
        for (ModelOutputInfo info : outputInfos.values()) {
            Log.i(TAG, "  Output: " + info.name + " " + info.shapeStr + " type=" + info.type);
        }
    }

    public boolean isLoaded() { return loaded; }
    public String getModelPath() { return modelPath; }

    public LinkedHashMap<String, ModelInputInfo> getInputInfos() { return inputInfos; }
    public LinkedHashMap<String, ModelOutputInfo> getOutputInfos() { return outputInfos; }

    public void setSampleRate(int sr) { this.sampleRate = sr; }
    public int getSampleRate() { return sampleRate; }

    /**
     * Run inference with provided tensor values.
     * Each entry maps: inputName -> float[] (or int64[] based on tensor type)
     *
     * Returns the first float audio output as float[].
     * If no float output found, returns the raw output values as byte[] in audio field (use with caution).
     */
    public float[] runInference(Map<String, Object> inputData) throws Exception {
        if (!loaded) throw new IllegalStateException("Model not loaded");

        Map<String, OnnxTensor> ortInputs = new HashMap<>();
        try {
            for (Map.Entry<String, Object> entry : inputData.entrySet()) {
                String name = entry.getKey();
                ModelInputInfo info = inputInfos.get(name);
                if (info == null) {
                    throw new IllegalArgumentException("Unknown input: " + name);
                }

                OnnxTensor tensor;
                if (info.type == OnnxJavaType.FLOAT) {
                    float[] data = (float[]) entry.getValue();
                    long[] shape = replaceDynamicDims(info.shape, data.length);
                    tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
                } else if (info.type == OnnxJavaType.INT64) {
                    long[] data = (long[]) entry.getValue();
                    long[] shape = replaceDynamicDims(info.shape, data.length);
                    tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape);
                } else if (info.type == OnnxJavaType.INT32) {
                    int[] data = (int[]) entry.getValue();
                    long[] shape = replaceDynamicDims(info.shape, data.length);
                    tensor = OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(data), shape);
                } else if (info.type == OnnxJavaType.INT8) {
                    byte[] data = (byte[]) entry.getValue();
                    long[] shape = replaceDynamicDims(info.shape, data.length);
                    tensor = OnnxTensor.createTensor(env, java.nio.ByteBuffer.wrap(data), shape);
                } else {
                    throw new IllegalArgumentException("Unsupported input type: " + info.type + " for " + name);
                }
                ortInputs.put(name, tensor);
            }

            OrtSession.Result result = session.run(ortInputs);

            // Find the first float audio output
            float[] audio = null;
            for (Map.Entry<String, OnnxValue> out : result) {
                if (out.getValue() instanceof OnnxTensor) {
                    OnnxTensor tensor = (OnnxTensor) out.getValue();
                    if (tensor.getInfo() instanceof TensorInfo && ((TensorInfo) tensor.getInfo()).type == OnnxJavaType.FLOAT) {
                        long[] shape = tensor.getInfo().getShape();
                        int total = 1;
                        for (long s : shape) total *= (int) s;
                        float[] buf = new float[total];
                        ((FloatBuffer) tensor.getValue()).get(buf);
                        audio = buf;
                        Log.i(TAG, "Output " + out.getKey() + " shape=" + java.util.Arrays.toString(shape));
                        break;
                    }
                }
            }
            result.close();

            if (audio == null) {
                throw new RuntimeException("No float output tensor found");
            }
            return audio;

        } finally {
            for (OnnxTensor t : ortInputs.values()) {
                try { t.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Normalize audio and pack into WAV format.
     */
    public byte[] audioToWav(float[] audio) {
        byte[] pcm = new byte[audio.length * 2];
        for (int i = 0; i < audio.length; i++) {
            float s = audio[i];
            if (s > 1f) s = 1f;
            if (s < -1f) s = -1f;
            short val = (short) (s * 32767);
            pcm[i * 2] = (byte) (val & 0xFF);
            pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }

        byte[] header = createWavHeaderJava(pcm.length, sampleRate, 1, 16);

        byte[] wav = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcm, 0, wav, header.length, pcm.length);
        return wav;
    }

    public void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (env != null) env.close(); } catch (Exception ignored) {}
        loaded = false;
        inputInfos.clear();
        outputInfos.clear();
    }

    // Helper: replace -1 (dynamic) dims with actual value
    private static long[] replaceDynamicDims(long[] shape, long totalElements) {
        long[] result = shape.clone();
        if (result.length == 0) return result;
        int unknownIdx = -1;
        long knownProduct = 1;
        for (int i = 0; i < result.length; i++) {
            if (result[i] == -1 || result[i] == 0) {
                unknownIdx = i;
            } else {
                knownProduct *= result[i];
            }
        }
        if (unknownIdx >= 0) {
            result[unknownIdx] = totalElements / knownProduct;
        }
        return result;
    }

    private static byte[] createWavHeaderJava(int dataSize, int sampleRate, int channels, int bitsPerSample) {
        byte[] header = new byte[44];
        int totalSize = dataSize + 36;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeIntLE(header, 4, totalSize);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeIntLE(header, 16, 16);
        writeShortLE(header, 20, (short) 1);
        writeShortLE(header, 22, (short) channels);
        writeIntLE(header, 24, sampleRate);
        writeIntLE(header, 28, byteRate);
        writeShortLE(header, 32, (short) blockAlign);
        writeShortLE(header, 34, (short) bitsPerSample);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeIntLE(header, 40, dataSize);
        return header;
    }

    private static void writeIntLE(byte[] buf, int offset, int val) {
        buf[offset] = (byte)(val); buf[offset+1] = (byte)(val>>8);
        buf[offset+2] = (byte)(val>>16); buf[offset+3] = (byte)(val>>24);
    }
    private static void writeShortLE(byte[] buf, int offset, short val) {
        buf[offset] = (byte)(val); buf[offset+1] = (byte)(val>>8);
    }
}

package com.yukatts;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int MAX_INPUT_VALUES_DISPLAY = 100;

    private TTSEngine engine;
    private Button btnSelectModel;
    private TextView modelInfoText;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button btnGenerate;
    private Button btnPlay;
    private Button btnSave;
    private EditText textInput;
    private View tensorInputsContainer;
    private LinearLayout tensorInputsLayout;
    private Button btnParamsHelp;
    // New params fields
    private Button btnRefAudio, btnRefDefault, btnResetParams;
    private TextView refAudioName;
    private EditText refTextInput;
    private View paramsContainer;
    private SeekBar speedSeekbar, topkSeekbar, tempSeekbar;
    private TextView speedValue, topkValue, tempValue;
    private Uri refAudioUri;
    private String refAudioPath;


    private byte[] lastWav;
    private String lastModelPath;

    // Dynamically created tensor input fields
    private final ArrayList<EditText> tensorFields = new ArrayList<>();
    private final ArrayList<String> tensorNames = new ArrayList<>();

    private final ActivityResultLauncher<String> filePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) loadModelFromUri(uri);
            });

    private final ActivityResultLauncher<String> storagePerm =

    private final ActivityResultLauncher<String> refAudioPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    refAudioUri = uri;
                    refAudioName.setText("已选择：" + getFileNameFromUri(uri));
                    // Copy to cache for processing
                    copyRefAudioToCache(uri);
                }
            });
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) saveAudio();
                else Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = new TTSEngine();

        btnSelectModel = findViewById(R.id.btn_select_model);
        modelInfoText = findViewById(R.id.model_info);
        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress_bar);
        btnGenerate = findViewById(R.id.btn_generate);
        btnPlay = findViewById(R.id.btn_play);
        btnSave = findViewById(R.id.btn_save);
        textInput = findViewById(R.id.text_input);
        tensorInputsContainer = findViewById(R.id.tensor_inputs_container);
        tensorInputsLayout = findViewById(R.id.tensor_inputs_layout);
        // New param controls
        btnRefAudio = findViewById(R.id.btn_ref_audio);
        btnRefDefault = findViewById(R.id.btn_ref_default);
        refAudioName = findViewById(R.id.ref_audio_name);
        refTextInput = findViewById(R.id.ref_text_input);
        paramsContainer = findViewById(R.id.params_container);
        btnResetParams = findViewById(R.id.btn_reset_params);
        speedSeekbar = findViewById(R.id.speed_seekbar);
        topkSeekbar = findViewById(R.id.topk_seekbar);
        tempSeekbar = findViewById(R.id.temp_seekbar);
        speedValue = findViewById(R.id.speed_value);
        topkValue = findViewById(R.id.topk_value);
        tempValue = findViewById(R.id.temp_value);
        btnParamsHelp = findViewById(R.id.btn_params_help);

        btnSelectModel.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ uses the new media/image picker
                filePicker.launch("application/octet-stream");
            } else {
                filePicker.launch("*/*");
            }
        });

        btnGenerate.setOnClickListener(v -> {
            if (engine.isLoaded()) {
                setBusy(true);
                generate();
            } else {
                Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show();
            }
        });

        btnPlay.setOnClickListener(v -> {
            if (lastWav != null) playAudio(lastWav);
            else Toast.makeText(this, "请先生成语音", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            if (lastWav != null) checkStorageAndSave();
            else Toast.makeText(this, "请先生成语音", Toast.LENGTH_SHORT).show();
        });

        btnParamsHelp.setOnClickListener(v -> showParamsHelp());
    }

    private void loadModelFromUri(Uri uri) {
        setBusy(true);
        statusText.setText("正在加载模型…");

        // ---- New param control listeners ----

        btnRefAudio.setOnClickListener(v -> refAudioPicker.launch("audio/*"));

        btnRefDefault.setOnClickListener(v -> {
            // Use default reference audio from assets
            loadDefaultRefAudio();
        });

        btnResetParams.setOnClickListener(v -> {
            speedSeekbar.setProgress(50);
            topkSeekbar.setProgress(15);
            tempSeekbar.setProgress(80);
        });

        speedSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float val = 0.5f + p / 50.0f;
                speedValue.setText(String.format("%.1f", val));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        topkSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = Math.max(1, p);
                topkValue.setText(String.valueOf(val));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        tempSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float val = p / 100.0f;
                tempValue.setText(String.format("%.2f", val));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Show params container after model is loaded (hide now, show in updateModelInfo)
        paramsContainer.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                // Copy to cache dir for ONNX Runtime file access
                String fileName = "model_" + System.currentTimeMillis() + ".onnx";
                File cacheFile = new File(getCacheDir(), fileName);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }

                engine.loadModel(cacheFile.getAbsolutePath());
                lastModelPath = cacheFile.getAbsolutePath();

                runOnUiThread(() -> {
                    updateModelInfo();
                    buildTensorInputs();
                    setBusy(false);
                    statusText.setText("模型已加载！" + getModelFileSize(cacheFile));
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("加载失败：" + e.getMessage());
                    Toast.makeText(this, "出错：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String getModelFileSize(File f) {
        long sz = f.length();
        if (sz < 1024) return sz + "B";
        if (sz < 1048576) return (sz / 1024) + "KB";
        return String.format("%.1fMB", sz / 1048576.0);
    }

    private void updateModelInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("模型已加载\n\n");
        sb.append("输入：\n");
        for (TTSEngine.ModelInputInfo info : engine.getInputInfos().values()) {
            sb.append("  • ").append(info.name).append(" ").append(info.shapeStr)
              .append(" ").append(info.type).append("\n");
        }
        sb.append("\n输出：\n");
        for (TTSEngine.ModelOutputInfo info : engine.getOutputInfos().values()) {
            sb.append("  • ").append(info.name).append(" ").append(info.shapeStr)
              .append(" ").append(info.type).append("\n");
        }
        modelInfoText.setText(sb.toString());
        modelInfoText.setVisibility(View.VISIBLE);
    }

    private void buildTensorInputs() {
        tensorInputsLayout.removeAllViews();
        tensorFields.clear();
        tensorNames.clear();

        LinkedHashMap<String, TTSEngine.ModelInputInfo> inputs = engine.getInputInfos();
        if (inputs.isEmpty()) return;

        // For each input, create a labeled text field
        for (TTSEngine.ModelInputInfo info : inputs.values()) {
            // Label
            TextView label = new TextView(this);
            label.setText(info.name + "  " + info.shapeStr + "  " + info.type);
            label.setTextColor(0xffaaaaaa);
            label.setTextSize(13);
            label.setPadding(0, 16, 0, 4);
            tensorInputsLayout.addView(label);

            // Hint text
            String hint;
            if (info.type == ai.onnxruntime.OnnxJavaType.FLOAT) {
                hint = "例: 0.5, -0.3, 0.8,...";
            } else if (info.type == ai.onnxruntime.OnnxJavaType.INT64) {
                hint = "例: 12, 34, 56,...";
            } else {
                hint = "逗号分隔的数值";
            }

            EditText edit = new EditText(this);
            edit.setHint(hint);
            edit.setTextColor(0xffffffff);
            edit.setHintTextColor(0xff555555);
            edit.setBackgroundResource(android.R.drawable.editbox_background);
            edit.setPadding(12, 8, 12, 8);
            edit.setTextSize(14);
            edit.setLines(1);

            // Pre-fill small inputs with zeros
            long numEl = info.numElements;
            if (numEl > 0 && numEl <= 32) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < (int)Math.min(numEl, MAX_INPUT_VALUES_DISPLAY); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(info.type == ai.onnxruntime.OnnxJavaType.INT64 ? "0" : "0.0");
                }
                if (numEl > MAX_INPUT_VALUES_DISPLAY) sb.append(", ...");
                edit.setText(sb.toString());
            }

            tensorInputsLayout.addView(edit);
            tensorFields.add(edit);
            tensorNames.add(info.name);

            // Small size hint
            TextView sizeHint = new TextView(this);
            sizeHint.setText("约 " + numEl + " 个值" + (info.shape[0]==-1 ? "（动态）" : ""));
            sizeHint.setTextColor(0xff666666);
            sizeHint.setTextSize(11);
            sizeHint.setPadding(0, 0, 0, 8);
            tensorInputsLayout.addView(sizeHint);
        }

        // Simple mode hint
        TextView modeHint = new TextView(this);
        modeHint.setText("或输入文本，自动转字符编码");
        modeHint.setTextColor(0xff888888);
        modeHint.setTextSize(12);
        modeHint.setPadding(0, 20, 0, 0);
        tensorInputsLayout.addView(modeHint);

        tensorInputsContainer.setVisibility(View.VISIBLE);
        paramsContainer.setVisibility(View.VISIBLE);
    }

    private void generate() {
        new Thread(() -> {
            try {
                Map<String, Object> inputData = new java.util.LinkedHashMap<>();

                // Try reading from tensor fields first
                if (!tensorFields.isEmpty()) {
                    for (int i = 0; i < tensorFields.size() && i < tensorNames.size(); i++) {
                        String val = tensorFields.get(i).getText().toString().trim();
                        if (val.isEmpty()) continue;

                        TTSEngine.ModelInputInfo info = engine.getInputInfos().get(tensorNames.get(i));
                        if (info == null) continue;

                        String[] parts = val.split(",");
                        if (info.type == ai.onnxruntime.OnnxJavaType.FLOAT) {
                            float[] arr = new float[parts.length];
                            for (int j = 0; j < parts.length; j++)
                                arr[j] = Float.parseFloat(parts[j].trim());
                            inputData.put(tensorNames.get(i), arr);
                        } else if (info.type == ai.onnxruntime.OnnxJavaType.INT64) {
                            long[] arr = new long[parts.length];
                            for (int j = 0; j < parts.length; j++)
                                arr[j] = Long.parseLong(parts[j].trim());
                            inputData.put(tensorNames.get(i), arr);
                        } else if (info.type == ai.onnxruntime.OnnxJavaType.INT32) {
                            int[] arr = new int[parts.length];
                            for (int j = 0; j < parts.length; j++)
                                arr[j] = Integer.parseInt(parts[j].trim());
                            inputData.put(tensorNames.get(i), arr);
                        }
                    }
                }

                // If no tensor fields filled, try text input as fallback (char->int64)
                if (inputData.isEmpty()) {
                    String txt = textInput.getText().toString().trim();
                    if (!txt.isEmpty()) {
                        // Find first int64 input and fill it with char codes
                        for (TTSEngine.ModelInputInfo info : engine.getInputInfos().values()) {
                            if (info.type == ai.onnxruntime.OnnxJavaType.INT64) {
                                long[] charCodes = new long[txt.length()];
                                for (int j = 0; j < txt.length(); j++)
                                    charCodes[j] = txt.charAt(j);
                                inputData.put(info.name, charCodes);
                                break;
                            }
                        }
                    }
                }

                if (inputData.isEmpty()) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        statusText.setText("请输入张量数值或文本");
                    });
                    return;
                }

                runOnUiThread(() -> statusText.setText("正在推理…"));
                engine.runInference(inputData, getSpeedParam(), getTopKParam(), getTempParam());
                lastWav = engine.audioToWav(audio);

                float durationSec = audio.length / (float) engine.getSampleRate();
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText(String.format("完成！%.1f 秒音频（%.0f KB）", 
                            durationSec, lastWav.length / 1024.0));
                    btnPlay.setEnabled(true);
                    btnSave.setEnabled(true);
                    playAudio(lastWav);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("出错：" + e.getMessage());
                    Toast.makeText(this, "推理失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void playAudio(byte[] wavData) {
        try {
            if (wavData.length < 44) return;
            int dataOffset = 44;
            int pcmLen = wavData.length - dataOffset;
            int minSize = AudioTrack.getMinBufferSize(
                    engine.getSampleRate(),
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(engine.getSampleRate())
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minSize, pcmLen))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            track.write(wavData, dataOffset, pcmLen);
            track.play();
            track.release(); // one-shot play
        } catch (Exception e) {
            statusText.setText("播放失败：" + e.getMessage());
        }
    }

    private void checkStorageAndSave() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+ scoped storage - save to Downloads directly
            saveAudio();
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            saveAudio();
        } else {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void saveAudio() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String name = "tts_" + ts + ".wav";
            File dir;
            if (Build.VERSION.SDK_INT >= 30) {
                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            } else {
                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            }
            dir.mkdirs();
            File file = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(lastWav);
            }
            statusText.setText("已保存：" + name);
            Toast.makeText(this, "已保存到 Downloads/" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            statusText.setText("保存失败：" + e.getMessage());
        }
    }

    private void showParamsHelp() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("使用说明");
        b.setMessage(
                "1. 点击「选择 ONNX 模型」选取 .onnx 文件\n" +
                "2. 选择一段参考音频（或使用默认）\n" +
                "3. 输入想要合成的文本\n" +
                "4. 可选：填写参考音频对应的文本\n" +
                "5. 调节语速、top_k、随机度参数\n" +
                "6. 点击「生成语音」→「播放」→「保存」\n\n" +
                "语速：0.5~2.5，默认 1.0\n" +
                "top_k：采样范围，越小越稳定\n" +
                "随机度：温度参数，越高越多样"
        );
        b.setPositiveButton("确定", null);
        b.show();
    }


    private String getFileNameFromUri(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path != null) {
            int cut = path.lastIndexOf('/');
            if (cut >= 0) path = path.substring(cut + 1);
        }
        return path != null ? path : "audio";
    }

    private void copyRefAudioToCache(Uri uri) {
        new Thread(() -> {
            try {
                File cacheFile = new File(getCacheDir(), "ref_audio.wav");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                refAudioPath = cacheFile.getAbsolutePath();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载参考音频失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void loadDefaultRefAudio() {
        try {
            // Try to copy default ref audio from assets
            String[] files = getAssets().list("");
            boolean found = false;
            for (String f : files) {
                if (f.startsWith("ref_audio") && (f.endsWith(".wav") || f.endsWith(".ogg"))) {
                    File cacheFile = new File(getCacheDir(), f);
                    try (InputStream is = getAssets().open(f);
                         FileOutputStream fos = new FileOutputStream(cacheFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    refAudioPath = cacheFile.getAbsolutePath();
                    refAudioName.setText("已选择：默认参考音频");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Toast.makeText(this, "未找到默认参考音频，请手动选择", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "未找到默认参考音频，请手动选择", Toast.LENGTH_SHORT).show();
        }
    }

    private float getSpeedParam() {
        return 0.5f + speedSeekbar.getProgress() / 50.0f;
    }

    private int getTopKParam() {
        return Math.max(1, topkSeekbar.getProgress());
    }

    private float getTempParam() {
        return tempSeekbar.getProgress() / 100.0f;
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnSelectModel.setEnabled(!busy);
        btnGenerate.setEnabled(!busy);
        btnPlay.setEnabled(false);
        btnSave.setEnabled(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.close();
    }
}

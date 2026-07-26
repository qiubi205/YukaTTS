package com.yuukatts;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.util.Log;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TTSEngine engine;
    private Button btnLoadModel;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button btnGenerate;
    private Button btnPlay;
    private Button btnSave;
    private EditText textInput;

    // 参考音频
    private Button btnRefAudio, btnRefDefault;
    private TextView refAudioName;
    private EditText refTextInput;
    private Uri refAudioUri;
    private String refAudioPath;

    // 参数
    private SeekBar speedSeekbar, topkSeekbar, tempSeekbar;
    private TextView speedValue, topkValue, tempValue;

    private byte[] lastWav;
    private boolean generating = false;

    private final ActivityResultLauncher<String> storagePerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) saveAudio();
                else Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show();
            });

    private final ActivityResultLauncher<String> refAudioPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    refAudioUri = uri;
                    refAudioName.setText("已选择：" + getFileNameFromUri(uri));
                    copyRefAudioToCache(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = new TTSEngine();

        btnLoadModel = findViewById(R.id.btn_load_model);
        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress_bar);
        btnGenerate = findViewById(R.id.btn_generate);
        btnPlay = findViewById(R.id.btn_play);
        btnSave = findViewById(R.id.btn_save);
        textInput = findViewById(R.id.text_input);

        btnRefAudio = findViewById(R.id.btn_ref_audio);
        btnRefDefault = findViewById(R.id.btn_ref_default);
        refAudioName = findViewById(R.id.ref_audio_name);
        refTextInput = findViewById(R.id.ref_text_input);
        speedSeekbar = findViewById(R.id.speed_seekbar);
        topkSeekbar = findViewById(R.id.topk_seekbar);
        tempSeekbar = findViewById(R.id.temp_seekbar);
        speedValue = findViewById(R.id.speed_value);
        topkValue = findViewById(R.id.topk_value);
        tempValue = findViewById(R.id.temp_value);

        // ── 加载模型 ──
        btnLoadModel.setOnClickListener(v -> {
            if (!engine.isLoaded()) {
                loadModels();
            } else {
                Toast.makeText(this, "模型已加载", Toast.LENGTH_SHORT).show();
            }
        });

        // ── 生成 ──
        btnGenerate.setOnClickListener(v -> {
            if (!engine.isLoaded()) {
                Toast.makeText(this, "请先加载模型", Toast.LENGTH_SHORT).show();
                return;
            }
            String txt = textInput.getText().toString().trim();
            if (txt.isEmpty()) {
                Toast.makeText(this, "请输入文本", Toast.LENGTH_SHORT).show();
                return;
            }
            if (refAudioPath == null || refAudioPath.isEmpty()) {
                Toast.makeText(this, "请先选择参考音频", Toast.LENGTH_SHORT).show();
                return;
            }
            generate(txt);
        });

        // ── 播放 ──
        btnPlay.setOnClickListener(v -> {
            if (lastWav != null) playAudio(lastWav);
            else Toast.makeText(this, "请先生成语音", Toast.LENGTH_SHORT).show();
        });

        // ── 保存 ──
        btnSave.setOnClickListener(v -> {
            if (lastWav != null) checkStorageAndSave();
            else Toast.makeText(this, "请先生成语音", Toast.LENGTH_SHORT).show();
        });

        // ── 参考音频 ──
        btnRefAudio.setOnClickListener(v -> refAudioPicker.launch("audio/*"));
        btnRefDefault.setOnClickListener(v -> loadDefaultRefAudio());

        // ── 参数监听 ──
        speedSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                speedValue.setText(String.format("%.1f", 0.5f + p / 50f));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        topkSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                topkValue.setText(String.valueOf(Math.max(1, p)));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        tempSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                tempValue.setText(String.format("%.2f", p / 100f));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ──────── 模型加载 ────────

    private void loadModels() {
        setBusy(true);
        statusText.setText("正在加载模型…");

        new Thread(() -> {
            try {
                // 尝试从 assets 加载
                try {
                    engine.loadModelsFromAssets(this);
                    runOnUiThread(() -> {
                        setBusy(false);
                        statusText.setText("✅ 模型加载完成！");
                        btnLoadModel.setEnabled(false);
                        Toast.makeText(this, "模型已就绪", Toast.LENGTH_SHORT).show();
                    });
                    return;
                } catch (Exception e) {
                    Log.w("YuukaTTS", "assets 加载失败，尝试外部存储: " + e.getMessage());
                }

                // 尝试从外部存储 models/yuka/ 目录加载
                File extDir = Environment.getExternalStorageDirectory();
                File modelDir = new File(extDir, "models/yuka");
                if (modelDir.exists()) {
                    engine.loadModels(modelDir.getAbsolutePath());
                    runOnUiThread(() -> {
                        setBusy(false);
                        statusText.setText("✅ 模型加载完成！");
                        btnLoadModel.setEnabled(false);
                        Toast.makeText(this, "模型已就绪", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 都不行，提示用户
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("❌ 未找到模型文件");
                    Toast.makeText(this,
                            "请将 bert_model.pt, ssl_model.pt, gpt_sovits_model.pt\n放入手机存储的 models/yuka/ 目录",
                            Toast.LENGTH_LONG).show();
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

    // ──────── 推理 ────────

    private void generate(String text) {
        if (generating) return;
        generating = true;
        setBusy(true);
        statusText.setText("正在生成语音…");

        float speed = getSpeedParam();
        int topK = getTopKParam();
        float temp = getTempParam();
        String refAudio = refAudioPath;
        String refText = refTextInput.getText().toString().trim();

        new Thread(() -> {
            try {
                float[] audio;
                if (refAudio != null && !refAudio.isEmpty()) {
                    audio = engine.synthesize(text, refAudio, refText, speed, topK, temp);
                } else {
                    audio = engine.synthesizeSimple(text, speed, topK, temp);
                }
                lastWav = engine.audioToWav(audio);

                float dur = audio.length / (float) engine.getSampleRate();
                runOnUiThread(() -> {
                    generating = false;
                    setBusy(false);
                    statusText.setText(String.format("✅ 完成！%.1f 秒音频（%.0f KB）",
                            dur, lastWav.length / 1024f));
                    btnPlay.setEnabled(true);
                    btnSave.setEnabled(true);
                    // 自动播放
                    playAudio(lastWav);
                });

            } catch (Exception e) {
                Log.e("YuukaTTS", "推理失败", e);
                runOnUiThread(() -> {
                    generating = false;
                    setBusy(false);
                    statusText.setText("❌ 推理失败：" + e.getMessage());
                    Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ──────── 音频播放 ────────

    private void playAudio(byte[] wavData) {
        try {
            if (wavData.length < 44) return;
            int pcmLen = wavData.length - 44;
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

            track.write(wavData, 44, pcmLen);
            track.play();
            track.release();
        } catch (Exception e) {
            Log.w("YuukaTTS", "播放失败", e);
        }
    }

    // ──────── 保存 ────────

    private void checkStorageAndSave() {
        if (Build.VERSION.SDK_INT >= 30) {
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
            String name = "yuka_" + ts + ".wav";
            File dir;
            if (Build.VERSION.SDK_INT >= 30) {
                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            } else {
                dir = new File(Environment.getExternalStorageDirectory(), "YuukaTTS");
            }
            dir.mkdirs();
            File file = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(lastWav);
            }
            statusText.setText("已保存到 " + dir.getName() + "/" + name);
            Toast.makeText(this, "已保存：" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            statusText.setText("保存失败：" + e.getMessage());
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ──────── 参考音频 ────────

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
                Log.i("YuukaTTS", "参考音频已缓存: " + refAudioPath);
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "加载参考音频失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void loadDefaultRefAudio() {
        try {
            String[] files = getAssets().list("");
            if (files == null) {
                Toast.makeText(this, "未找到默认参考音频", Toast.LENGTH_SHORT).show();
                return;
            }
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
                    refAudioName.setText("默认参考音频");
                    Log.i("YuukaTTS", "默认参考音频: " + refAudioPath);
                    return;
                }
            }
            Toast.makeText(this, "未找到默认参考音频", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "未找到默认参考音频", Toast.LENGTH_SHORT).show();
        }
    }

    // ──────── 参数 ────────

    private float getSpeedParam() {
        return 0.5f + speedSeekbar.getProgress() / 50f;
    }
    private int getTopKParam() {
        return Math.max(1, topkSeekbar.getProgress());
    }
    private float getTempParam() {
        return tempSeekbar.getProgress() / 100f;
    }

    // ──────── UI状态 ────────

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnLoadModel.setEnabled(!busy && !engine.isLoaded());
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

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
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private File crashLogFile;

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

    // 模型文件路径
    private String bertPath, sslPath, gptPath;
    private String pendingModelType = null;

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

    private final ActivityResultLauncher<String> modelFilePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null || pendingModelType == null) return;
                copyModelFile(uri, pendingModelType);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 全局崩溃日志 ──
        crashLogFile = new File(getExternalFilesDir(null), "crash_log.txt");
        CrashLogger.init(crashLogFile);
        CrashLogger.write("onCreate", "APP v1.2.0 (serial load)");
        CrashLogger.write("onCreate", "ABI=" + Build.CPU_ABI + "/" + Build.CPU_ABI2);
        CrashLogger.write("onCreate", "Model=" + Build.MODEL + " SDK=" + Build.VERSION.SDK_INT);

        setContentView(R.layout.activity_main);

        // 测试 PyTorch Lite
        try {
            CrashLogger.write("onCreate", "检查 PyTorch Lite...");
            Class.forName("org.pytorch.Module");
            try {
                System.loadLibrary("pytorch_jni_lite");
                CrashLogger.write("onCreate", "libpytorch_jni_lite OK");
            } catch (UnsatisfiedLinkError e) {
                try {
                    System.loadLibrary("pytorch_jni");
                    CrashLogger.write("onCreate", "降级 libpytorch_jni");
                } catch (UnsatisfiedLinkError e2) {
                    CrashLogger.write("onCreate", "FATAL: 无 native 库");
                }
            }
        } catch (Exception e) {
            CrashLogger.write("onCreate", "PyTorch 不可用: " + e.getMessage());
        }

        engine = new TTSEngine();
        CrashLogger.write("onCreate", "TTSEngine 创建完成");

        btnLoadModel = findViewById(R.id.btn_load_model);
        statusText = findViewById(R.id.status_text);
        statusText.setText("日志: " + crashLogFile.getAbsolutePath());
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

        textInput.setHint("输入日文文本…（例：こんにちは）");

        // ── 模型选择 ──
        btnLoadModel.setOnClickListener(v -> {
            if (engine.isReady()) {
                Toast.makeText(this, "模型已就绪", Toast.LENGTH_SHORT).show();
                return;
            }
            showModelPickerDialog();
        });

        // ── 生成 ──
        btnGenerate.setOnClickListener(v -> {
            if (!engine.isReady()) {
                Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show();
                return;
            }
            String txt = textInput.getText().toString().trim();
            if (txt.isEmpty()) { Toast.makeText(this, "请输入文本", Toast.LENGTH_SHORT).show(); return; }
            if (refAudioPath == null || refAudioPath.isEmpty()) {
                Toast.makeText(this, "请先选择参考音频", Toast.LENGTH_SHORT).show(); return;
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

        // ── 参数 ──
        speedSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { speedValue.setText(String.format("%.1f", 0.5f + p / 50f)); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        topkSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { topkValue.setText(String.valueOf(Math.max(1, p))); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        tempSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { tempValue.setText(String.format("%.2f", p / 100f)); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ─── 模型选择 ───

    private void showModelPickerDialog() {
        String b = bertPath != null ? "✅ BERT" : "❌ BERT";
        String s = sslPath  != null ? "✅ SSL" : "❌ SSL";
        String g = gptPath  != null ? "✅ GPT-SoVITS" : "❌ GPT-SoVITS";

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择模型文件")
                .setMessage(b + "\n" + s + "\n" + g + "\n\n选完后点确认（不加载到内存）")
                .setPositiveButton("BERT", (d, w) -> { pendingModelType = "bert"; modelFilePicker.launch("*/*"); })
                .setNeutralButton("SSL", (d, w) -> { pendingModelType = "ssl"; modelFilePicker.launch("*/*"); })
                .setNegativeButton("GPT", (d, w) -> { pendingModelType = "gpt"; modelFilePicker.launch("*/*"); })
                .show();
    }

    private void showInitDialog() {
        if (bertPath == null || sslPath == null || gptPath == null) {
            Toast.makeText(this, "请先选完三个模型", Toast.LENGTH_SHORT).show(); return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("确认模型")
                .setMessage("BERT: " + new File(bertPath).getName()
                        + "\nSSL: " + new File(sslPath).getName()
                        + "\nGPT: " + new File(gptPath).getName()
                        + "\n\n确认？（不会加载到内存）")
                .setPositiveButton("确认", (d, w) -> doInit())
                .setNegativeButton("返回", (d, w) -> showModelPickerDialog())
                .show();
    }

    private void doInit() {
        setBusy(true);
        statusText.setText("初始化…");
        new Thread(() -> {
            try {
                engine.init(new File(bertPath).getParent(), getAssets());
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("✅ v1.2 就绪 | 推理时串行加载");
                    btnLoadModel.setEnabled(false);
                });
            } catch (Exception e) {
                Log.e("YuukaTTS", "init failed", e);
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("❌ " + e.getMessage());
                });
            }
        }).start();
    }

    private void copyModelFile(Uri uri, String type) {
        File destDir = new File(getCacheDir(), "models"); destDir.mkdirs();
        String name;
        switch (type) {
            case "bert": name = "bert_model_cpu.pt"; break;
            case "ssl":  name = "ssl_model_cpu.pt"; break;
            default:     name = "gpt_sovits_model_cpu.pt"; break;
        }
        File destFile = new File(destDir, name);
        setBusy(true);
        statusText.setText("复制 " + type + "…");
        new Thread(() -> {
            try {
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(destFile)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                if (destFile.length() < 1024) {
                    runOnUiThread(() -> { setBusy(false); Toast.makeText(this, "文件太小", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                switch (type) {
                    case "bert": bertPath = destFile.getAbsolutePath(); break;
                    case "ssl":  sslPath  = destFile.getAbsolutePath(); break;
                    case "gpt":  gptPath  = destFile.getAbsolutePath(); break;
                }
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText(getStatusLabel());
                    if (bertPath != null && sslPath != null && gptPath != null) showInitDialog();
                    else showModelPickerDialog();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false); statusText.setText("复制失败"); });
            }
        }).start();
    }

    private String getStatusLabel() {
        StringBuilder sb = new StringBuilder();
        if (bertPath != null) sb.append("✅BERT "); else sb.append("❌BERT ");
        if (sslPath != null) sb.append("✅SSL "); else sb.append("❌SSL ");
        if (gptPath != null) sb.append("✅GPT"); else sb.append("❌GPT");
        return sb.toString();
    }

    // ─── 推理 ───

    private void generate(String text) {
        if (generating) return;
        generating = true;
        setBusy(true);
        statusText.setText("生成中（串行加载模型，请耐心等待）…");

        float speed = 0.5f + speedSeekbar.getProgress() / 50f;
        int topK = Math.max(1, topkSeekbar.getProgress());
        float temp = tempSeekbar.getProgress() / 100f;
        String refText = refTextInput.getText().toString().trim();
        String refPath = refAudioPath;

        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                float[] audio = engine.synthesize(text, refPath, refText.isEmpty() ? null : refText, speed, topK, temp);
                long ms = System.currentTimeMillis() - t0;

                lastWav = engine.audioToWav(audio);
                float dur = audio.length / (float) engine.getSampleRate();
                runOnUiThread(() -> {
                    generating = false;
                    setBusy(false);
                    statusText.setText(String.format("✅ %.1fs 音频 | 耗时 %.1fs", dur, ms / 1000f));
                    btnPlay.setEnabled(true);
                    btnSave.setEnabled(true);
                    playAudio(lastWav);
                });
            } catch (Exception e) {
                Log.e("YuukaTTS", "推理失败", e);
                CrashLogger.write("synthesize", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                runOnUiThread(() -> {
                    generating = false;
                    setBusy(false);
                    statusText.setText("❌ " + e.getMessage());
                    Toast.makeText(this, "失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ─── 音频 ───

    private void playAudio(byte[] wavData) {
        try {
            if (wavData.length < 44) return;
            int pcmLen = wavData.length - 44;
            int minSize = AudioTrack.getMinBufferSize(engine.getSampleRate(), AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(engine.getSampleRate()).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(minSize, pcmLen))
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
            track.write(wavData, 44, pcmLen);
            track.play();
            track.release();
        } catch (Exception e) { Log.w("YuukaTTS", "play", e); }
    }

    private void checkStorageAndSave() {
        if (Build.VERSION.SDK_INT >= 30) saveAudio();
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) saveAudio();
        else storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void saveAudio() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir;
            if (Build.VERSION.SDK_INT >= 30) dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            else dir = new File(Environment.getExternalStorageDirectory(), "YuukaTTS");
            dir.mkdirs();
            File f = new File(dir, "yuka_" + ts + ".wav");
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(lastWav); }
            statusText.setText("已保存: " + f.getName());
        } catch (Exception e) { statusText.setText("保存失败"); }
    }

    // ─── 参考音频 ───

    private String getFileNameFromUri(Uri uri) {
        String p = uri.getLastPathSegment();
        return p != null ? p.substring(p.lastIndexOf('/') + 1) : "audio";
    }

    private void copyRefAudioToCache(Uri uri) {
        new Thread(() -> {
            try {
                File cf = new File(getCacheDir(), "ref_audio.wav");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(cf)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                refAudioPath = cf.getAbsolutePath();
            } catch (Exception e) { /* ignore */ }
        }).start();
    }

    private void loadDefaultRefAudio() {
        try {
            String[] files = getAssets().list("");
            if (files == null) return;
            for (String f : files) {
                if (f.startsWith("ref_audio") && (f.endsWith(".wav") || f.endsWith(".ogg"))) {
                    File cf = new File(getCacheDir(), f);
                    try (InputStream is = getAssets().open(f); FileOutputStream fos = new FileOutputStream(cf)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    refAudioPath = cf.getAbsolutePath();
                    refAudioName.setText("默认");
                    return;
                }
            }
        } catch (Exception e) {}
    }

    // ─── UI ───

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnLoadModel.setEnabled(!busy && !engine.isReady());
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

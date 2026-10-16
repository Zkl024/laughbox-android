package com.lazy.laughbox;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private TextureView video;
    private ImageView idle;
    private TextView hint;
    private TextView btnLoop;
    private TextView btnGear;

    private MediaPlayer mp;
    private Surface surface;
    private boolean surfaceReady = false;
    private boolean surfaceAttached = false;
    private boolean videoReady = false;
    private boolean preparingPlayer = false;
    private boolean copyingVideo = false;
    private int copyFails = 0;
    private boolean resumed = false;
    private boolean wantPlay = false;

    private boolean loopMode = false;
    private boolean vibeOn = true;
    private float volume = 1.0f;

    private int taps = 0;
    private long lastTapAt = 0L;

    private ObjectAnimator breathX, breathY;
    private Dialog settingsDlg;
    private SharedPreferences sp;
    private File videoFile;

    private int vw = 720, vh = 1260;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable resetTaps = new Runnable() {
        @Override public void run() { taps = 0; }
    };

    /** 循环模式下自动续播；素材未就绪则自轮询等待 */
    private final Runnable autoPlay = new Runnable() {
        @Override public void run() {
            if (!resumed || !loopMode) return;
            if (!videoReady) { handler.postDelayed(this, 300); return; }
            if (!wantPlay) play();
        }
    };

    private final Runnable retryCopy = new Runnable() {
        @Override public void run() {
            if (!videoReady && copyFails < MAX_COPY_FAILS) prepareVideoAsync();
        }
    };

    private static final int MAX_COPY_FAILS = 4;
    private static final String TAG = "LaughBox";

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();

        sp = getSharedPreferences("laugh", Context.MODE_PRIVATE);
        loopMode = sp.getBoolean("loop", false);
        vibeOn = sp.getBoolean("vibe", true);
        // 兼容早期版本存过的 0..1 小数语义
        float vol100 = sp.getFloat("vol100", -1f);
        volume = (vol100 < 0f ? sp.getFloat("vol", 1.0f) * 100f : vol100) / 100f;
        if (volume < 0f || volume > 1f) volume = 1.0f;

        video = (TextureView) findViewById(R.id.video);
        idle = (ImageView) findViewById(R.id.idle);
        hint = (TextView) findViewById(R.id.hint);
        btnLoop = (TextView) findViewById(R.id.btnLoop);
        btnGear = (TextView) findViewById(R.id.btnGear);

        // 关键：TextureView 必须始终 VISIBLE 才会被绘制并创建 SurfaceTexture。
        // 绝不能用 setVisibility(GONE) 隐藏它 —— SurfaceTexture 永远等不到，播放直接死锁。
        video.setAlpha(0f);
        idle.setAlpha(1f);
        video.setSurfaceTextureListener(this);

        findViewById(R.id.stage).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { laugh(); }
        });
        btnLoop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLoop(); }
        });
        btnGear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });

        videoFile = new File(getCacheDir(), "laugh.mp4");
        prepareVideoAsync();

        updateLoopBtn();
        startBreathing();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        goImmersive();
        // 回到前台后可视区域可能变了（状态栏/导航栏），重算一次
        video.post(new Runnable() {
            @Override public void run() { fitVideo(); }
        });
        handler.removeCallbacks(autoPlay);
        if (loopMode) handler.postDelayed(autoPlay, 200);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(autoPlay);
        super.onPause();
        resumed = false;
        showIdle();
        stopBreathing();
        handler.removeCallbacks(resetTaps);
    }

    @Override
    protected void onDestroy() {
        // 必须在 super.onDestroy() 之前摘除窗口，否则 window token 已失效
        if (settingsDlg != null) {
            try { if (settingsDlg.isShowing()) settingsDlg.dismiss(); } catch (Exception ignored) {}
            settingsDlg = null;
        }
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (surface != null) {
            try { surface.release(); } catch (Exception ignored) {}
            surface = null;
        }
        super.onDestroy();
    }

    // ---------------- 素材准备（子线程 + 原子替换 + 损坏自愈） ----------------

    private boolean cacheValid() {
        return videoFile.isFile() && videoFile.length() == AssetSize.BYTES;
    }

    private void prepareVideoAsync() {
        if (videoReady) return;
        if (cacheValid()) { videoReady = true; return; }
        if (copyingVideo) return;
        copyingVideo = true;

        new Thread(new Runnable() {
            @Override public void run() {
                copyAssetAtomic();
                final boolean ok = cacheValid();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        copyingVideo = false;
                        videoReady = ok;
                        if (ok) {
                            copyFails = 0;
                            handler.removeCallbacks(retryCopy);
                            if (loopMode && resumed) play();
                        } else {
                            copyFails++;
                            if (copyFails >= MAX_COPY_FAILS) {
                                toast("素材加载失败，请重启 App");
                            } else if (resumed) {
                                // 指数退避，避免磁盘满 / IO 异常时无限重拷
                                long delay = Math.min(16000L, 1500L << copyFails);
                                handler.removeCallbacks(retryCopy);
                                handler.postDelayed(retryCopy, delay);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    private void copyAssetAtomic() {
        final File tmp = new File(getCacheDir(), "laugh.mp4.tmp");
        if (tmp.exists()) tmp.delete();
        InputStream in = null;
        OutputStream out = null;
        boolean ok = false;
        try {
            in = getAssets().open("laugh.mp4");
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            ((FileOutputStream) out).getFD().sync();
            ok = tmp.length() == AssetSize.BYTES;
        } catch (Exception e) {
            ok = false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
        if (!ok) {
            tmp.delete();
            return;
        }
        // 同目录 POSIX rename 本身即覆盖且原子，不要先 delete 目标
        if (!tmp.renameTo(videoFile)) {
            Log.w(TAG, "renameTo failed, tmp=" + tmp + " -> " + videoFile);
            tmp.delete();
        }
    }

    /**
     * 缓存确实坏了才删文件重装。
     * 解码失败（例如设备不支持该 codec）时缓存是完好的，
     * 重拷一份完全相同的字节不可能修复，必须区别对待，否则会陷入死循环。
     */
    private void onMediaError() {
        releasePlayer();
        if (cacheValid()) {
            videoReady = true;                 // 素材没问题，是解码/播放失败
            Log.w(TAG, "media error but cache intact, not re-copying");
        } else {
            videoReady = false;
            if (copyFails < MAX_COPY_FAILS) {
                copyFails++;
                if (videoFile.exists()) videoFile.delete();
                prepareVideoAsync();
            } else {
                toast("这台设备放不出来…");
            }
        }
        showIdle();
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    // ---------------- MediaPlayer ----------------

    private void createPlayer() {
        final MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(this, Uri.fromFile(videoFile));
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer pl) {
                    preparingPlayer = false;
                    if (mp != pl) { try { pl.release(); } catch (Exception ignored) {} return; }
                    if (wantPlay && resumed) startNow(pl);
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(final MediaPlayer pl) {
                    if (loopMode) {
                        // 延后一帧重播，避开部分机型 completion 回调内的 seek/start 竞态
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (mp != pl || !resumed || !loopMode || !wantPlay) return;
                                try { pl.seekTo(0); pl.start(); }
                                catch (Exception e) { onMediaError(); }
                            }
                        });
                    } else {
                        showIdle();
                    }
                }
            });
            p.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer pl, int w, int h) {
                    if (w > 0 && h > 0) { vw = w; vh = h; fitVideo(); }
                    Log.i(TAG, "videoSize=" + w + "x" + h);
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer pl, int what, int extra) {
                    preparingPlayer = false;
                    onMediaError();
                    return true;
                }
            });
            mp = p;                 // prepareAsync 之前赋值，供回调比对身份
            preparingPlayer = true;
            p.prepareAsync();
        } catch (Exception e) {
            preparingPlayer = false;
            try { p.release(); } catch (Exception ignored) {}
            if (mp == p) mp = null;
            // 瞬时异常（如连点导致的 IllegalStateException）不代表缓存损坏
            releasePlayer();
            showIdle();
        }
    }

    private void startNow(MediaPlayer p) {
        try {
            if (!surfaceAttached && surface != null) {
                p.setSurface(surface);
                surfaceAttached = true;
            }
            p.setVolume(volume, volume);
            p.seekTo(0);
            p.start();
            punch();
            if (vibeOn) buzz(28);
        } catch (Exception e) {
            releasePlayer();
            showIdle();
        }
    }

    private void releasePlayer() {
        preparingPlayer = false;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        surfaceAttached = false;
    }

    // ---------------- 播放控制 ----------------

    private void laugh() {
        if (!videoReady) {
            toast("素材还在准备…");
            prepareVideoAsync();
            return;                 // 素材没就绪就不消耗这次连击
        }
        countTap();
        play();
    }

    private void play() {
        if (!resumed) return;
        if (!videoReady) {
            handler.removeCallbacks(retryCopy);
            handler.postDelayed(retryCopy, 500);
            return;
        }

        wantPlay = true;
        showVideoLayer(true);       // 用 alpha 而非 visibility，SurfaceTexture 依赖 View 被绘制

        if (!surfaceReady || surface == null) return;   // 等 onSurfaceTextureAvailable 兑现
        if (mp != null && !preparingPlayer) {
            startNow(mp);
        } else if (!preparingPlayer) {
            createPlayer();        // 异步 prepare，onPrepared 里续上 startNow
        }
    }

    private void showIdle() {
        wantPlay = false;
        if (mp != null) {
            // PlaybackCompleted 态下 pause 属非法迁移，先判状态
            try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        }
        showVideoLayer(false);
        hint.animate().alpha(0.7f).setDuration(400).start();
        // 呼吸动画由 showVideoLayer(false) 负责启停
    }

    private void showVideoLayer(boolean on) {
        video.setAlpha(on ? 0.99f : 0f);
        idle.setAlpha(on ? 0f : 1f);
        // 播放时停掉呼吸动画：否则待机图 alpha=0 仍在 60fps 全量重绘，纯耗电
        if (on) stopBreathing();
        else if (resumed) startBreathing();
    }

    private void countTap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTapAt > 1500) taps = 0;
        lastTapAt = now;
        taps++;
        handler.removeCallbacks(resetTaps);
        handler.postDelayed(resetTaps, 1500);
        if (taps >= 3) {
            toast("哈哈哈哈哈 ×" + taps);
        }
    }

    private void toggleLoop() {
        loopMode = !loopMode;
        sp.edit().putBoolean("loop", loopMode).apply();
        updateLoopBtn();
        if (loopMode) {
            play();                // 不计入连击
            toast("已开启：它会一直笑下去");
        } else {
            showIdle();
            toast("已关闭循环");
        }
    }

    private void updateLoopBtn() {
        btnLoop.setText(loopMode ? "🔁 停不下来" : "🔄 循环大笑");
        btnLoop.setBackgroundResource(loopMode ? R.drawable.bg_pill_on : R.drawable.bg_pill);
        btnLoop.setTextColor(loopMode ? 0xFF4A1D00 : 0xFFFFFFFF);
    }

    // ---------------- 动效 ----------------

    private void startBreathing() {
        stopBreathing();
        idle.setScaleX(1f);
        idle.setScaleY(1f);
        breathX = ObjectAnimator.ofFloat(idle, View.SCALE_X, 1f, 1.035f);
        breathY = ObjectAnimator.ofFloat(idle, View.SCALE_Y, 1f, 1.035f);
        breathX.setDuration(1900);
        breathY.setDuration(1900);
        breathX.setRepeatCount(ObjectAnimator.INFINITE);
        breathY.setRepeatCount(ObjectAnimator.INFINITE);
        breathX.setRepeatMode(ObjectAnimator.REVERSE);
        breathY.setRepeatMode(ObjectAnimator.REVERSE);
        AccelerateDecelerateInterpolator ip = new AccelerateDecelerateInterpolator();
        breathX.setInterpolator(ip);
        breathY.setInterpolator(ip);
        breathX.start();
        breathY.start();
    }

    private void stopBreathing() {
        if (breathX != null) { breathX.cancel(); breathX = null; }
        if (breathY != null) { breathY.cancel(); breathY = null; }
    }

    private void punch() {
        video.animate().cancel();
        video.setScaleX(0.94f);
        video.setScaleY(0.94f);
        video.animate().scaleX(1f).scaleY(1f)
             .setDuration(260)
             .setInterpolator(new OvershootInterpolator(3.2f))
             .start();
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 让视频按 4:7 等比缩放并居中。
     *
     * 这里刻意**不用** TextureView.setTransform()：那条路依赖矩阵，
     * 在真机上容易因为调用时序（SurfaceTexture 刚建、view 还没测量）而没生效，
     * 一旦 transform 缺失，TextureView 会把视频拉伸铺满全屏 —— 就是"比例不对"。
     *
     * 改成直接把 TextureView 的尺寸设成 contain 后的实际像素大小，
     * 再由父容器的 gravity=center 完成居中。语义上和待机图的
     * scaleType="fitCenter" 完全一致，切换时不会跳变。
     */
    private void fitVideo() {
        if (video == null) return;
        ViewParent parent = video.getParent();
        if (!(parent instanceof ViewGroup)) return;

        // 用**父容器**尺寸算，避免用 video 自身尺寸造成自我依赖的死循环
        int pw = ((ViewGroup) parent).getWidth();
        int ph = ((ViewGroup) parent).getHeight();
        if (pw == 0 || ph == 0 || vw == 0 || vh == 0) return;

        float s = Math.min((float) pw / vw, (float) ph / vh);
        int tw = Math.max(1, Math.round(vw * s));
        int th = Math.max(1, Math.round(vh * s));

        ViewGroup.LayoutParams lp = video.getLayoutParams();
        // 唯一的守卫：已经在位就别再 setLayoutParams。
        // 注意不要拿"父容器尺寸"做缓存——若 onVideoSizeChanged 回报了与默认值
        // 不同的 vw/vh，那种缓存会静默吞掉重算，导致又变回"比例不对"。
        if (lp.width == tw && lp.height == th) return;
        lp.width = tw;
        lp.height = th;
        video.setLayoutParams(lp);
    }

    // ---------------- 设置面板 ----------------

    private void showSettings() {
        if (settingsDlg != null && settingsDlg.isShowing()) {
            try { settingsDlg.dismiss(); } catch (Exception ignored) {}
        }

        final Dialog dlg = new Dialog(this);
        View v = getLayoutInflater().inflate(R.layout.view_settings, null);
        dlg.setContentView(v);
        dlg.setCanceledOnTouchOutside(true);
        settingsDlg = dlg;

        SeekBar sb = (SeekBar) v.findViewById(R.id.seekVolume);
        final Switch sw = (Switch) v.findViewById(R.id.swVibe);
        sb.setProgress(Math.round(volume * 100f));
        sw.setChecked(vibeOn);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int pending = -1;
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                volume = progress / 100f;
                if (mp != null) {
                    try { mp.setVolume(volume, volume); } catch (Exception ignored) {}
                }
                if (fromUser) pending = progress;
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {
                if (pending >= 0) { sp.edit().putFloat("vol100", pending).apply(); pending = -1; }
            }
        });

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                vibeOn = checked;
                sp.edit().putBoolean("vibe", checked).apply();
            }
        });

        try {
            dlg.getWindow().setBackgroundDrawable(
                getResources().getDrawable(R.drawable.bg_sheet, getTheme()));
            dlg.show();
        } catch (Exception e) {
            toast("设置打不开");
        }
    }

    // ---------------- 沉浸式 ----------------

    private void goImmersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goImmersive();
            // 沉浸式开关会改变可视区域，重算一次
            video.post(new Runnable() {
                @Override public void run() { fitVideo(); }
            });
        }
    }

    /** manifest 吞掉了 screenSize，分屏 / 折叠屏展开时窗口尺寸变化不会重建 Activity */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        video.post(new Runnable() {
            @Override public void run() { fitVideo(); }
        });
    }

    // ---------------- TextureView 回调 ----------------

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surfaceReady = true;
        if (surface != null) { try { surface.release(); } catch (Exception ignored) {} }
        surface = new Surface(st);
        if (mp != null && !surfaceAttached) {
            try { mp.setSurface(surface); surfaceAttached = true; } catch (Exception ignored) {}
        }
        // 布局通常还没测量完，post 到下一帧再算
        video.post(new Runnable() {
            @Override public void run() { fitVideo(); }
        });
        if (wantPlay && resumed) play();     // 兑现之前记下的播放意图
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        // 该回调可能在 layout pass 期间派发，setLayoutParams 必须 post 出去，
        // 否则 ViewRootImpl 会打 "requestLayout() improperly called ... during layout"
        video.post(new Runnable() {
            @Override public void run() { fitVideo(); }
        });
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        surfaceAttached = false;
        if (surface != null) { surface.release(); surface = null; }
        if (mp != null) {
            try { mp.setSurface(null); } catch (Exception ignored) {}
        }
        return true;    // SurfaceTexture 交回 TextureView 自行释放
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}

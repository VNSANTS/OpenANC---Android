package com.openanc.app;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Segundo canal independente. O Android/HAL pode recusar captura simultânea,
 * redirecionar ambos os AudioRecords ao mesmo hardware ou silenciar um deles.
 * Este componente verifica o roteamento efetivo e evita afirmar que há dual mic
 * se não for possível validar duas entradas físicas distintas.
 */
final class DualMicCapture implements AutoCloseable {
    private final AudioRecord recorder;
    private final int requestedDeviceId;
    private final int primaryDeviceId;
    private final int block;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean routeValid = false;
    private volatile String fault = null;
    private volatile float[] latest = null;
    private volatile long latestNs = 0L;
    private volatile double rms = 0.0;
    private volatile int silentBlocks = 0;
    private Thread thread;

    DualMicCapture(AudioDeviceInfo device,int mainDeviceId,int rate,int block,AudioManager manager) {
        this.requestedDeviceId=device.getId();
        this.primaryDeviceId=mainDeviceId;
        this.block=block;
        int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,
                                             AudioFormat.ENCODING_PCM_FLOAT);
        if(min<=0)throw new IllegalStateException("PCM float não suportado");
        AudioFormat fmt=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        String raw=manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
        int src="true".equalsIgnoreCase(raw)?MediaRecorder.AudioSource.UNPROCESSED:
                                              MediaRecorder.AudioSource.MIC;
        AudioRecord.Builder builder=new AudioRecord.Builder().setAudioSource(src)
                .setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min,block*4*4));
        if(Build.VERSION.SDK_INT>=30)builder.setPrivacySensitive(false);
        recorder=builder.build();
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){
            recorder.release();
            throw new IllegalStateException("2º AudioRecord não inicializou");
        }
        if(!recorder.setPreferredDevice(device)){
            recorder.release();
            throw new IllegalStateException("Roteamento do 2º microfone recusado");
        }
    }

    void start(){
        if(!running.compareAndSet(false,true))return;
        thread=new Thread(this::capture,"openanc-mic-auxiliar");
        thread.setDaemon(true);
        thread.start();
    }

    private void capture(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        float[] buf=new float[block];
        int frames=0;
        try{
            if(!running.get())return;
            recorder.startRecording();
            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("2º canal não iniciou");
            while(running.get()){
                int n=recorder.read(buf,0,buf.length,AudioRecord.READ_BLOCKING);
                if(!running.get())break;
                if(n<0)throw new IllegalStateException("Falha de leitura do 2º canal: "+n);
                if(n==0)continue;
                if(++frames%12==0 || !routeValid){
                    AudioDeviceInfo route=recorder.getRoutedDevice();
                    if(route!=null){
                        if(route.getId()!=requestedDeviceId||route.getId()==primaryDeviceId)
                            throw new IllegalStateException("Android roteou ambos ao mesmo microfone ou a outro dispositivo");
                        routeValid=true;
                    }
                }
                double sum=0;
                for(int k=0;k<n;k++)sum+=buf[k]*buf[k];
                rms=Math.sqrt(sum/n);
                silentBlocks=rms<0.0001?silentBlocks+1:0;
                if(routeValid&&silentBlocks<250) {
                    latest=Arrays.copyOf(buf,n);
                    latestNs=System.nanoTime();
                }else if(silentBlocks>=250) {
                    latest=null;
                }
            }
        }catch(Throwable ex){
            if(running.get())fault=ex.getMessage()==null?ex.toString():ex.getMessage();
        }finally{
            running.set(false);
            latest=null;
            try{recorder.stop();}catch(Exception ignored){}
            try{recorder.release();}catch(Exception ignored){}
        }
    }

    float[] recentBlock(){
        if(!running.get()||!routeValid||silentBlocks>=250)return null;
        long age=System.nanoTime()-latestNs;
        if(age<0||age>20_000_000L)return null;
        return latest;
    }

    String status(){
        if(fault!=null)return "Dual Mic indisponível: "+fault+" | principal mantido";
        if(!running.get())return "Aguardando segundo microfone";
        if(!routeValid)return "Segundo microfone: tentando validar roteamento físico";
        if(silentBlocks>=250)return "2º microfone sem áudio; usando apenas o principal";
        return String.format(Locale.US,"2 microfones roteados; auxiliar: %.0f dBFS (cancelamento não medido)",
                              20*Math.log10(Math.max(0.000001,rms)));
    }

    @Override public void close(){
        running.set(false);
        latest=null;
        try{recorder.stop();}catch(Exception ignored){}
        // A thread de captura faz o release; evitar bloquear a interface gráfica.
    }
}

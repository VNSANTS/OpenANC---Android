package com.openanc.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BG=0xff0c1323, CARD=0xff19223a, TEXT=0xfff1f5ff,
            DIM=0xffa9b7ce, MINT=0xff48e5c2, RED=0xffff8e88;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicBoolean running=new AtomicBoolean(false);
    private final ArrayList<AudioDeviceInfo> microphones=new ArrayList<>();
    private final ArrayList<AudioDeviceInfo> headphones=new ArrayList<>();
    private AudioManager manager;
    private AudioDeviceCallback deviceCallback;
    private Spinner micList,outputList,profiles;
    private SeekBar gainBar,cutoffBar,delayBar;
    private TextView gainLabel,cutoffLabel,delayLabel,status,devicesInfo,meters;
    private Button power;
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private volatile Thread audioThread;
    private volatile int gainPercent=10,cutoffHz=150,delayMs=0,playingDeviceId=-1;
    private volatile double rms=0,peak=0;
    private volatile long frames=0,shortBlocks=0;
    private static final int SAMPLE_RATE=48000, BLOCK=192;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        manager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(22),px(22),px(22),px(35));
        scroll.addView(page);
        setContentView(scroll);
        text(page,"OPENANC",33,MINT,true);
        text(page,"Antirruído experimental para fones comuns",14,DIM,false);
        space(page,18);
        LinearLayout intro=card(page);
        text(intro,"🎧  PROCESSAMENTO AO VIVO",18,TEXT,true);
        text(intro,"Capta o som ambiente, filtra os graves e envia uma onda invertida aos fones com fio.",14,DIM,false);
        status=text(intro,"● Desligado",16,DIM,true);
        space(page,15);

        LinearLayout io=card(page);
        text(io,"01   DISPOSITIVOS",13,MINT,true);
        text(io,"Microfone",14,TEXT,true);
        micList=spinner(io);
        text(io,"Saída em fones com fio",14,TEXT,true);
        outputList=spinner(io);
        devicesInfo=text(io,"Conecte um fone P2 ou USB para começar.",12,DIM,false);
        space(page,15);

        LinearLayout settings=card(page);
        text(settings,"02   FILTROS E FASE",13,MINT,true);
        text(settings,"Perfil",14,TEXT,true);
        profiles=spinner(settings);
        profiles.setAdapter(adapter(new String[]{"Graves • 150 Hz","Ruído contínuo • 250 Hz",
            "Experimental • 450 Hz","Manual"}));
        gainLabel=text(settings,"Ganho do antirruído: 10%",14,TEXT,true);
        gainBar=slider(settings,35,10,v->{
            gainPercent=v;gainLabel.setText("Ganho do antirruído: "+v+"%");
        });
        cutoffLabel=text(settings,"Frequência máxima: 150 Hz",14,TEXT,true);
        cutoffBar=slider(settings,570,70,v->{
            cutoffHz=80+v;cutoffLabel.setText("Frequência máxima: "+cutoffHz+" Hz");
        });
        delayLabel=text(settings,"Atraso adicional: 0 ms",14,TEXT,true);
        delayBar=slider(settings,15,0,v->{
            delayMs=v;delayLabel.setText("Atraso adicional: "+v+" ms");
        });
        profiles.setSelection(0);
        profiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View v,int index,long id){
                if(index==0)cutoffBar.setProgress(70);
                if(index==1)cutoffBar.setProgress(170);
                if(index==2)cutoffBar.setProgress(370);
            }
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        space(page,15);

        LinearLayout telemetry=card(page);
        text(telemetry,"03   SINAL DO MICROFONE",13,MINT,true);
        meters=text(telemetry,"Aguardando início...",14,DIM,false);
        space(page,18);

        power=new Button(this);
        power.setText("INICIAR ANTIRRUÍDO");
        power.setTextSize(16);
        power.setAllCaps(false);
        power.setTypeface(null,Typeface.BOLD);
        power.setTextColor(BG);
        power.setBackground(background(MINT,16));
        page.addView(power,new LinearLayout.LayoutParams(-1,px(62)));
        power.setOnClickListener(v->{if(running.get())stop("Parado pelo usuário.",false);else start();});
        space(page,14);
        TextView warning=text(page,"⚠ EXPERIMENTO: inverter o som não garante ANC no ouvido. A latência pode aumentar o ruído. Use volume baixo, pare ao ouvir apitos ou desconforto e nunca use no trânsito ou perto de máquinas.",13,DIM,false);
        warning.setLineSpacing(px(3),1f);

        deviceCallback=new AudioDeviceCallback(){
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d){ui.post(()->refreshDevices());}
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d){
                for(AudioDeviceInfo item:d){
                    if(running.get()&&item.getId()==playingDeviceId){
                        ui.post(()->stop("Fone desconectado: interrompido por segurança.",true));break;
                    }
                }
                ui.post(()->refreshDevices());
            }
        };
        manager.registerAudioDeviceCallback(deviceCallback,ui);
        refreshDevices();
        ui.post(meterRefresh);
    }

    private final Runnable meterRefresh=new Runnable(){
        @Override public void run(){
            if(running.get()){
                meters.setText(String.format(Locale.US,
                  "Entrada RMS  %.1f dBFS\nPico de entrada  %.1f dBFS\nAmostras processadas  %,d\nBlocos incompletos  %,d",
                  dB(rms),dB(peak),frames,shortBlocks));
            }
            ui.postDelayed(this,350);
        }
    };
    private static double dB(double n){return 20*Math.log10(Math.max(n,0.000001));}
    private int px(int dp){return (int)(dp*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable background(int color,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(px(radius));return d;
    }
    private void space(LinearLayout parent,int height){
        View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,px(height)));
    }
    private LinearLayout card(LinearLayout parent){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(px(17),px(14),px(17),px(15));
        l.setBackground(background(CARD,18));
        parent.addView(l,new LinearLayout.LayoutParams(-1,-2));return l;
    }
    private TextView text(LinearLayout p,String label,int size,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(label);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(0,px(4),0,px(8));
        p.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;
    }
    private Spinner spinner(LinearLayout p){
        Spinner s=new Spinner(this);
        s.setBackgroundTintList(ColorStateList.valueOf(MINT));
        p.addView(s,new LinearLayout.LayoutParams(-1,px(50)));return s;
    }
    private ArrayAdapter<String> adapter(String[] values){
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values){
            @Override public View getView(int pos,View convert,ViewGroup parent){
                TextView t=(TextView)super.getView(pos,convert,parent);
                t.setTextColor(TEXT);return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return a;
    }
    private interface Changed{void accept(int value);}
    private SeekBar slider(LinearLayout p,int maximum,int initial,Changed changed){
        SeekBar b=new SeekBar(this);
        b.setMax(maximum);b.setProgress(initial);
        b.setProgressTintList(ColorStateList.valueOf(MINT));
        b.setThumbTintList(ColorStateList.valueOf(MINT));
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int v,boolean fromUser){changed.accept(v);}
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        p.addView(b,new LinearLayout.LayoutParams(-1,px(46)));return b;
    }
    private boolean wired(AudioDeviceInfo d){
        int t=d.getType();
        return t==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||
            t==AudioDeviceInfo.TYPE_WIRED_HEADSET||
            t==AudioDeviceInfo.TYPE_USB_HEADSET||
            t==AudioDeviceInfo.TYPE_USB_DEVICE||
            t==AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }
    private String deviceName(AudioDeviceInfo d){
        CharSequence title=d.getProductName();
        return (title==null?"Dispositivo":title.toString())+" (ID "+d.getId()+")";
    }
    private void refreshDevices(){
        if(running.get())return;
        microphones.clear();headphones.clear();
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if(d.isSource())microphones.add(d);
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if(d.isSink()&&wired(d))headphones.add(d);
        ArrayList<String> ins=new ArrayList<>(),outs=new ArrayList<>();
        for(AudioDeviceInfo d:microphones)ins.add(deviceName(d));
        for(AudioDeviceInfo d:headphones)outs.add(deviceName(d));
        if(ins.isEmpty())ins.add("Microfone indisponível");
        if(outs.isEmpty())outs.add("Nenhum fone com fio detectado");
        micList.setAdapter(adapter(ins.toArray(new String[0])));
        outputList.setAdapter(adapter(outs.toArray(new String[0])));
        devicesInfo.setText(headphones.isEmpty()?
            "Conecte um fone com fio. Bluetooth e alto-falante não são permitidos.":
            "✓ Saída com fio detectada. O app funciona sem internet.");
        power.setEnabled(!microphones.isEmpty()&&!headphones.isEmpty());
    }
    private void status(String message,boolean error){
        status.setText("● "+message);
        status.setTextColor(error?RED:(running.get()?MINT:DIM));
    }
    private void start(){
        if(running.get())return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},100);return;
        }
        int i=micList.getSelectedItemPosition(),o=outputList.getSelectedItemPosition();
        if(i<0||i>=microphones.size()||o<0||o>=headphones.size()){
            status("Conecte o microfone e um fone com fio.",true);return;
        }
        AudioDeviceInfo in=microphones.get(i),out=headphones.get(o);
        if(!wired(out)){status("Saída sem fio bloqueada.",true);return;}
        AudioRecord rec=null;
        AudioTrack track=null;
        try {
            int inMin=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
            int outMin=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT);
            if(inMin<1||outMin<1)throw new IllegalStateException("PCM float 48 kHz indisponível");
            AudioFormat inputFormat=new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            AudioFormat outputFormat=new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            String supportsRaw=manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
            int micSource="true".equalsIgnoreCase(supportsRaw)?
                MediaRecorder.AudioSource.UNPROCESSED:MediaRecorder.AudioSource.MIC;
            rec=new AudioRecord.Builder().setAudioSource(micSource).setAudioFormat(inputFormat)
                .setBufferSizeInBytes(Math.max(inMin,BLOCK*4*4)).build();
            track=new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(outputFormat).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(Math.max(outMin,BLOCK*8*4)).build();
            if(rec.getState()!=AudioRecord.STATE_INITIALIZED||
                track.getState()!=AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("Não foi possível iniciar o hardware de áudio.");
            if(!rec.setPreferredDevice(in)||!track.setPreferredDevice(out))
                throw new IllegalStateException("Android recusou o microfone ou a saída selecionada.");
            recorder=rec;player=track;
            peak=0;rms=0;frames=0;shortBlocks=0;
            playingDeviceId=out.getId();
            running.set(true);
            power.setText("PARAR IMEDIATAMENTE");
            power.setBackground(background(RED,16));
            micList.setEnabled(false);outputList.setEnabled(false);
            status("Processamento ativo",false);
            final AudioRecord useRec=rec;
            final AudioTrack useTrack=track;
            audioThread=new Thread(()->process(useRec,useTrack,out.getId()),"openanc-audio");
            audioThread.start();
        }catch(Throwable problem){
            if(rec!=null){try{rec.release();}catch(Exception ignored){}}
            if(track!=null){try{track.release();}catch(Exception ignored){}}
            recorder=null;player=null;playingDeviceId=-1;
            status("Falha: "+problem.getMessage(),true);
        }
    }
    private void process(AudioRecord rec,AudioTrack track,int outId){
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        float[] input=new float[BLOCK],stereo=new float[BLOCK*2];
        float[] fifo=new float[SAMPLE_RATE/50+2];
        int pointer=0,clipCount=0;
        double hp=0,previous=0,lp=0;
        try {
            rec.startRecording();
            track.play();
            while(running.get()){
                AudioDeviceInfo physical=track.getRoutedDevice();
                if(physical!=null&&(!wired(physical)||physical.getId()!=outId))
                    throw new IllegalStateException("A rota mudou; áudio interrompido.");
                int read=rec.read(input,0,BLOCK,AudioRecord.READ_BLOCKING);
                if(read<0)throw new IllegalStateException("Falha ao ler microfone: "+read);
                if(read==0){shortBlocks++;continue;}
                double energy=0,max=0;
                double highPassFactor=Math.exp(-2*Math.PI*35/SAMPLE_RATE);
                double lowPassFactor=1-Math.exp(-2*Math.PI*Math.max(80,cutoffHz)/SAMPLE_RATE);
                int d=Math.max(0,Math.min(fifo.length-1,delayMs*SAMPLE_RATE/1000));
                float intensity=Math.max(0,Math.min(0.35f,gainPercent/100f));
                for(int k=0;k<read;k++){
                    double x=input[k];
                    energy+=x*x;max=Math.max(max,Math.abs(x));
                    hp=highPassFactor*(hp+x-previous);previous=x;
                    lp+=lowPassFactor*(hp-lp);
                    fifo[pointer]=(float)lp;
                    float inverse=-intensity*fifo[(pointer-d+fifo.length)%fifo.length];
                    pointer=(pointer+1)%fifo.length;
                    float safe=Math.max(-0.25f,Math.min(0.25f,inverse));
                    stereo[2*k]=safe;
                    stereo[2*k+1]=safe;
                }
                rms=Math.sqrt(energy/read);peak=max;frames+=read;
                if(max>0.99)clipCount++;else clipCount=0;
                if(clipCount>=50)
                    throw new IllegalStateException("Microfone saturado; proteção acionada.");
                int sent=0;
                while(sent<read*2&&running.get()){
                    int n=track.write(stereo,sent,read*2-sent,AudioTrack.WRITE_BLOCKING);
                    if(n<=0)throw new IllegalStateException("Fone indisponível: "+n);
                    sent+=n;
                }
                if(read<BLOCK)shortBlocks++;
            }
        }catch(Throwable problem){
            if(running.get())ui.post(()->stop("Proteção: "+problem.getMessage(),true));
        }finally {
            try{track.pause();track.flush();}catch(Exception ignored){}
            try{rec.stop();}catch(Exception ignored){}
            try{track.release();}catch(Exception ignored){}
            try{rec.release();}catch(Exception ignored){}
        }
    }
    private void stop(String reason,boolean error){
        if(!running.getAndSet(false))return;
        AudioRecord rec=recorder;AudioTrack track=player;
        recorder=null;player=null;playingDeviceId=-1;
        try{if(rec!=null)rec.stop();}catch(Exception ignored){}
        try{if(track!=null)track.pause();}catch(Exception ignored){}
        power.setText("INICIAR ANTIRRUÍDO");
        power.setBackground(background(MINT,16));
        micList.setEnabled(true);outputList.setEnabled(true);
        status(reason,error);
        refreshDevices();
    }
    @Override public void onRequestPermissionsResult(int code,String[] requested,int[] grants){
        super.onRequestPermissionsResult(code,requested,grants);
        if(code==100){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)start();
            else status("Permissão de microfone não concedida.",true);
        }
    }
    @Override protected void onPause(){
        super.onPause();
        if(running.get())stop("Pausado ao sair do aplicativo.",false);
    }
    @Override protected void onDestroy(){
        ui.removeCallbacks(meterRefresh);
        if(running.get())stop("Encerrado.",false);
        if(deviceCallback!=null)manager.unregisterAudioDeviceCallback(deviceCallback);
        super.onDestroy();
    }
}

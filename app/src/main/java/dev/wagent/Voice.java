package dev.wagent;
import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.*;
import android.speech.tts.TextToSpeech;
import java.util.*;
import java.util.function.BiConsumer;

final class Voice {
    private final Activity activity;private SpeechRecognizer recognizer;private TextToSpeech tts;private boolean ready;private BiConsumer<String,String> callback;
    Voice(Activity activity){this.activity=activity;}
    void listen(BiConsumer<String,String> result){
        if(callback!=null){result.accept(null,"Голосовой ввод уже запущен");return;}
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){result.accept(null,"Разрешите микрофон в настройках W Agent и повторите");activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},5);return;}
        if(!SpeechRecognizer.isRecognitionAvailable(activity)){result.accept(null,"На устройстве нет службы распознавания речи. Используйте голосовой ввод клавиатуры или установите системную службу речи.");return;}
        callback=result;recognizer=SpeechRecognizer.createSpeechRecognizer(activity);recognizer.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){}public void onBeginningOfSpeech(){}public void onRmsChanged(float rms){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}
            public void onError(int error){finish(null,"Распознавание не удалось ("+error+"). Проверьте микрофон, сеть и службу речи.");}
            public void onResults(Bundle b){ArrayList<String> text=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);finish(text==null||text.isEmpty()?"":text.get(0),null);}
        });
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());recognizer.startListening(intent);
    }
    private void finish(String text,String error){BiConsumer<String,String> result=callback;callback=null;if(recognizer!=null){recognizer.destroy();recognizer=null;}if(result!=null)result.accept(text,error);}
    void speak(String text,BiConsumer<String,String> result){
        if(text.length()>TextToSpeech.getMaxSpeechInputLength()){result.accept(null,"Ответ слишком длинный для озвучки одним запросом. Скопируйте нужный фрагмент.");return;}
        if(ready){int code=tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"w-agent");result.accept(code==TextToSpeech.SUCCESS?"ok":null,code==TextToSpeech.SUCCESS?null:"Движок озвучки не принял текст");return;}
        if(tts!=null){result.accept(null,"Движок озвучки запускается; повторите");return;}
        tts=new TextToSpeech(activity,status->{ready=status==TextToSpeech.SUCCESS;if(!ready){result.accept(null,"Системный движок озвучки недоступен");return;}int language=tts.setLanguage(Locale.getDefault());if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){result.accept(null,"Установите голос для языка устройства в настройках озвучки");return;}speak(text,result);});
    }
    void stop(){if(tts!=null)tts.stop();if(recognizer!=null){recognizer.cancel();finish(null,"Голосовой ввод остановлен");}}
    void close(){stop();if(tts!=null)tts.shutdown();}
}

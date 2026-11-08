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
    private final Activity activity;private SpeechRecognizer recognizer;private TextToSpeech tts;private boolean ready;private BiConsumer<String,String> callback;private boolean permissionPending;private String partial="";private final BiConsumer<String,String> updates;private final android.os.Handler timer=new android.os.Handler(android.os.Looper.getMainLooper());
    Voice(Activity activity,BiConsumer<String,String> updates){this.activity=activity;this.updates=updates;}
    void listen(BiConsumer<String,String> result){
        if(callback!=null){result.accept(null,"Голосовой ввод уже запущен");return;}
        callback=result;partial="";
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){permissionPending=true;updates.accept("permission","");activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},5);return;}
        startRecognition();
    }
    void permissionResult(int[] grants){if(!permissionPending)return;permissionPending=false;if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startRecognition();else finish(null,"Нет доступа к микрофону. Разрешите его в настройках приложения.");}
    private void startRecognition(){
        if(callback==null)return;
        if(!SpeechRecognizer.isRecognitionAvailable(activity)){finish(null,"На устройстве нет службы распознавания речи. Используйте голосовой ввод клавиатуры или установите системную службу речи.");return;}
        try{recognizer=SpeechRecognizer.createSpeechRecognizer(activity);recognizer.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){updates.accept("ready","");}public void onBeginningOfSpeech(){}public void onRmsChanged(float rms){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){updates.accept("processing",partial);}public void onEvent(int t,Bundle b){}
            public void onPartialResults(Bundle b){ArrayList<String> text=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(text!=null&&!text.isEmpty()){partial=text.get(0);updates.accept("partial",partial);}}
            public void onError(int error){if(!partial.isEmpty()&&(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT))finish(partial,null);else finish(null,message(error));}
            public void onResults(Bundle b){ArrayList<String> text=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);finish(text==null||text.isEmpty()?partial:text.get(0),null);}
        });
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true).putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());recognizer.startListening(intent);timer.postDelayed(()->{if(callback!=null){finishListening();timer.postDelayed(()->{if(callback!=null)finish(partial,partial.isEmpty()?"Не удалось расслышать. Попробуйте ещё раз.":null);},5000);}},30000);
        }catch(Exception e){finish(null,"Служба речи недоступна: "+e.getMessage());}
    }
    private static String message(int error){switch(error){case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:return "Разрешите доступ к микрофону в настройках W Agent";case SpeechRecognizer.ERROR_NO_MATCH:case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:return "Не удалось расслышать. Нажмите микрофон и повторите";case SpeechRecognizer.ERROR_NETWORK:case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:return "Службе распознавания речи нужен доступ к сети";case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:return "Микрофон занят. Закройте другую запись и повторите";default:return "Ошибка службы речи ("+error+"). Проверьте службу распознавания в Android";}}
    void finishListening(){if(recognizer!=null)recognizer.stopListening();}
    private void finish(String text,String error){timer.removeCallbacksAndMessages(null);BiConsumer<String,String> result=callback;callback=null;permissionPending=false;if(recognizer!=null){recognizer.destroy();recognizer=null;}if(result!=null)result.accept(text,error);}
    void speak(String text,BiConsumer<String,String> result){
        if(text.length()>TextToSpeech.getMaxSpeechInputLength()){result.accept(null,"Ответ слишком длинный для озвучки одним запросом. Скопируйте нужный фрагмент.");return;}
        if(ready){int code=tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"w-agent");result.accept(code==TextToSpeech.SUCCESS?"ok":null,code==TextToSpeech.SUCCESS?null:"Движок озвучки не принял текст");return;}
        if(tts!=null){result.accept(null,"Движок озвучки запускается; повторите");return;}
        tts=new TextToSpeech(activity,status->{ready=status==TextToSpeech.SUCCESS;if(!ready){result.accept(null,"Системный движок озвучки недоступен");return;}int language=tts.setLanguage(Locale.getDefault());if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){result.accept(null,"Установите голос для языка устройства в настройках озвучки");return;}speak(text,result);});
    }
    void stop(){if(tts!=null)tts.stop();if(recognizer!=null)recognizer.cancel();if(callback!=null)finish(null,"Голосовой ввод остановлен");}
    void close(){stop();if(tts!=null)tts.shutdown();}
}

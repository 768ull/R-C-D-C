# Preserva os métodos expostos ao JavaScript: seus nomes são contratos da interface WebView.
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# As pontes são acessadas pelo nome JavaScript e não podem ser removidas/renomeadas.
-keep class com.chronos.app.MainActivity$CompassBridge { *; }
-keep class com.chronos.app.MainActivity$AlarmBridge { *; }
-keep class com.chronos.app.MainActivity$LegacyStorageBridge { *; }

# Componentes Android declarados no Manifest ou acionados pelo sistema.
-keep class com.chronos.app.AlarmReceiver { *; }
-keep class com.chronos.app.AlarmBootReceiver { *; }
-keep class com.chronos.app.AlarmActivity { *; }

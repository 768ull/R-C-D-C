# Chronos App — Android

Aplicativo Android nativo do **Chronos**: relógio analógico e digital, calendário brasileiro com mapeamento de feriados e festas, cronograma diário de tarefas, contagens regressivas (próximos anos e feriados do Brasil) e calculadora com conversão de horas para minutos.

O aplicativo funciona de forma **100% offline e autônoma**: a interface HTML/JS/CSS está embutida nos assets do APK e é executada em um WebView local. Nenhuma permissão de internet é solicitada — a sincronização de dias, meses e contagens ocorre exclusivamente pelo relógio do aparelho.

## Funcionalidades

- Relógio analógico com ponteiro contínuo (modo sweep) e som de tique-taque opcional.
- Display digital com data completa em português.
- Tema claro e escuro, sincronizado com a preferência do sistema.
- Calendário mensal com destaque do dia atual e de feriados e festas brasileiras (incluindo datas móveis calculadas pelo algoritmo de Gauss, como Carnaval e Páscoa).
- Cronograma diário com adição, conclusão e remoção de tarefas.
- Contagem regressiva dos próximos cinco anos e de todos os feriados catalogados.
- Calculadora com operações básicas e conversão de horas para minutos.
- Bloqueio de zoom, seleção de texto e menus nativos do navegador, com comportamento de aplicativo.

## Estrutura do projeto

```
.
├── .github/workflows/android-build.yml   # CI: compila o APK automaticamente
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/index.html             # Aplicação completa (HTML/JS/CSS)
│       ├── java/com/chronos/app/MainActivity.java
│       └── res/
│           ├── drawable/ic_launcher.xml
│           └── values/{strings,colors,themes}.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

## Como compilar

### Opção 1 — GitHub Actions (sem instalar nada)

1. Abra a aba **Actions** do repositório.
2. Selecione o workflow **Build APK**.
3. Após a execução, faça o download do artefato **chronos-debug-apk**.
4. Extraia o ZIP e instale o `app-debug.apk` no celular (ative "instalar apps de fontes desconhecidas" se solicitado).

### Opção 2 — Android Studio

1. Clone o repositório: `git clone https://github.com/768ull/R-C-D-C.git`
2. Abra a pasta no Android Studio (aguarde a sincronização do Gradle).
3. Selecione **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. O APK estará em `app/build/outputs/apk/debug/app-debug.apk`.

### Opção 3 — Linha de comando

Requisitos: JDK 17 e Gradle 8.7 (ou Android Studio com SDK 34 instalado).

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Para gerar uma versão assinada para publicação na Play Store, crie uma keystore e configure `signingConfigs` em `app/build.gradle`.

## Requisitos

- Android 7.0 (API 24) ou superior.
- Nenhuma permissão de internet, câmera ou armazenamento.

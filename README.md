# Chronos App — Android

Aplicativo Android nativo do **Chronos**: relógio analógico e digital, calendário brasileiro com mapeamento de feriados e festas, cronograma diário de tarefas, contagens regressivas (próximos anos e feriados do Brasil) e calculadora com conversão de horas para minutos.

O aplicativo funciona de forma **offline e autônoma**: a interface HTML/JS/CSS está embutida nos assets do APK e é executada em um WebView local em modo imersivo (tela cheia, sem barras do sistema). A sincronização de dias, meses e contagens ocorre exclusivamente pelo relógio do aparelho. A internet é usada apenas pela verificação de atualizações — sem conexão, tudo continua funcionando normalmente.

## Funcionalidades

- Relógio analógico com ponteiro contínuo (modo sweep) e som de tique-taque opcional.
- Display digital com data completa em português.
- Tema claro e escuro, sincronizado com a preferência do sistema.
- Modo imersivo de tela cheia: barra de status e de navegação ocultas.
- Calendário mensal com destaque do dia atual e de feriados e festas brasileiras (incluindo datas móveis calculadas pelo algoritmo de Gauss, como Carnaval e Páscoa).
- Cronograma diário com adição, conclusão e remoção de tarefas.
- Contagem regressiva dos próximos cinco anos e de todos os feriados catalogados.
- Calculadora com operações básicas e conversão de horas para minutos.
- Bloqueio de zoom, seleção de texto e menus nativos do navegador, com comportamento de aplicativo.
- **Sistema de atualização**: o aplicativo verifica automaticamente a versão instalada contra a última versão publicada no GitHub e avisa quando há uma nova; também é possível verificar manualmente pelo botão de download na barra superior. A nova versão é instalada diretamente sobre a anterior, sem precisar desinstalar nada.

## Sistema de versões e atualização

Cada execução do CI produz uma versão nova e crescente (`1.N`, em que `N` é o número da execução) e publica:

- Um **Release** do GitHub (`v1.N`) com o APK `chronos-v1.N.apk` pronto para download direto.
- Um artefato `chronos-apk` na execução do workflow.

A **chave de assinatura estável** (`keystore/chronos-release-key.jks`) é gerada automaticamente na primeira execução do CI e persistida no repositório. Todas as versões seguintes são assinadas com a mesma chave, por isso o Android aceita a instalação como atualização sobre qualquer versão anterior, sem exigir desinstalação.

Observação de segurança: a chave fica versionada no repositório por conveniência. Se o repositório for público e isso for uma preocupação, recomenda-se movê-la para GitHub Secrets (keystore em base64 e senhas) e ajustar o workflow e o `app/build.gradle` para lê-las de lá.

## Estrutura do projeto

```
.
├── .github/workflows/android-build.yml   # CI: chave estável, APK versionado e Releases
├── keystore/chronos-release-key.jks      # Chave de assinatura estável (gerada pelo CI)
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

## Como obter e atualizar o aplicativo

1. Abra a página de [Releases](https://github.com/768ull/R-C-D-C/releases) do repositório.
2. Baixe o APK da versão mais recente (`chronos-v1.N.apk`).
3. Instale normalmente: o Android reconhecerá a assinatura idêntica e instalará como atualização, preservando o aplicativo já existente.

Alternativa: dentro do próprio aplicativo, toque no ícone de download na barra superior; se houver versão nova, um aviso aparecerá com o botão Atualizar, que abre a página da nova versão para download.

## Como compilar manualmente

### Android Studio

1. Clone o repositório: `git clone https://github.com/768ull/R-C-D-C.git`
2. Abra a pasta no Android Studio (aguarde a sincronização do Gradle).
3. Selecione **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

### Linha de comando

Requisitos: JDK 17 e Gradle 8.7 (ou Android Studio com SDK 34 instalado).

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

O build local usa a mesma chave de assinatura do repositório, quando presente em `keystore/`.

## Requisitos

- Android 7.0 (API 24) ou superior.
- Permissão de internet usada apenas pela verificação de atualizações; todas as demais funções prescindem de conexão.

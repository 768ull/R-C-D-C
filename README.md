# Chronos — aplicativo Android

O **Chronos** é um aplicativo Android com interface construída em HTML, CSS e JavaScript, integrado a recursos nativos do Android por Java e WebView.

O repositório é público e o código original do projeto está disponibilizado sob a licença **MIT**. O histórico de versões publicadas, desde as primeiras releases até a v1.55, está indexado em [SOURCE_CODE.md](SOURCE_CODE.md).

## Funcionalidades

- Relógio analógico e digital, com ponteiro contínuo opcional e som de tique-taque.
- Personalização da cor do ponteiro dos segundos, com paleta e seletor de cor personalizado.
- Temas claro e escuro, com preferência salva no dispositivo.
- Despertador nativo do Android, com repetição por dias, vibração, toque, soneca e restauração após reinicialização.
- Calendário brasileiro, feriados e datas comemorativas.
- Cronograma diário com tarefas salvas localmente.
- Contagens regressivas e eventos personalizados.
- Cronômetro, calculadora e conversão de horas em minutos.
- Bússola baseada nos sensores do aparelho.
- Previsão do tempo com dados do Open-Meteo.
- Mapa baseado em OpenStreetMap e Leaflet, com pesquisa de lugares.

As funções locais — como relógio, calendário, calculadora e alarmes já programados — continuam disponíveis sem depender de uma conexão contínua. Previsão do tempo, mapas e pesquisas externas precisam de internet.

## Código-fonte e versões

- [Código atual (branch main)](https://github.com/768ull/R-C-D-C/tree/main)
- [Todas as versões e seus códigos-fonte](SOURCE_CODE.md)
- [Todas as releases e APKs](https://github.com/768ull/R-C-D-C/releases)
- [Histórico de alterações](CHANGELOG.md)

Cada release do GitHub está associada a uma tag. Abrindo a tag é possível navegar pelo código exatamente como estava naquela versão; a página da release também disponibiliza os arquivos de origem gerados automaticamente pelo GitHub.

## Estrutura do projeto

```text
.
├── .github/workflows/android-build.yml  # compilação e publicação automatizadas
├── app/
│   ├── build.gradle                     # configuração do módulo Android
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/index.html             # interface HTML, CSS e JavaScript
│       ├── java/com/chronos/app/         # integração Android em Java
│       └── res/                          # ícones e recursos Android
├── gradle/wrapper/                      # configuração do Gradle Wrapper
├── build.gradle
├── settings.gradle
├── gradle.properties
├── LICENSE
├── SECURITY.md
└── SOURCE_CODE.md
```

Arquivos de assinatura são credenciais de publicação, não código-fonte, e não devem ser compartilhados em cópias de desenvolvimento.

## Como compilar

### Requisitos

- JDK 17.
- Android SDK 34.
- Gradle 8.7.
- Android 7.0 (API 24) ou superior para executar o aplicativo.

### Pelo Android Studio

1. Clone o repositório.
2. Abra a pasta do projeto no Android Studio.
3. Aguarde a sincronização do Gradle.
4. Use **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Pela linha de comando

```bash
gradle assembleDebug --no-daemon
```

O APK de desenvolvimento será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

A compilação local pode usar uma chave de depuração criada pelo Android Gradle Plugin. Para publicar uma atualização compatível com instalações existentes, a assinatura precisa seguir a política de gerenciamento de chaves descrita em [SECURITY.md](SECURITY.md).

## Dependências e serviços externos

O módulo Android utiliza AndroidX AppCompat e AndroidX WebKit. O mapa carrega Leaflet 1.9.4 e blocos do OpenStreetMap; pesquisas de lugares e previsão do tempo dependem dos respectivos serviços externos. Esses componentes e serviços mantêm suas próprias licenças e condições de uso.

A licença MIT deste repositório se aplica ao código original do Chronos. Ela não substitui nem altera as licenças de bibliotecas, serviços, mapas ou outros materiais de terceiros.

## Licença

O código original do Chronos está sob a licença [MIT](LICENSE). Consulte o arquivo da licença para os termos completos.

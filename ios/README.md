# Chronos para iPhone

Esta pasta contém o projeto iOS independente. A versão Android continua em `app/` e não compartilha código nativo com o iPhone.

## Estado desta primeira etapa

- Projeto SwiftUI com `WKWebView`, reutilizando `Resources/index.html`.
- Ponte JavaScript própria para iOS, substituindo as interfaces Java Android.
- Bússola baseada em Core Location.
- Pesquisa de cidades, previsão Open-Meteo, geolocalização e busca de locais do OpenStreetMap.
- Alarmes guardados no iPhone e agendados como notificações locais.
- Base de compilação do simulador no GitHub Actions.

### Limitações que precisam ficar claras

- Os alarmes desta primeira versão usam notificações locais do iOS, não o AlarmKit. O sistema pode apresentar restrições de entrega conforme permissões, Foco e ajustes de notificações; não é uma réplica do comportamento de alarme do Android.
- O som configurável do Android é substituído pelo som padrão de notificação do iPhone.
- O botão que abre o Relógio do Android não tem equivalente público direto no iOS.
- O widget Android de terceiros não pode ser incorporado no iPhone. Um widget próprio de tela inicial com WidgetKit será uma etapa separada.
- A interface e seus temporizadores são reaproveitados; atividades JavaScript não devem ser consideradas execução garantida em segundo plano no iOS.

## Gerar e testar

O projeto Xcode é criado a partir de `project.yml` usando XcodeGen:

```sh
brew install xcodegen
cd ios
xcodegen generate --spec project.yml
xcodebuild -project Chronos.xcodeproj -scheme Chronos \
  -sdk iphonesimulator -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

O GitHub Actions executa essa compilação em um runner macOS e publica o app de simulador como artefato.

## Gerar IPA para TestFlight / App Store Connect

O IPA distribuível exige assinatura Apple e perfil de provisionamento compatível com o identificador `com.chronos.app.ios`. Configure estes GitHub Actions Secrets:

| Secret | Conteúdo |
|---|---|
| `IOS_CERTIFICATE_P12` | Certificado Apple Distribution em Base64 |
| `IOS_CERTIFICATE_PASSWORD` | Senha do arquivo P12 |
| `IOS_PROVISIONING_PROFILE` | Perfil App Store Connect em Base64 |
| `IOS_TEAM_ID` | ID da equipe Apple de 10 caracteres |

O perfil precisa corresponder ao Bundle ID `com.chronos.app.ios` e à mesma equipe. O workflow só tenta exportar o IPA quando todos os secrets estão presentes. Sem eles, produz o artefato para simulador e informa o que falta para a assinatura.

Nunca coloque certificados, senhas ou perfis privados no repositório.

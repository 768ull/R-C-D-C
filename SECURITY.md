# Segurança e assinatura do Chronos

## Código-fonte versus chave de assinatura

O código do Chronos é aberto sob a licença MIT. Uma chave de assinatura Android, certificado de publicação ou senha **não é código-fonte** e não deve ser distribuído em arquivos ZIP/TAR de código, commits ou releases.

## Situação do histórico de assinatura

O repositório público contém um arquivo de assinatura Android legado no histórico Git e o workflow antigo também configura valores padrão de assinatura. Como o repositório é público, essa chave deve ser considerada exposta. Não compartilhe, copie ou reutilize o arquivo de assinatura a partir do histórico público.

A chave foi usada para assinar versões já distribuídas. Trocar ou remover a chave sem planejamento pode impedir que o Android instale a próxima versão por cima do aplicativo existente. Antes de alterar o processo de assinatura, o mantenedor deve:

1. Guardar uma cópia privada e segura dos materiais necessários para avaliar uma migração.
2. Configurar os segredos de assinatura no GitHub Actions, em vez de manter credenciais no repositório.
3. Planejar a rotação da chave e testar a compatibilidade de atualização nos Androids suportados.
4. Retirar materiais privados do conteúdo público futuro e, se necessário, tratar também o histórico Git e as tags antigas.

Não publique valores de senha, certificados privados ou conteúdo de arquivos de assinatura em issues, commits ou documentação.

## Relato de vulnerabilidades

Não publique detalhes exploráveis ou credenciais em issues públicas. Entre em contato com o mantenedor do repositório por um canal privado antes de divulgar uma vulnerabilidade.

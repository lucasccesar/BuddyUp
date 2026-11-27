# Autenticação e sessões

Decisão da [SEG-01](https://github.com/lucasccesar/BuddyUp/issues/13), com base em AR-02, BD-02, RF-001 a RF-005, RF-031 e RN-003. O [OpenAPI](../api/openapi.yaml) define a superfície HTTP desta decisão. As rotas estão planejadas e o Prism simula seus resultados.

## Manter conectado

| Opção no login | Site | App |
| --- | --- | --- |
| Desmarcada por padrão | Cookie de sessão, sem `Max-Age` nem `Expires`. Em uma nova sessão do navegador, pedir login. | Manter a credencial na memória do processo. Voltar do segundo plano mantém o login enquanto o processo estiver vivo. Reiniciar o processo pede login. |
| Marcada | Cookie persistente neste navegador. Renovar sua duração durante o uso, respeitando os limites do navegador. | Guardar a credencial localmente com Expo SecureStore e revalidar no servidor ao retomar. Sem nova autenticação programada apenas por fechar o app. |

A opção vale por login e dispositivo. Sair, redefinir a senha e sofrer suspensão encerram o acesso conforme a seção de revogação. Alguns navegadores recuperam cookies temporários ao restaurar a sessão anterior. O site segue o ciclo da sessão do navegador. Fechar uma aba também não encerra a sessão das demais abas. Se o sistema encerrar o processo do app em segundo plano, reabri-lo conta como reinício. [Cookies de sessão](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie) · [Expo SecureStore](https://docs.expo.dev/versions/latest/sdk/securestore/)

## Emissão, verificação e revogação

- O backend emite uma credencial opaca aleatória por sessão e guarda somente seu resumo no PostgreSQL. O registro associa conta, canal, persistência, última atividade, validade opcional e revogação. Cada instância consulta a mesma fonte para autorizar requisições. A primeira implementação usa PostgreSQL diretamente e mede esse custo em testes de carga.
- A sessão persistente continua elegível enquanto a credencial estiver disponível no cliente e válida no servidor. Sessões temporárias abandonadas expiram após 24 horas sem uso, com a atividade registrada de forma limitada. A opção fica gravada na criação da sessão, com `rememberMe: false` quando omitida.
- No site, a aplicação e a API são servidas pela mesma origem. A API entrega a credencial só em cookie `__Host-buddyup_session` com `HttpOnly`, `Secure`, `Path=/`, `SameSite=Lax` e sem atributo `Domain`. Comandos via cookie exigem origem confiável e `X-CSRF-Token` vinculado à sessão. A SPA obtém a prova na consulta da sessão. Login e fluxos públicos que possam criar uma sessão no navegador validam a origem e usam retornos de provedor correlacionados ao fluxo.
- O app recebe a credencial no corpo por HTTPS e a envia em `Authorization: Bearer`. O backend separa as credenciais por canal e rejeita a solicitação de um token de app quando a requisição vem de uma origem web. O campo `client` seleciona o contrato de resposta, mas não substitui essas verificações. Respostas com credenciais usam `Cache-Control: no-store`. Credenciais e provas de acesso ficam fora de logs e telemetria.
- O logout revoga só a sessão atual, encerra suas conexões e remove a credencial do cliente. Redefinir a senha, suspender ou excluir uma conta revoga todas as suas sessões. A alteração de conta e a revogação são persistidas juntas. As demais instâncias e os canais de tempo real recebem a revogação após a gravação, revalidam o acesso em operações protegidas e encerram inscrições afetadas. Uma sessão revogada retorna `401 SESSION_REVOKED`, e o cliente remove a credencial antes de pedir login.
- A sessão autenticada não substitui a autorização. O backend lê o estado atual da conta, a relação com o recurso e os cargos persistidos. Cargos no retorno da sessão orientam a apresentação. Mudar um cargo invalida acessos privilegiados e inscrições que dependiam dele.

## Cadastro e métodos de acesso

- O cadastro por e-mail começa pela confirmação de um código de uso único. A confirmação gera uma autorização curta e restrita à continuação do cadastro. Dados mínimos e declaração de 18+ ativam a conta. Um primeiro login social pode retornar `PROFILE_REQUIRED`; essa sessão só pode retomar o perfil pendente, consultar o próprio estado e sair.
- Inicie Google ou Apple no navegador do sistema, em um fluxo associado ao canal e ao cliente que o iniciou. Use destinos de retorno registrados. O backend valida assinatura, emissor, público, validade e correlação com `state` e `nonce`, além de usar PKCE no provedor quando disponível. No retorno, o cliente troca um código descartável apresentando o verificador original, que corresponde ao desafio SHA-256 enviado na abertura do fluxo. A troca produz uma prova de vida curta, vinculada ao fluxo e de uso único. Login, vínculo e nova confirmação usam essa prova. [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect) · [Verificação de identidade da Apple](https://developer.apple.com/documentation/signinwithapple/verifying-a-user)
- A chave da identidade é `(provider, subject)`, única no banco. Um login com identidade já vinculada usa a conta desse vínculo. Para Google ainda sem vínculo, só vincule automaticamente se o e-mail coincidente já foi confirmado pelo BuddyUp e o Google for autoridade sobre o endereço (Gmail ou Workspace confirmado). Para coincidência com e-mail externo ou para Apple, retorne `409 ACCOUNT_PROOF_REQUIRED`, sem emitir sessão nem expor dados da conta. O usuário entra pelo método existente, confirma o acesso novamente e vincula pelo fluxo autenticado. Um endereço de relay da Apple é tratado como o endereço entregue pela Apple. [Validação do e-mail Google](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
- Vincular e desvincular exigem confirmação recente por método já associado. A prova vale uma vez, para uma finalidade e para a mesma conta e sessão. Um vínculo já pertencente a outra conta retorna `409 IDENTITY_ALREADY_LINKED`. Garanta a unicidade dentro da transação. Se a remoção deixar a conta sem método de acesso, retorne `409 LAST_LOGIN_METHOD` e peça para configurar e-mail e senha.
- Recuperação usa link de uso único e revoga as sessões quando a senha é redefinida. A troca de e-mail exige confirmação do novo endereço e envia aviso ao antigo. Quem já tem senha confirma a senha atual. Para configurar e-mail e senha pela primeira vez, confirme acesso pelo método social já vinculado e valide o endereço antes de cadastrar a senha. A exclusão exige confirmação por um método vinculado. Os endpoints planejados deixam os clientes simular os fluxos enquanto CTA-01, CTA-03 e CTA-06 implementam seus efeitos.

## Permissões internas

**Catálogo** gerencia o catálogo. **Moderação** trata denúncias e medidas autorizadas. Os dois cargos podem ser acumulados. **Dono** tem as funções internas e pode gerenciar acessos. A alteração de cargos exige nova confirmação de acesso e registra autor, data e motivo. Um cargo enviado pelo cliente nunca concede autoridade ao ator.

## Validação de ameaças

| Cenário | Resultado esperado | Verificação |
| --- | --- | --- |
| Sessão anterior após redefinição de senha ou suspensão | Operações protegidas falham com `401 SESSION_REVOKED`, e conexões afetadas são encerradas. | Resposta testada no mock. Comprovar revogação real entre instâncias nas entregas que implementam essas ações. |
| Identidade Google ou Apple vinculada a outra conta | `409 IDENTITY_ALREADY_LINKED` sem mudar a propriedade do vínculo. | Resposta testada no mock. Verificar concorrência e integridade do vínculo na implementação. |
| Google com e-mail externo coincidente | `409 ACCOUNT_PROOF_REQUIRED` sem nova sessão, seguido de confirmação na conta já existente. | Resposta testada no mock. Verificar a validação do provedor e ausência de efeitos na implementação. |
| Pessoa sem cargo Dono tenta alterar cargos | `403 ROLE_FORBIDDEN` sem mudança no acesso. | Resposta testada no mock. Validar permissões persistidas e auditoria na implementação do painel. |
| Solicitação via cookie sem prova de origem ou de CSRF | Comando recusado e dados preservados. | Conferir os dois requisitos no OpenAPI e executar teste de integração quando o filtro existir. |
| Prova de provedor, reautenticação ou recuperação expirada ou reapresentada | Prova recusada sem sessão, vínculo ou alteração adicional. | Validar consumo de uso único e concorrência na implementação de cada fluxo. |

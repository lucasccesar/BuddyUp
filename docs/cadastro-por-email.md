# Cadastro por e-mail

A [CTA-01](https://github.com/lucasccesar/BuddyUp/issues/14) implementa as três operações de cadastro do [OpenAPI](../api/openapi.yaml), conforme RF-001, RF-006 e BD-02.

1. Envie o e-mail a `POST /v1/auth/registration/email-challenges`. A resposta `202` contém o `challengeId`. O código de seis dígitos chega por e-mail. Para um endereço já cadastrado, a resposta tem o mesmo formato e o serviço não envia um código.
2. Envie `challengeId` e `code` a `POST /v1/auth/registration/email-confirmations`. O servidor consome o código e retorna `registrationToken`. Guarde essa prova no cliente para reabrir a última etapa enquanto ela estiver válida. A prova só dá acesso à conclusão do cadastro. A interface pode guardar localmente o progresso do formulário, com exceção da senha.
3. Envie a prova, a senha e o perfil a `POST /v1/auth/registration`. Exija nome, nickname, país ou região, pelo menos um idioma e a declaração de 18+. O retorno `201` indica que a conta, o perfil e os idiomas foram gravados e a prova foi consumida. Se o nickname estiver ocupado, a mesma prova permite tentar outro.

O código vale 10 minutos e permite 5 tentativas. Um novo código invalida o anterior. A prova vale 24 horas. O reenvio tem espera de 60 segundos, com limite de 5 solicitações por e-mail e 30 por endereço de conexão em uma janela de uma hora. O limite de origem usa o endereço observado pelo servidor. Em ambientes com proxy, configure o encaminhamento confiável antes de aplicar o limite a usuários reais.

O PostgreSQL guarda os desafios em `buddyup.auth_challenge` com a distinção entre código e prova. A API usa uma chave de servidor para resumir o código e persiste somente o resumo da prova. A senha fica armazenada como hash PBKDF2. A migração também cria `account`, `player_profile` e `profile_language`. A unicidade usa o e-mail e a chave do nickname normalizados. A conta e o perfil são confirmados na mesma transação.

## Executar localmente

Na raiz, suba o PostgreSQL e a caixa de e-mail local:

```powershell
docker compose up -d --wait postgres mailpit
$env:BUDDYUP_REGISTRATION_CODE_KEY = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

O SMTP do Mailpit recebe mensagens na porta `11025`. Abra a [caixa de entrada local](http://127.0.0.1:18025) para ver o código. Em outros ambientes, configure `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `BUDDYUP_REGISTRATION_FROM` e `BUDDYUP_REGISTRATION_CODE_KEY`. Use a mesma chave aleatória de pelo menos 32 bytes para todas as instâncias do ambiente e guarde-a no gerenciador de segredos. A escolha do provedor de e-mail e a integração com a fila ficam na [NOT-02](https://github.com/lucasccesar/BuddyUp/issues/46).

Para executar os testes, rode `.\mvnw.cmd verify` na pasta `backend/`. Com Docker ativo, o Testcontainers abre um PostgreSQL vazio e um Mailpit temporário. Os testes exercitam a API por HTTP e leem os códigos entregues pelo SMTP.

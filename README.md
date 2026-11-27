# BuddyUp

Encontre parceiros para jogar agora ou montar uma equipe recorrente.

| Pasta | Projeto | Porta local |
| --- | --- | --- |
| `backend/` | API Spring Boot | `8080` |
| `web/` | Site React com Vite | `5173` |
| `mobile/` | App React Native com Expo | `8081` |
| Raiz | PostgreSQL de desenvolvimento via Docker Compose | `15432` |

## Pré-requisitos

- Java 21. Confira a versão com `java -version` e aponte `JAVA_HOME` para o JDK 21 caso tenha várias versões instaladas. O Maven Wrapper baixa a versão de Maven usada pelo projeto.
- Node.js 24 e npm. Confira com `node --version` e `npm --version`.
- Docker com Compose v2 ou superior. Confira com `docker compose version` e mantenha o serviço do Docker rodando.
- Para o app, tenha um aparelho Android ou iOS com uma versão do Expo Go compatível com o SDK 58. O simulador de iOS requer macOS com Xcode. O emulador de Android requer Android Studio com um dispositivo virtual configurado.

Os exemplos usam PowerShell. Em macOS ou Linux, use `./mvnw` no lugar de `.\mvnw.cmd`. Execute cada bloco a partir da raiz do repositório, em terminais separados para manter os servidores rodando.

## Backend e banco

```powershell
docker compose up -d --wait postgres
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

Abra [a verificação do banco](http://127.0.0.1:8080/actuator/health/db). A resposta esperada é HTTP 200 com `{"status":"UP"}`.

Na primeira execução com um volume vazio, o PostgreSQL pode levar mais tempo que a janela do healthcheck do Compose. Se aparecer `container buddyup-postgres-1 is unhealthy`, acompanhe a inicialização com `docker compose logs -f postgres` até o servidor definitivo informar que está aceitando conexões. Pressione Ctrl+C para sair dos logs, repita `docker compose up -d --wait postgres` e inicie o backend após o comando confirmar que o banco está saudável.

O perfil `local` usa o banco `buddyup` em `127.0.0.1:15432`, com as credenciais de desenvolvimento do Compose. Se a porta estiver ocupada, configure `BUDDYUP_POSTGRES_PORT` com a mesma porta livre nos terminais do Compose, do backend e dos testes. Em PowerShell, use `$env:BUDDYUP_POSTGRES_PORT = '15434'`. Em bash, use `export BUDDYUP_POSTGRES_PORT=15434`.

Para parar o backend, pressione Ctrl+C. Para parar o PostgreSQL, execute `docker compose stop postgres` na raiz. Os dados locais ficam no volume do Compose.

## Site

```powershell
cd web
npm ci
npm run dev
```

Abra [o site local](http://localhost:5173) e confira o título **BuddyUp**. Se a porta estiver ocupada, use a URL que o Vite imprimir no terminal.

## App

```powershell
cd mobile
npm ci
npm start
```

Abra o projeto pelo QR code em um aparelho na mesma rede e confira a tela **BuddyUp**. Com um emulador Android iniciado, pressione `a`. No macOS, com o simulador iOS instalado, pressione `i`. Consulte as instruções oficiais de instalação do Expo Go para o SDK do projeto se a versão da loja for incompatível.

- [Iniciar o desenvolvimento com Expo](https://docs.expo.dev/get-started/start-developing/)
- [Compatibilidade de versões do Expo Go](https://docs.expo.dev/troubleshooting/expo-go-version-mismatch/)

## Checagens

Execute os comandos no diretório indicado:

| Diretório | Comandos | Resultado esperado |
| --- | --- | --- |
| `backend/` | `.\mvnw.cmd verify` | Fronteiras dos módulos, migração inicial em PostgreSQL e empacotamento são verificados. O Testcontainers sobe um banco temporário com o Docker. |
| `web/` | `npm run lint`, `npm run typecheck`, `npm run build` | Lint, tipos e build de produção passam. |
| `mobile/` | `npm run lint`, `npm run typecheck`, `npx expo export --platform all` | Lint, tipos e geração dos bundles Android e iOS passam. Confira a tela em aparelho ou simulador para validar a execução nativa. |

O workflow **Verificar monorepo** executa as checagens por área nos PRs para a `main`. Para validar o onboarding, em um clone limpo, inicie as três partes seguindo este README e registre os comandos, as plataformas testadas e os resultados no PR.

Para adicionar módulos, APIs internas ou migrações, consulte a [arquitetura do backend](docs/arquitetura-backend.md).

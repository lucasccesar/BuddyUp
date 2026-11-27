# Contrato da API

O [OpenAPI versionado](../api/openapi.yaml) é a referência para o backend, o site e o app. As operações iniciais de catálogo e aceite estão marcadas como planejadas e podem ser chamadas no mock. Atualize o contrato e os tipos TypeScript no mesmo PR quando alterar rotas, dados, erros ou requisitos de autenticação.

## Convenções

- **HTTP:** prefixo `/v1`, recursos em inglês, campos JSON em `camelCase`, IDs públicos em UUID e instantes em ISO 8601 com fuso. Use `200` quando retornar uma representação, `201` com `Location` na criação de um recurso, e `204` quando a operação terminar sem corpo.
- **Paginação:** envie `limit` de 1 a 100, com padrão 20, e `cursor` retornado pela página anterior. Leia `items` e `nextCursor`; `null` encerra a paginação. Reinicie sem cursor ao trocar filtros. O servidor usa ordenação estável com desempate e aplica as regras de visibilidade antes de paginar.
- **Erros:** use `application/problem+json`, com `type`, `title`, `status` e `code`. Para validações de campo, `fieldErrors` pode identificar campos e códigos. Os clientes localizam mensagens pelo `code` e usam um aviso genérico para códigos desconhecidos. O servidor evita expor exceções, dados privados e detalhes internos.
- **Autenticação:** rotas públicas declaram `security: []`. Rotas protegidas declaram `webSession` para navegador ou `nativeSession` para app. Comandos do navegador exigem também `csrfToken`. Use `401` para sessão ausente ou inválida, `403` para ator autenticado sem permissão e `404` quando revelar o recurso seria indevido. Consulte [sessões e contas](autenticacao-e-sessoes.md).
- **Comandos:** use `POST` para transições como `/v1/interactions/{interactionId}/accept`. O servidor verifica autorização, prazo e estado atual na transação. Use `409` quando o estado ou a concorrência impedir a ação, e `422` quando uma regra de elegibilidade impedir uma solicitação válida. Documente por operação se um reenvio repete o resultado ou é tratado como conflito.

## Mock para React e Expo

Com Node.js 24.18.0 ou posterior da série 24, execute na pasta `api/`:

```sh
npm ci
npm run mock
```

O Prism serve os exemplos do próprio contrato em `http://127.0.0.1:4010`. Para testar no navegador, use essa URL como base no cliente web. Em um aparelho na mesma rede, inicie com `npm run mock -- --host 0.0.0.0` e use o IP do computador na URL do app. Use somente dados fictícios no mock. Os exemplos de rotas protegidas simulam respostas e dispõem de `Prefer: code=401`, `Prefer: code=409` e os demais códigos documentados para exercitar estados de erro.

Em React e Expo, importe `listGames` de `api/client.ts`, passando a URL base e os parâmetros, como `listGames(baseUrl, { limit: 1 })`. Os tipos do contrato ficam em `api/generated/schema.d.ts`. Ambos os clientes podem importar `paths`, `operations` ou `components` como tipos, sem dependência de execução para a geração.

## Verificação no PR

Com o Docker ativo e as dependências instaladas, execute estes comandos em `api/`:

```sh
npm run lint
npm run generate
npm run check:types
npm test
npm run check:compatibility
```

Inclua os tipos gerados no commit. O job **Backend** exige lint válido, tipos atualizados, chamadas ao mock e compatibilidade com o contrato da branch de destino. O oasdiff também testa um exemplo conhecido de remoção de operação para verificar que o CI falha quando detecta incompatibilidade. Se uma mudança precisar quebrar o contrato, planeje a transição com os clientes instalados antes de integrar.

A transição da SEG-01 tem uma exceção restrita à versão que marcava o aceite como planejado e protegido por meio de `x-buddyup-session`. Nessa base, o oasdiff interpreta a promoção para `security` como remoção de acesso anônimo. A exceção vale só para esse aviso nessa versão do contrato.

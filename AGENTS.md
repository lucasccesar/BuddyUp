# AGENTS.md

## Visão geral do projeto

O BuddyUp usa Spring Boot e PostgreSQL em `backend/`, React em `web/` e React Native com Expo em `mobile/`. O site e os apps usam a mesma API REST. Siga estas regras em todo o repositório.

## Contexto do domínio

- Identifique qual dos quatro fluxos a issue afeta: grupo procurando alguém para completar, pessoa disponível para completar grupo, equipe recrutando e pessoa disponível para entrar numa equipe.
- Confira no fluxo correspondente o que inicia a interação, se há vaga vinculada, quando ela expira e quando o chat fecha. Por exemplo, um convite para jogar pode ser direto, e um convite de recrutamento exige uma vaga publicada.
- Quando a mudança afetar interface, consulte o protótipo e os estados descritos na issue. Use o backlog para localizar as dependências de protótipo, contrato e implementação.

## Trabalhe a partir da issue

1. Leia **Descrição**, **Atividades**, **Critérios de aceitação**, **Como concluir** e os vínculos **Bloqueada por**. Consulte as regras, o modelo de dados e o OpenAPI pertinentes. Se faltar uma decisão que mude o comportamento esperado, explique o impacto e peça a decisão antes de implementar essa parte.
2. Para mudanças no repositório, confira a branch e as alterações locais. Preserve o trabalho alheio. Use branch curta a partir da `main` atualizada, como `feat/42-aceitar-candidatura`. Para entregas externas, registre a evidência no card. Uma issue mista só termina após validar as duas partes.
3. Implemente o escopo da issue e verifique casos equivalentes que a mudança pode afetar. Se encontrar outro problema fora do escopo, registre-o separadamente. Use os comandos reais do repositório e as dependências já adotadas pela equipe.
4. Separe mudanças independentes em commits focados e siga a seção **Mensagens de commit**.

## Mensagens de commit

- Escreva todas as mensagens de commit em inglês, incluindo o escopo, a descrição e o corpo, quando houver. Use Conventional Commits no formato `type(scope): description`.
- Use uma linha curta, com verbo no imperativo, que descreva o que mudou. Acrescente um corpo apenas quando a linha não der conta de uma informação necessária. Exemplo: `fix(slots): prevent duplicate acceptance`.
- Antes de publicar, confira o autor, a mensagem e os arquivos do commit. Mantenha commits, PRs, issues e documentos livres de `Co-Authored-By`, assinaturas e atribuições automáticas de ferramentas.

## Fontes do projeto e divergências

- **Comportamento e estados:** consulte os requisitos e fluxos do BuddyUp, a issue aprovada e as decisões relacionadas. Siga as dependências das issues e os marcos do backlog. Com contrato e protótipo definidos, web e mobile podem trabalhar com respostas simuladas antes da integração.
- **Contrato HTTP e persistência:** consulte o OpenAPI para rotas, campos, respostas e autenticação. Consulte a modelagem e as migrações vigentes para entidades, chaves e restrições. Evite manter um inventário de rotas duplicado neste guia.
- Se código, OpenAPI, modelo ou issue divergirem sobre o comportamento esperado, aponte a divergência e proponha a solução antes de alterar essa parte. Peça uma decisão quando resolver a divergência mudar o escopo, uma decisão já aprovada ou um contrato público. Atualize os documentos afetados junto com o código depois que a decisão estiver clara.
- Registre regras de negócio por funcionalidade, com referência ao requisito e ao trecho que as aplica quando já estiverem implementadas. Registre decisões técnicas no documento de arquitetura. Se a equipe mantiver um registro de progresso, anote nele apenas entregas verificadas, com data e referência à issue ou ao PR. As próximas tarefas pertencem ao backlog.

## Padrões de escrita de código

- **Nomes:** escreva identificadores em inglês e use o mesmo termo para o mesmo conceito em cada camada. Use `PascalCase` para classes, tipos e componentes, `camelCase` para métodos, funções e variáveis, e `UPPER_SNAKE_CASE` para constantes e valores de enums, quando aplicável. Use os nomes de rotas e campos públicos definidos no OpenAPI. Textos exibidos ao usuário seguem os arquivos de tradução.
- **Legibilidade:** prefira funções curtas com uma responsabilidade e nomes que expliquem o comportamento. Extraia código compartilhado quando a mesma regra aparecer em mais de um lugar. Evite comentários e Javadoc por padrão. Registre uma restrição de compatibilidade ou de ordem de operações quando o código não for suficiente para explicá-la.
- **Formatação:** siga o formatador, o linter e as convenções de imports já configurados em cada stack. Restrinja a formatação aos arquivos da mudança. Se a configuração ainda não existir, defina-a com a equipe em uma issue própria.
- **React e Expo:** organize telas e componentes por funcionalidade. Use `PascalCase` em componentes e prefixo `use` em hooks. Mantenha acesso à API no cliente compartilhado pela respectiva aplicação. Tipos de request e response devem seguir o OpenAPI. Centralize chaves de tradução e preserve as diferenças de plataforma perto do código que as usa.
- **Nomes de testes:** em Java, use `should<ExpectedBehavior>When<Condition>` em `camelCase`, como `shouldRejectInviteWhenExpired`. Em cada `@Test` do JUnit, use `@DisplayName` em inglês no formato `[methodUnderTest] Should <Expected Behavior> - When <Condition>`. Escreva entre colchetes o nome exato do método testado e preserve a grafia dos tipos citados. Exemplo: `@DisplayName("[acceptInvite] Should Reject Invite - When Invite Has Expired")`. Em web e mobile, descreva em inglês a condição e o resultado observável, como `shows an error when the invite expires`.

## Comandos e configuração local

- No backend, use o wrapper de build versionado. Se for Maven, rode `mvnw.cmd test` no Windows ou `./mvnw test` nos demais ambientes. Passe `"-Dtest=InviteServiceTest"` para executar uma classe ou `"-Dtest=InviteServiceTest#shouldRejectInviteWhenExpired"` para executar um método. Consulte o README para os comandos de inicialização e os pré-requisitos do PostgreSQL de testes.
- Em web e mobile, use o gerenciador indicado pelo arquivo de dependências e pelo lockfile. Rode os scripts de lint, teste, checagem de tipos e build da área alterada, quando configurados. Documente novos comandos reproduzíveis no README da área.

## Código, contrato e dados

- **Backend:** organize Spring Boot por funcionalidade, com camadas dentro de cada módulo. Outros módulos acessam apenas as interfaces e os eventos expostos. Mantenha permissões, visibilidade e invariantes no servidor. Ao adicionar uma regra, confira as outras rotas que permitem a mesma ação.
- **API:** valide as entradas e mantenha respostas de erro consistentes com o contrato. Use códigos de domínio previstos e proteja dados internos e detalhes de exceções. Se a mudança afetar rota, campo, estado, erro ou autorização, **atualize o OpenAPI versionado no mesmo PR** e descreva o impacto para o site e o app, incluindo versões mobile já instaladas.
- **Banco:** inclua a migração no mesmo PR quando o esquema mudar. Mantenha a compatibilidade necessária durante o deploy com instâncias antigas e novas. Corrija uma migração já aplicada em ambiente persistente com outra migração. Se a operação puder ocorrer em paralelo, trate a disputa no banco e na transação. Por exemplo, o aceite não pode deixar duas pessoas ocuparem a mesma última vaga.
- **Web e mobile:** preserve os estados e mensagens definidos na issue. Use a resposta da API para tratar erro, conflito e expiração. Reutilize os componentes e os clientes de API existentes quando se encaixarem. Confira os fluxos afetados nas plataformas listadas pela issue.
- **Documentação:** atualize requisitos, modelo de dados e guias afetados no mesmo PR da mudança. Registre decisões que alteram o comportamento para que o próximo integrante consiga entender a implementação.

## Arquitetura do backend

- **Módulos e DTOs:** use o módulo existente mais próximo como referência de estrutura. Controllers adaptam HTTP para chamadas ao serviço. Serviços coordenam regras e transações. Repositórios encapsulam consultas. Revise o mapeamento de cada novo campo e mantenha entidades e dados internos fora das respostas públicas.
- **Erros:** use o tratamento de erros compartilhado. Confira falhas de validação, JSON inválido, enum desconhecido, parâmetro no formato errado e erros do domínio. Respostas inesperadas devem seguir o formato contratado e expor apenas uma mensagem genérica. Mantenha o detalhe necessário ao diagnóstico nos logs do servidor, sem expor segredos nem conteúdo privado.
- **Autorização:** verifique no servidor quem é a pessoa autenticada, o vínculo dela com o recurso e a permissão para a ação. Aplique a mesma regra nas rotas equivalentes. Dados de cargo ou propriedade informados pelo cliente não concedem acesso.
- **Paginação:** reutilize o padrão e os limites documentados para cada endpoint. Valide filtros e limites, use ordenação estável e aplique permissões e bloqueios antes de paginar. Para paginação por cursor, inclua um desempate estável na ordenação e no cursor.
- **Integridade e concorrência:** garanta unicidade e vínculos no PostgreSQL. Nomeie restrições de forma descritiva, como `uq_players_nickname_key`, e mapeie conflitos conhecidos para o erro definido no contrato. Trate retentativas de forma idempotente quando o fluxo exigir. Se uma operação precisar de mais de um bloqueio, use uma ordem consistente e mantenha chamadas externas fora da transação sempre que possível. Use o padrão transacional aprovado pelo módulo.
- **Migrações:** mantenha o SQL versionado no mesmo PR da mudança. Verifique a aplicação em um banco limpo e a atualização da versão anterior quando a estrutura existente for afetada. Preserve migrações já aplicadas e use uma nova para corrigir o esquema.

## Testes na mesma issue

- Implemente **no mesmo PR** os testes descritos em **Atividades**. As issues de QA preparam a infraestrutura compartilhada e a homologação. Para protótipos, decisões e outras entregas sem código, registre a validação pedida diretamente no card.
- Use **testes unitários** para regras isoladas, **testes de integração** para contrato HTTP, permissões e persistência, e **PostgreSQL real** para transações, restrições, concorrência e migrações. Nos clientes, cubra os estados de interface afetados e, quando a issue pedir, valide a jornada completa em homologação ou em aparelho real.
- Teste o comportamento real. Simule apenas colaboradores quando isso ajudar a isolar o cenário. Faça asserções sobre o resultado, o erro e os dados que foram gravados ou preservados. Em regras de tempo, use relógio controlado quando possível. Atualize testes afetados por mudanças de comportamento e refatorações.
- Antes de pedir o PR, confira os critérios e rode as verificações pertinentes. Em **Como testar**, informe comandos e resultados efetivamente verificados, e separe o que ainda resta para revisão ou homologação. Se uma verificação necessária estiver pendente, diga qual.

### Testes por camada

| Camada | O que o teste deve demonstrar |
|---|---|
| Serviço e domínio | Caminho principal e regras que mudam o resultado, como expiração, incompatibilidade, permissão, filtro ou reenvio. Use um relógio controlado quando o prazo faz parte do comportamento. |
| Controller isolado | Conversão dos argumentos, delegação e resposta que o próprio controller produz, como o cabeçalho `Location`. Para provar códigos HTTP de erros ou autenticação, execute a pilha MVC e os filtros necessários. |
| Integração HTTP | Validação do contrato, autenticação, autorização e efeitos persistidos. Quando o contrato prever, teste os códigos `400`, `401`, `403`, `404` e `409` nos cenários aplicáveis. Após uma rejeição, confira que os dados ficaram no estado esperado. |
| Integração PostgreSQL | Restrições, migrações, consultas e concorrência em PostgreSQL real. Na última vaga, envie dois aceites concorrentes e confira a resposta e o único ocupante gravado. Use a infraestrutura de teste adotada pelo projeto. |
| Interface e ponta a ponta | Estados observáveis, como carregamento, falha, expiração, lista vazia e sucesso. Reserve jornadas completas para fluxos críticos e integre os clientes com o contrato real em homologação. |

Escolha os cenários pelo risco e pelos critérios da issue. Use um teste de integração quando a resposta depender de filtros de segurança, SQL ou transações. Evite repetir em integração todas as combinações já cobertas pelo teste unitário. Com mocks, verifique um resultado que o código produziu ou confira o valor do argumento enviado ao colaborador, como o nickname normalizado antes de salvar. Confirme que a asserção falharia se a regra regredisse. Ao refatorar, revise nomes, asserções e mocks para que descrevam o comportamento atual.

## Checklist técnico antes da revisão

- Confira se a mudança resolve o problema da issue e se uma abordagem mais simples atenderia aos mesmos critérios.
- Para cada campo novo ou alterado, revise limites, formato, valores ausentes e o erro que o cliente recebe.
- Confira as permissões nas rotas equivalentes e os estados que podem expor dados indevidamente. Ao mexer em chat, por exemplo, confira leitura, edição, exclusão e denúncia.
- Ao mudar mais de um registro, verifique o que acontece com requisições concorrentes, com reenvios e com falha entre gravações.
- Ao alterar uma regra que atravessa módulos, confira os efeitos nos fluxos relacionados. Bloquear uma pessoa afeta amizades, convites, chat e avisos.
- Explique termos técnicos com uma frase curta quando a equipe precisar deles para entender a mudança ou tomar uma decisão.

## GitHub Projects

- Consulte o [quadro BuddyUp](https://github.com/users/lucasccesar/projects/1) antes de assumir uma issue. Ao atribuir a si mesmo uma issue **Pronta**, confira se ela passou para **Em andamento**. Se houver bloqueios ou pendências, preserve o estado e indique o que falta.
- Para issues **Pronta** ou **Em andamento** e sem bloqueios, um PR pronto do repositório com `Closes #42` move o cartão para **Em revisão**. PR em rascunho sinaliza **Em andamento**. PR parcial usa `Parte de #42` e preserva o estado da issue. Para entrega sem PR, registre a evidência no card e aplique `revisar` ao pedir revisão.
- Ao fechar uma issue como concluída, confira se o quadro a moveu para **Concluída** e se as dependentes elegíveis passaram para **Pronta**. Deixe a automação processar a mudança antes de corrigir manualmente um cartão. Na ausência de acesso ao quadro, diga qual estado falta conferir.
- **Ao encerrar cada sessão**, cite a issue ou o PR, diga o que validou e indique a próxima ação concreta. Use o estado verificado: “Issue #42 pronta para revisão. Quer que eu abra o PR?”, “PR #73 aberto. Confira a revisão” ou “Issue #42 em andamento. Falta testar o aceite simultâneo”.

## Peça confirmação e abra o PR

1. Quando a branch estiver pronta para revisão, confira o diff, os critérios de aceitação e as verificações locais pertinentes. Prepare o título e o corpo. Pergunte: “A issue #42 está pronta para revisão. Quer que eu abra o PR desta branch para `main`?”
2. Após a confirmação, publique a branch se necessário e abra o PR para `main` com o título e o corpo padronizados. Se já existir PR para a branch, use o existente e informe o link. Limite os commits ao trabalho da issue.
3. Informe o link do PR e confirme se a automação moveu a issue para **Em revisão** quando a entrega for completa. Se faltar acesso ao quadro, indique essa conferência como próxima ação. Para um PR parcial, indique o que ainda falta na issue. O merge segue após as verificações exigidas e a aprovação de outro integrante. A publicação ocorre depois da homologação, conforme o fluxo do projeto.

## Modelo de descrição do PR

Use o [modelo do repositório](.github/pull_request_template.md). Escreva em português e preencha cada seção com informações do diff e da validação. Use `Closes #42` quando o merge concluir a issue inteira, com qualquer entrega externa já conferida. Se ainda faltar uma parte, use `Parte de #42`. Use o número real da issue no GitHub. Em **Como testar**, diferencie comandos e cenários executados dos que ficam para o revisor. Marque apenas os itens comprovados no checklist.

```markdown
## Descrição

[Explique em poucas linhas o resultado e o motivo da mudança.]

## Issue relacionada

Closes #42

## Alterações realizadas

- **[Área ou arquivo]:** [o que mudou e qual comportamento isso afeta].
- **[Contrato, banco ou documentação, se aplicável]:** [impacto para quem usa ou mantém o sistema].

## Como testar

1. [Informe os comandos executados, o diretório e o resultado.]
2. [Descreva um cenário reproduzível e o resultado esperado.]

## Checklist

- [ ] O código segue os padrões do projeto.
- [ ] As verificações pertinentes foram executadas e seus resultados estão registrados.
- [ ] As mudanças se limitam ao escopo da issue.
- [ ] OpenAPI, migrações e documentação foram atualizados quando afetados.
- [ ] O PR está pronto para revisão.

## Observações

[Registre pendências, limitações ou verificações que ficaram para o revisor. Se não houver, escreva “Sem observações”.]
```

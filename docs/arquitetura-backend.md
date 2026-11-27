# Arquitetura do backend

O BuddyUp usa um monólito modular Spring Boot. Os módulos são pacotes de primeiro nível em `com.buddyup.api` e são publicados como uma única aplicação. As declarações em `package-info.java` indicam quais dependências diretas são permitidas.

| Módulo | Responsabilidade |
| --- | --- |
| `accounts` | Contas, identidade, sessões e perfis |
| `catalog` | Jogos, modos, regras de compatibilidade e importação |
| `matchmaking` | Vagas, disponibilidade, candidaturas, convites e renovações |
| `chat` | Conversas, mensagens e retenção |
| `social` | Amizades e bloqueios |
| `notifications` | Caixa de avisos, preferências e push |
| `recognition` | Categorias e elogios |
| `moderation` | Denúncias, evidências, restrições e auditoria |
| `metrics` | Aceites, vagas preenchidas e agregações |

## Comunicação entre módulos

- Coloque a API exposta, seus tipos de entrada e saída e os eventos públicos no pacote raiz do módulo. Um consumidor pode, por exemplo, importar `com.buddyup.api.catalog.GameCatalog` quando o Catálogo publicar esse contrato.
- Coloque controllers, entidades JPA, repositórios e implementações em subpacotes internos do módulo. Cada módulo grava os dados sob sua responsabilidade. Para consultar ou acionar outro módulo, use a API exposta por ele. Para reagir a uma mudança, consuma um evento público.
- Declare o fornecedor da API ou do evento em `allowedDependencies` no módulo consumidor. O teste `ModuleBoundariesTest` executa o Spring Modulith para detectar acessos a internos, dependências fora da lista e ciclos.

## Persistência e migrações

- Spring Data JPA com Hibernate é o padrão para repositórios. O Hibernate roda com `ddl-auto=validate`. O Flyway aplica as mudanças de estrutura antes da validação do Hibernate.
- A migração `V1__create_buddyup_schema.sql` cria o esquema `buddyup` em PostgreSQL vazio. As tabelas de domínio entram nas entregas dos módulos donos. O Hibernate usa `buddyup` como esquema padrão e o Flyway guarda o histórico em `public.flyway_schema_history`. Qualifique tabelas com `buddyup.` ao escrever SQL nas migrações ou consultas nativas.
- Adicione SQL em `backend/src/main/resources/db/migration/` com o nome `V<número>__<descrição_em_inglês>.sql`. Corrija migrações já aplicadas por meio de uma nova versão. Inclua a migração e os testes no PR da mudança.
- Rode `./mvnw verify` em `backend/` ou `.\mvnw.cmd verify` no Windows. Com o Docker ativo, o teste inicia a aplicação com um PostgreSQL vazio e temporário, verifica a migração registrada no Flyway e confirma a resposta HTTP de saúde do banco.

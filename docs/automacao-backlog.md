# Automação do backlog do BuddyUp

O workflow mantém as etapas do GitHub Project BuddyUp de acordo com as dependências e pendências explícitas.

1. Configure o secret BUDDYUP_PROJECT_TOKEN com uma credencial do GitHub que possa editar o Project pessoal BuddyUp. Um token clássico com escopo project é uma opção documentada para a API de Projects. Cadastre a credencial no GitHub.
2. Use as etiquetas `aguarda-decisão` e `aguarda-marco` para pendências que não estejam representadas por um vínculo nativo. Para marcos como M3, também é possível criar uma issue de controle e usar **Bloqueada por**.
3. Integre os arquivos na main. Na aba Actions, execute Sincronizar backlog do BuddyUp com a opção de prévia marcada. Revise o plano antes de executar manualmente sem a prévia.

A automação reavalia o quadro ao abrir, fechar, reabrir ou atribuir uma issue, ao alterar suas etiquetas e ao mudar o estado de revisão de um PR do mesmo repositório para a main. A execução de hora em hora também cobre alterações nos vínculos. A execução com acesso ao quadro usa os arquivos da main.

| Estado | Regra |
|---|---|
| Backlog | Issues novas entram nessa coluna. Issues com dependência ou pendência explícita ficam nela até serem liberadas. Se uma issue pronta voltar a ter bloqueio, retorna para cá. |
| Pronta | Na sincronização, uma issue aberta no Backlog fica pronta se todas as dependências tiverem sido fechadas como concluídas e não houver decisão ou marco pendente. A etiqueta `épico` indica um card de agrupamento, que tem seu estado gerenciado separadamente. |
| Em andamento | Uma pessoa assume uma issue pronta, ou abre um PR em rascunho para entregar a issue inteira. Um PR que volta a rascunho retorna o cartão de revisão para andamento se não houver outro PR pronto nem etiqueta revisar. |
| Em revisão | Um PR do BuddyUp para a main, aberto e pronto, tem vínculo de fechamento com a issue. Em entregas sem PR, aplique a etiqueta revisar. |
| Concluída | A issue foi fechada com o motivo concluído. |

Use `Closes #42` quando o PR entrega a issue inteira. Para entregas parciais, use `Parte de #42`. Essa referência sozinha não muda a coluna. Uma issue em andamento ou em revisão com um novo bloqueio conserva sua coluna e aparece no log. Se a issue estiver em revisão e seu PR for fechado sem merge, revise o cartão manualmente.

Se a execução for manual, mantenha a opção de prévia marcada para ver as mudanças antes de aplicá-las. Para validar a etiqueta de revisão, crie revisar no repositório e aplique-a quando a entrega estiver pronta para ser conferida.

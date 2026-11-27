# Automação do backlog do BuddyUp

O workflow mantém as etapas do GitHub Project BuddyUp de acordo com a triagem e as dependências.

1. Configure o secret BUDDYUP_PROJECT_TOKEN com uma credencial do GitHub que possa editar o Project pessoal BuddyUp. Um token clássico com escopo project é uma opção documentada para a API de Projects. Cadastre a credencial no GitHub.
2. Crie as etiquetas triada, aguarda-decisão e aguarda-marco. Use triada quando escopo e critérios já estão revisados. Use as outras etiquetas para bloqueios que não estejam representados por um vínculo nativo. Para marcos como M3, também é possível criar uma issue de controle e usar Bloqueada por. CAM-01 continua sem triada enquanto estiver adiada. QA-06 depende de M3.
3. Integre os arquivos na main. Na aba Actions, execute Sincronizar backlog do BuddyUp com a opção de prévia marcada. Revise o plano antes de executar manualmente sem a prévia.

A automação reavalia o quadro ao abrir, fechar, reabrir ou atribuir uma issue, ao alterar suas etiquetas e ao mudar o estado de revisão de um PR do mesmo repositório para a main. A execução de hora em hora também cobre alterações nos vínculos. A execução com acesso ao quadro usa os arquivos da main.

| Estado | Regra |
|---|---|
| Backlog | Issues abertas que ainda não estão no quadro são adicionadas nessa coluna. Se uma issue pronta voltar a ter bloqueio, ela também retorna para cá. |
| Pronta | A issue tem a etiqueta triada, todas as dependências foram fechadas como concluídas e não há decisão ou marco pendente. |
| Em andamento | Uma pessoa assume uma issue pronta, ou abre um PR em rascunho para entregar a issue inteira. Um PR que volta a rascunho retorna o cartão de revisão para andamento se não houver outro PR pronto nem etiqueta revisar. |
| Em revisão | Um PR do BuddyUp para a main, aberto e pronto, tem vínculo de fechamento com a issue. Em entregas sem PR, aplique a etiqueta revisar. |
| Concluída | A issue foi fechada com o motivo concluído. |

Use `Closes #42` quando o PR entrega a issue inteira. Para entregas parciais, use `Parte de #42`. Essa referência sozinha não muda a coluna. Uma issue em andamento ou em revisão com um novo bloqueio conserva sua coluna e aparece no log. Se a issue estiver em revisão e seu PR for fechado sem merge, revise o cartão manualmente.

Se a execução for manual, mantenha a opção de prévia marcada para ver as mudanças antes de aplicá-las. Para validar a etiqueta de revisão, crie revisar no repositório e aplique-a quando a entrega estiver pronta para ser conferida.

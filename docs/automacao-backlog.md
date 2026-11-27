# Automação do backlog do BuddyUp

O workflow mantém as etapas do GitHub Project BuddyUp de acordo com a triagem e as dependências.

1. Configure o secret BUDDYUP_PROJECT_TOKEN com uma credencial do GitHub que possa editar o Project pessoal BuddyUp. Um token clássico com escopo project é uma opção documentada para a API de Projects. Cadastre a credencial no GitHub.
2. Crie as etiquetas triada, aguarda-decisão e aguarda-marco. Use triada quando escopo e critérios já estão revisados. Use as outras etiquetas para bloqueios que não estejam representados por um vínculo nativo. Para marcos como M3, também é possível criar uma issue de controle e usar Bloqueada por. CAM-01 continua sem triada enquanto estiver adiada. QA-06 depende de M3.
3. Integre os arquivos na main. Na aba Actions, execute Sincronizar backlog do BuddyUp com a opção de prévia marcada. Revise o plano antes de executar manualmente sem a prévia.

A automação reage ao fechamento e à reabertura de issues e às mudanças nas etiquetas. Uma execução de hora em hora também cobre alterações nos vínculos. Apenas fechamentos como concluído satisfazem dependências. Cartões em andamento ou revisão conservam o estado se voltarem a ter bloqueio; o workflow registra um aviso no log.

O script atualiza apenas cartões já presentes no quadro. A entrada de novas issues é configurada separadamente no GitHub Projects.

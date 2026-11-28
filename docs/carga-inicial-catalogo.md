# Carga inicial do catálogo

A [issue #21](https://github.com/lucasccesar/BuddyUp/issues/21) cria as tabelas de catálogo no PostgreSQL e importa um snapshot XLSX autorizado da planilha unificada do BuddyUp. A migração Flyway V4 cria a estrutura. A aplicação do snapshot é um comando explícito após a migração.

## Validar e aplicar

Exporte a planilha unificada como XLSX e guarde o arquivo em um local interno. O relatório registra o SHA-256 dos bytes que foram validados. Rode os comandos a partir da raiz do BuddyUp, com Python 3.10 ou superior. No PowerShell:

```powershell
python -m venv "$env:TEMP/buddyup-catalog-venv"
$catalogPython = "$env:TEMP/buddyup-catalog-venv/Scripts/python.exe"
& $catalogPython -m pip install -r backend/catalog/requirements.txt
& $catalogPython -B backend/catalog/import_catalog.py 'C:\Temp\catalogo.xlsx' --report "$env:TEMP/catalogo-validacao.json"
```

No Linux ou macOS, use o executável `bin/python` do ambiente virtual. A validação verifica os cabeçalhos das 20 abas, as chaves, os tipos e os vínculos na mesma origem. O snapshot deve conter valores, sem fórmulas ou erros de célula. A coluna `rule_value` mantém o texto da planilha, que pode conter JSON ou uma descrição. Se houver erros, o relatório mostra a aba, a linha, o campo e o motivo. A validação sem `--apply` funciona sem banco.

Para aplicar, suba a API da branch para que o Flyway execute a migração V4. Em outro terminal, com o mesmo executável e o mesmo arquivo, configure `BUDDYUP_CATALOG_DSN` com a conexão do PostgreSQL que recebeu a migração. Use as credenciais do ambiente pretendido. Exemplo para o Compose local com a porta padrão:

```powershell
$catalogPython = "$env:TEMP/buddyup-catalog-venv/Scripts/python.exe"
$env:BUDDYUP_CATALOG_DSN = 'postgresql://buddyup:buddyup@127.0.0.1:15432/buddyup'
& $catalogPython -B backend/catalog/import_catalog.py 'C:\Temp\catalogo.xlsx' --report "$env:TEMP/catalogo-validacao.json" --apply
```

A aplicação valida o arquivo novamente, grava as 17 tabelas de referência em uma transação e registra o hash da carga. Reaplicar o mesmo snapshot preserva os IDs e a contagem. Um snapshot diferente sincroniza o catálogo inicial, inclusive a remoção das linhas ausentes. Use este comando para a carga inicial. A gestão de alterações após o lançamento é tratada na [issue #23](https://github.com/lucasccesar/BuddyUp/issues/23).

## Origem e seleção ativa

- O PostgreSQL guarda `source_catalog` junto das chaves internas. Os vínculos entre jogo, modo, plataforma, rank, função, servidor e mapa usam a mesma origem. Registros equivalentes de origens diferentes mantêm suas relações na planilha.
- As projeções `buddyup.catalog_active_games` e `buddyup.catalog_active_game_modes` expõem IDs estáveis e colunas destinadas à leitura pela aplicação. Jogos inativos e modos inativos ou de jogos inativos ficam fora dessas projeções. Os registros de referência preservam as flags de disponibilidade e os resultados explícitos de incompatibilidade. A API e a avaliação das regras ficam na [issue #22](https://github.com/lucasccesar/BuddyUp/issues/22).
- As três abas de dados de jogadores fazem parte da validação estrutural. O snapshot inicial vem com elas vazias. Se alguma contiver dados, o relatório indica as linhas e a importação é recusada. O perfil e as métricas pertencem à [issue #26](https://github.com/lucasccesar/BuddyUp/issues/26).

## Testes

Com o Docker ativo e as dependências instaladas, execute `python -B -m unittest discover -s backend/catalog -v` usando o Python do ambiente virtual. A suíte cria seu próprio PostgreSQL descartável, aplica as migrações e verifica referências, seleção ativa, campos públicos, rejeição de linhas inválidas e reimportação.

Na validação da #21, o snapshot com SHA-256 `352eaceb4b396aa14b190dd35f605bf017c5466d81d43f938c0fab5fb8ca5b8e` passou nas 20 abas sem erros. A carga e a reaplicação no PostgreSQL 17 produziram 85.388 registros de referência, com 368 jogos e 1.325 modos nas projeções ativas.

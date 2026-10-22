# ADR 0007: GitFlow com develop, release e main

**Status:** Aceito · **Data:** 2026-10-22

## Contexto
Até a Sprint 2 tudo ia direto para a `main` por squash. Com os deploys em dev, homolog e produção (#34) chegando,
cada ambiente precisa de uma branch, e só código que passou na esteira inteira pode chegar à produção.

## Decisão
- Fluxo: branch de trabalho (`feat/`, `fix/`, `ci/`, `docs/`) → `develop` (dev) → `release/0.N.0` (homolog) →
  `main` (produção). N = número da sprint. A `develop` é a branch padrão.
- Métodos de merge:

  | PR | Método | Por quê |
  |---|---|---|
  | trabalho → `develop` | squash | um commit por issue na `develop` |
  | `release/0.N.0` → `main` | merge commit | os commits da `main` são os mesmos da `develop` |
  | `main` → `develop` (back-merge) | merge commit | traz o merge commit da release de volta, sem conflito |

  Squash na `main` criaria commits que não existem na `develop`, e todo back-merge passaria a dar conflito.
- Rulesets em `develop` e `main`, sem exceção nem para o admin: só via PR, sem force push nem deleção, com os checks
  `verify`, `analyze (java-kotlin)`, `CodeQL` e `gitleaks` obrigatórios. A `main` aceita só merge commit e exige
  também o `source-branch`. A `develop` aceita squash e merge commit.
- Release só pelo workflow `cut-release.yml`: ele cria a `release/0.N.0` apenas se `api`, `CodeQL` e `gitleaks`
  passaram no **HEAD atual** da `develop` (a última execução pode ser de um commit antigo).
- O merge da release na `main` cria a tag `v0.N.0` e uma GitHub Release (`tag-release.yml`).
- Build once, deploy many: a imagem será gerada uma vez, na `develop`, e a mesma imagem sobe em homolog e
  produção. O que foi testado é exatamente o que vai para a produção. O build e a publicação ficam na #34.

## Limites do GitHub e como foram contornados
- O ruleset só olha a branch de destino: não dá para exigir squash só para as features. A `develop` aceita os dois
  métodos; "feature entra com squash" é regra do CLAUDE.md.
- O ruleset não restringe a branch de origem de um PR. O check `source-branch` (`branch-policy.yml`) reprova PR para
  a `main` que não venha de `release/0.N.0` deste repositório.
- Ações feitas com o `GITHUB_TOKEN` não disparam outros workflows. Por isso o `cut-release.yml` só cria a branch, e
  o PR para a `main` é aberto à mão; aberto pelo workflow, os checks obrigatórios nunca rodariam.

## Consequências
- O `api.yml` roda em todo PR, sem filtro de caminho: um check obrigatório filtrado ficaria pendente para sempre
  num PR só de docs. Quando a api não mudou, os testes são pulados e o check termina verde. No push sempre roda tudo.
- Sem hotfix por enquanto: correção urgente passa pela `develop` e por uma release nova. Vira decisão quando houver
  produção de verdade (#34).
- Depois de toda release, alguém precisa lembrar do back-merge `main → develop`.
- A release de uma sprint sai no fim dela; o que entrar na `develop` depois do corte fica para a próxima.

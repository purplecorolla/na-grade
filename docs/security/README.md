# Segurança na esteira

O que roda automaticamente em cada mudança, onde ver o resultado e o que fazer quando algo acusa.
Fundação criada na #26; o resto (Trivy, SBOM, ZAP, ASVS, threat model) está no épico #33.

## O que roda e quando

| Ferramenta | O que procura | Quando | Check no PR |
|---|---|---|---|
| CodeQL (`codeql.yml`) | Falhas de segurança no código Kotlin (SAST, queries `security-extended`) | Todo PR, push na `main` e toda segunda | `CodeQL / analyze (java-kotlin)` |
| gitleaks (`gitleaks.yml`) | Segredos nos commits do PR | Todo PR e push na `main` | `gitleaks` |
| JaCoCo (`api.yml`) | Cobertura de linhas abaixo de 70% (sem a classe main e o pacote `config`) | No `./mvnw verify`, em todo PR que muda a api | `verify` |
| Dependabot (`dependabot.yml`) | Dependências desatualizadas e vulneráveis (maven, actions, imagens do compose) | Toda segunda; security updates assim que sai o alerta | PR próprio |
| Secret scanning + push protection (GitHub) | Tokens de provedores conhecidos | No `git push` (bloqueia antes de subir) | — |

Os três checks acima entram como obrigatórios nas regras de branch (#27).

## Onde ver
- **Aba Security** do repositório: alertas do CodeQL (Code scanning), do Dependabot e do Secret scanning.
- **No PR:** o CodeQL comenta na linha e, quando dá, o Copilot Autofix sugere a correção; o gitleaks comenta o achado.
- **Cobertura:** artefato `jacoco-report` de cada execução da esteira, ou localmente em
  `services/api/target/site/jacoco/index.html` depois do `./mvnw verify`.

## Rodar localmente
```bash
cd services/api && ./mvnw verify                        # testes + cobertura (falha abaixo de 70%)
cd services/api && ./mvnw verify -Dcoverage.minimum=0.99 # ver o gate falhando

# gitleaks no histórico, com os valores mascarados (mesma versão da esteira)
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --config /repo/.gitleaks.toml --redact
```
No Git Bash, prefixe o `docker run` com `MSYS_NO_PATHCONV=1` para o caminho `/repo` não ser convertido.
Varra o **git**, não a pasta: o modo `dir` acusa o `.env` local, que está no `.gitignore` e nunca vai para o repositório.

## Quando algo acusa

### Segredo de verdade (gitleaks ou push protection)
1. **Revogue primeiro** no provedor (Spotify, setlist.fm...). Apagar o commit não basta: o repositório é público.
2. Gere um valor novo e coloque só no `.env` (ou no secret do ambiente).
3. Depois limpe o commit, se ainda não foi para a `main`.

### Valor falso de teste (falso positivo do gitleaks)
- Preferido: o comentário `gitleaks:allow` **na mesma linha** do valor. Fica explícito para quem lê o teste.
  ```kotlin
  val key = "fake-key-for-tests-only" // gitleaks:allow
  ```
- Só quando o comentário mudaria o valor (num `.properties`, o que vem depois do `=` faz parte do valor) ou o
  valor já está no histórico: uma entrada no `.gitleaks.toml`, presa ao arquivo **e** ao valor exato (`condition = "AND"`).
- Nunca libere um diretório inteiro nem uma regra inteira.

### Alerta do CodeQL
- Corrija no mesmo PR (o Autofix ajuda). Se for falso positivo, feche o alerta na aba Security com o motivo
  ("False positive" ou "Used in tests") e explique no PR.

### PR do Dependabot
- Minor/patch vêm agrupados: com a esteira verde, pode fazer o merge.
- Major vem sozinho: ler o changelog; se pedir mudança de código, vira issue.
- Security update tem prioridade sobre o resto da sprint.

## Antes de abrir um PR
Rodar `/security-review` no Claude Code (regra no CLAUDE.md). Os achados confirmados são corrigidos no PR ou viram issue.

# Na Grade

App que te deixa pronto para o show: conhece o gosto do usuário pelo Spotify, monta a
playlist da setlist prevista (setlist.fm), avisa de shows e organiza o rolê com os amigos.
Projeto de portfólio, trabalhado como numa empresa.

## Papéis
- Caio é PO e dev. Você é o tech lead em par.
- Respostas curtas, em blocos, com passo a passo. Português do Brasil.
- Proponha o plano antes de editar arquivos e espere aprovação.
- Explique o "porquê" das decisões; o objetivo também é aprender.

## Stack e estrutura (monorepo, ADR 0001)
- `services/api`: Kotlin + Spring Boot, JVM 21 (Maven, pacote `app.nagrade.api`, ADR 0005)
- `services/radar`: Python (Sprint 3)
- `services/notifier`: alertas no Telegram (Sprint 3)
- `infra/docker-compose.yml`: PostgreSQL 16 e Redis 7. Kafka a partir da Sprint 3
- `docs/adr`: decisões de arquitetura. `docs/spikes`: investigações
- Ambiente: Windows 11, Git Bash, IntelliJ, Docker Desktop

## Comandos
- Infra: sobe sozinha ao rodar a api (spring-boot-docker-compose, lê credenciais dos containers). Na mão: `docker compose --env-file .env -f infra/docker-compose.yml up -d`
- Zerar o banco local (ex.: mudou usuário/senha no `.env`): `docker compose --env-file .env -f infra/docker-compose.yml down -v`
- Testes: `cd services/api && ./mvnw verify`
- Rodar: `cd services/api && ./mvnw spring-boot:run` (já usa o perfil `dev`) ou ▶ no `ApiApplication.kt`, com working dir
  `services/api` e VM option `-Dspring.profiles.active=dev` (IntelliJ Community não tem o campo "Active profiles")
- Testar pelo navegador (cadastro, login, conectar Spotify): `http://127.0.0.1:8080/playground.html`, só no perfil `dev`
- Health: `http://127.0.0.1:8080/actuator/health`
- Testar a API à mão: importar `services/api/insomnia/na-grade.yaml` no Insomnia (13.x). Endpoint novo entra na collection no mesmo PR
- Validar a collection pela linha de comando: `inso run collection wrk_nagrade_api -w services/api/insomnia/na-grade.yaml -e env_nagrade_base`
  (o runner do inso não troca o cookie `SESSION` depois do logout: requisições logadas ficam antes do Logout)

## Idioma
- Código em inglês: pacotes, classes, funções, variáveis, nomes de teste, comentários, banco e contrato da API
- Erros da API em inglês com `code` estável (ex.: `email_already_registered`); o front traduz pelo `code`
- Em pt-BR: ADRs, spikes, README, CLAUDE.md, commits, issues e PRs

## Decisões já tomadas
- ADR 0002: Spotify via OAuth2 Authorization Code + PKCE; tokens criptografados (AES-GCM) no banco
- ADR 0003: provedores de música atrás da porta `MusicProvider`; `SpotifyAdapter` é a 1ª implementação
- ADR 0004: login próprio (e-mail/senha com BCrypt; Google depois). Spotify é só conexão opcional
- ADR 0005: api em Kotlin (JVM 21 e Maven continuam)
- ADR 0006: sessão no Redis com Spring Session, em vez de JWT. CSRF no modo SPA (`GET /auth/csrf` gera o cookie)
- Redirect URI do Spotify: `http://127.0.0.1:8080/login/oauth2/code/spotify` (não usar "localhost")
- Migrations com Flyway (`V1__...sql`). Testes de integração com Testcontainers; adapters com WireMock

## Achados do spike (docs/spikes/apis.md)
- Spotify mudou a API em fev/2026 no modo de desenvolvimento:
  - Criar playlist: `POST /me/playlists`. Adicionar músicas: `POST /playlists/{id}/items`
  - Busca: `limit` máximo 10
  - Removidos: `GET /artists/{id}/top-tracks`, browse, e os campos `popularity` e `followers`
  - `genres` do artista está deprecated (doc conferida em 2026-10-21): não usar
  - Dono precisa de Premium; até 5 usuários autorizados
- setlist.fm: buscar por nome traz bandas cover. Fluxo certo:
  `GET /rest/1.0/search/artists?artistName=X&sort=relevance` → aceitar só nome idêntico →
  guardar o `mbid` → `GET /rest/1.0/artist/{mbid}/setlists`
- setlist.fm: 2 req/s e 1440/dia. Cache no Redis é obrigatório
- Tutoriais anteriores a fev/2026 podem estar errados; confira a documentação oficial

## Segurança (inegociável)
- Nunca leia, mostre ou commite o `.env`. Segredos só por variável de ambiente
- Cada variável do `.env` tem um leitor só. `DB_*`/`REDIS_*` são do Docker Compose: no dev local a api pega a conexão
  dos containers (os `${DB_*}` do `application.properties` só valem fora do Compose). `SPOTIFY_*` e `TOKEN_ENCRYPTION_KEY` são da api, que
  importa o `.env` como `.properties` (valores sem aspas)
- Tokens de provedores ficam cifrados no banco (AES-GCM, `EncryptedTokenConverter` na coluna, chave em `TOKEN_ENCRYPTION_KEY`).
  Coluna nova com token ou segredo usa o mesmo converter; nunca logue o valor cifrado ou decifrado
- Toda variável nova entra no `.env.example`, sem valor
- Nada de chave, senha ou token hardcoded, nem em teste (use valores falsos)

## Como trabalhamos (Scrum)
- Sprints de 1 semana. Board "Na Grade" no GitHub Projects:
  Backlog → Sprint atual → Fazendo → Revisão → Pronto. No máximo 2 itens em Fazendo
- Fluxo: issue → branch (`feat/`, `fix/`, `ci/`, `docs/`) → PR com `Closes #N` → squash merge
- Commits no padrão Conventional Commits, em português: `feat(api): ...`, `docs: ...`
- Pronto = testes passando, esteira verde e documentação atualizada
- Daily: comentário na issue "Diário da Sprint N" (Fiz / Vou fazer / Travei em)

## Chat × Claude Code
- Claude Desktop (chat) é a sala de reunião: planejamento da sprint, histórias e critérios, arquitetura e rascunho
  de ADRs, explicar o "porquê", destravar, review e retro
- Claude Code é a mesa de trabalho: executa uma issue por vez até o PR (código, testes, migrations, git, esteira)
  e mantém o CLAUDE.md atualizado
- A ponte é o GitHub (issues, PRs, `docs/adr`) e este arquivo. O chat não vê o código nem a memória local:
  decisão tomada no chat só vale depois de registrada na issue ou num ADR
- O chat decide o caminho; o Claude Code transforma em plano verificado no código antes de editar. Se o código
  contradisser a decisão, registre o conflito na issue e a dúvida volta para o chat

## Sprint 1 (atual)
Objetivo: o usuário cria a conta, conecta o Spotify e vê os artistas mais ouvidos.
Ordem: #8 CI → #15 migrar para Kotlin → #18 código em inglês → #9 cadastro e login por e-mail → #10 conectar Spotify → #11 criptografar tokens
→ #12 artistas mais ouvidos. #13 Login com Google fica no Backlog.
Detalhes e critérios de cada item: `gh issue view N`.

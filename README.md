# na-grade

[![api](https://github.com/purplecorolla/na-grade/actions/workflows/api.yml/badge.svg)](https://github.com/purplecorolla/na-grade/actions/workflows/api.yml)

## Rodar localmente

Pré-requisitos: JDK 21 e Docker Desktop. Copie o `.env.example` para `.env` e preencha (o `.env` nunca vai para o git).

A `TOKEN_ENCRYPTION_KEY` (chave AES-256 dos tokens do Spotify no banco) é gerada uma vez, no Git Bash:

```bash
openssl rand -base64 32
```

Cole a saída no `.env`, sem aspas. Trocar a chave invalida os tokens salvos (é preciso reconectar o Spotify).

A `SETLISTFM_API_KEY` sai de [setlist.fm/settings/api](https://www.setlist.fm/settings/api) (conta grátis). Sem ela, como sem as
do Spotify, a api não sobe. A api se limita a 1400 chamadas por dia (a chave permite 1440) e guarda as respostas no Redis.

```bash
cd services/api && ./mvnw spring-boot:run   # sobe PostgreSQL e Redis sozinho, no perfil dev
```

No IntelliJ (Community): ▶ no `ApiApplication.kt` e, em **Edit Configurations...**:

- **Working directory**: a pasta `services/api` (a api acha o compose e o `.env` a partir dela)
- **Modify options → Add VM options**: `-Dspring.profiles.active=dev` (o ▶ não passa pelo Maven, que já liga o `dev`)

No log deve aparecer `The following 1 profile is active: "dev"`. Só uma instância por vez: se a 8080 estiver
ocupada, pare o `mvnw` do terminal antes.

## Conectar o Spotify pelo navegador

1. Abra `http://127.0.0.1:8080/playground.html`. Use sempre `127.0.0.1`: os cookies de `localhost` são outros.
2. Cadastre-se e faça login, depois clique em **Connect Spotify** e autorize.
3. Na volta, o playground lista as conexões.
4. **GET /me/top-artists** mostra seus artistas mais ouvidos (`period`: `short` ~4 semanas, `medium` ~6 meses, `long` ~1 ano).
   O token do Spotify é renovado sozinho; se o Spotify recusar, a resposta é 401 `provider_reconnect_required` e é só conectar de novo.
5. **Disconnect Spotify** apaga os tokens do nosso banco.
6. Em **Show**, com o id de um artista (vem no top artists) e a data do show:
   **GET setlists** (shows recentes do setlist.fm), **GET predicted setlist** (a previsão) e **POST playlist**
   (cria a playlist privada no seu Spotify; gerar de novo para o mesmo artista e data atualiza a mesma).

Conectou o Spotify antes da #30? A playlist pede um scope novo (`playlist-read-private`): a resposta vem 401
`provider_reconnect_required` e é só conectar de novo, uma vez.

O Spotify não tem API de revogação: para tirar o acesso de vez, remova o app em
[spotify.com/account/apps](https://www.spotify.com/account/apps/).

O playground só existe no perfil `dev`. Fora dele, `/playground.html` dá 404.

## Branches e releases
GitFlow ([ADR 0007](docs/adr/0007-gitflow.md)): branch de trabalho → `develop` (dev) → `release/0.N.0` (homolog) → `main` (produção).
- PRs de trabalho vão para a `develop`, com squash. Nada entra na `develop` ou na `main` sem PR e esteira verde.
- Uma release por sprint, só na review da sprint (`v0.1.0` = Sprint 1 + fundação; a próxima é a `v0.2.0`).
- Release: Actions → **cut-release** (só corta com a esteira verde no HEAD da `develop`) → PR
  `release/0.N.0 → main` com merge commit → tag `v0.N.0` automática → back-merge `main → develop` com merge commit.
- Segurança na esteira: [docs/security/README.md](docs/security/README.md).

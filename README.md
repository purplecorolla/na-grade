# na-grade

[![api](https://github.com/purplecorolla/na-grade/actions/workflows/api.yml/badge.svg)](https://github.com/purplecorolla/na-grade/actions/workflows/api.yml)

## Rodar localmente

Pré-requisitos: JDK 21 e Docker Desktop. Copie o `.env.example` para `.env` e preencha (o `.env` nunca vai para o git).

```bash
cd services/api && ./mvnw spring-boot:run   # sobe PostgreSQL e Redis sozinho, no perfil dev
```

No IntelliJ: ▶ no `ApiApplication.kt`, com working dir `services/api` e **Active profiles: `dev`**.

## Conectar o Spotify pelo navegador

1. Abra `http://127.0.0.1:8080/playground.html`. Use sempre `127.0.0.1`: os cookies de `localhost` são outros.
2. Cadastre-se e faça login, depois clique em **Connect Spotify** e autorize.
3. Na volta, o playground lista as conexões. **Disconnect Spotify** apaga os tokens do nosso banco.

O Spotify não tem API de revogação: para tirar o acesso de vez, remova o app em
[spotify.com/account/apps](https://www.spotify.com/account/apps/).

O playground só existe no perfil `dev`. Fora dele, `/playground.html` dá 404.

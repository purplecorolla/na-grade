# ADR 0004: Login próprio, Spotify como conexão opcional

**Status:** Aceito · **Data:** 2026-10-20

## Contexto
No modo de desenvolvimento, o Spotify limita o app a 5 usuários autorizados e exige Premium do dono. Se o login dependesse dele, o app inteiro teria esse limite.

## Decisão
- O login do app é por e-mail e senha (BCrypt) ou Google.
- O Spotify é uma conta conectada, vinculada depois do login.

## Consequências
- Qualquer pessoa usa o app (radar, rolês, avaliações); só os recursos de playlist exigem o Spotify.
- Mais trabalho na Sprint 1: cadastro, login e vínculo de contas.
- Abre espaço para outros provedores (ADR 0003).

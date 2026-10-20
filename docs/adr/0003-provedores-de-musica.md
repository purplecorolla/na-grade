# ADR 0003: Provedores de música atrás de uma interface

**Status:** Aceito · **Data:** 2026-10-20

## Contexto
O Spotify mudou a Web API duas vezes em 15 meses: em fev/2026 removeu top-tracks do artista, renomeou /tracks para /items e trocou o endpoint de criar playlist (ver docs/spikes/apis.md).

## Decisão
Usar portas e adaptadores: o domínio conhece só a interface `MusicProvider` (top artistas, busca, criar playlist). O `SpotifyAdapter` é a primeira implementação.

## Consequências
- Uma nova mudança do Spotify fica restrita ao adapter.
- Deezer ou YouTube Music entram como novos adapters, sem mexer no domínio.
- Custa uma camada a mais de código e testes por provedor.

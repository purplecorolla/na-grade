# Spike: validação das APIs (Sprint 0)

| Recurso | Endpoint | Scope | Disponível | Observações |
|---|---|---|---|---|
| Artistas mais ouvidos | GET /me/top/artists | user-top-read | Sim (testado) | limit máx. 50; `genres` deprecated. Testado na #12 com conta real: 20 artistas, todos com imagem |
| Busca de músicas | GET /search | nenhum | Sim (testado) | limit máx. 10 desde fev/2026 |
| Criar playlist | POST /me/playlists | playlist-modify-public/private | Sim (doc) | Substituiu /users/{id}/playlists |
| Adicionar músicas | POST /playlists/{id}/items | playlist-modify-public/private | Sim (doc) | Renomeado de /tracks |
| Artista por id | GET /artists/{id} | nenhum | Sim (doc, conferido em 2026-10-22) | Substitui o `GET /artists` em lote, removido em fev/2026. Usado na #28 para o nome do artista |
| Top tracks do artista | GET /artists/{id}/top-tracks | - | Não | Removido em fev/2026 |
| Achar artista | GET /rest/1.0/search/artists | x-api-key | Sim (testado) | Retorna o mbid; ordenar por relevance |
| Setlists do artista | GET /rest/1.0/artist/{mbid}/setlists | x-api-key | Sim (testado) | Buscar por nome traz bandas cover; usar mbid |

## Limites
- Spotify (modo dev): dono precisa de Premium; até 5 usuários autorizados.
- setlist.fm: 2 req/s e 1440/dia, então cache é obrigatório.

## Decisões
- Guardar o mbid do artista no banco depois da primeira busca, para não repetir a chamada.
- Mapeamento Spotify → setlist.fm: buscar por nome com sort=relevance e aceitar só nome idêntico.

## Atualização (Sprint 1, 2026-10-21)
- `GET /me/top/artists` testado com conta real na #12: `time_range` short/medium/long e `images` (a mais larga primeiro) funcionam.
- Além de `popularity` e `followers` (removidos), `genres` do artista aparece como deprecated na doc. A api não usa nenhum dos três.
- O token do Spotify vence em 1 hora. A renovação pelo refresh token foi testada com WireMock; ainda falta ver acontecer com a conta real.

## Plano B
Sem setlist recente: mostrar aviso e permitir montar a playlist pela busca.

## Fonte
developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide

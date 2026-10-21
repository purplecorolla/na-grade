# Spike: validação das APIs (Sprint 0)

| Recurso | Endpoint | Scope | Disponível | Observações |
|---|---|---|---|---|
| Artistas mais ouvidos | GET /me/top/artists | user-top-read | Sim (doc) | limit máx. 50; `genres` deprecated (doc em 2026-10-21). Teste real na #12 |
| Busca de músicas | GET /search | nenhum | Sim (testado) | limit máx. 10 desde fev/2026 |
| Criar playlist | POST /me/playlists | playlist-modify-public/private | Sim (doc) | Substituiu /users/{id}/playlists |
| Adicionar músicas | POST /playlists/{id}/items | playlist-modify-public/private | Sim (doc) | Renomeado de /tracks |
| Top tracks do artista | GET /artists/{id}/top-tracks | - | Não | Removido em fev/2026 |
| Achar artista | GET /rest/1.0/search/artists | x-api-key | Sim (testado) | Retorna o mbid; ordenar por relevance |
| Setlists do artista | GET /rest/1.0/artist/{mbid}/setlists | x-api-key | Sim (testado) | Buscar por nome traz bandas cover; usar mbid |

## Limites
- Spotify (modo dev): dono precisa de Premium; até 5 usuários autorizados.
- setlist.fm: 2 req/s e 1440/dia, então cache é obrigatório.

## Decisões
- Guardar o mbid do artista no banco depois da primeira busca, para não repetir a chamada.
- Mapeamento Spotify → setlist.fm: buscar por nome com sort=relevance e aceitar só nome idêntico.

## Plano B
Sem setlist recente: mostrar aviso e permitir montar a playlist pela busca.

## Fonte
developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide

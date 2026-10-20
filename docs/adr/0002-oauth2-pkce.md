# ADR 0002: Conexão com o Spotify via OAuth2 + PKCE

**Status:** Aceito · **Data:** 2026-10-20

## Contexto
O app precisa ler os artistas mais ouvidos e criar playlists em nome do usuário. O Spotify recomenda o Authorization Code com PKCE, e os tokens dão acesso à conta da pessoa.

## Decisão
- Usar Authorization Code + PKCE com Spring Security OAuth2 Client.
- Guardar access e refresh tokens no PostgreSQL, criptografados com AES-GCM.
- A chave de criptografia vem de variável de ambiente, nunca do código.

## Consequências
- Um vazamento do banco não expõe os tokens em texto puro.
- Quando o refresh token expirar ou for revogado, o app pede uma nova conexão.
- Girar a chave de criptografia exige recriptografar os tokens.

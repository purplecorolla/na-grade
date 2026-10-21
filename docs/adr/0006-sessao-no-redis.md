# ADR 0006: Sessão no Redis em vez de JWT

**Status:** Aceito · **Data:** 2026-10-20

## Contexto
O login próprio (ADR 0004) precisa manter o usuário autenticado entre requisições. As opções eram um JWT guardado no navegador ou uma sessão no servidor com um cookie.

## Decisão
- Sessão com Spring Session gravada no Redis. O navegador recebe só o cookie `SESSION` (`HttpOnly`, `SameSite=Lax`).
- Login por endpoint JSON (`POST /auth/login`), que troca o ID da sessão e o token CSRF antes de gravar o login (contra session fixation).
- CSRF ligado no modo SPA: cookie `XSRF-TOKEN` que o front devolve no header `X-XSRF-TOKEN`. `GET /auth/csrf` gera o cookie.
- Requisição sem login responde 401, sem redirect.

## Consequências
- Logout de verdade: apagar a sessão no Redis invalida o acesso na hora. Um JWT continuaria válido até expirar.
- Nenhum token fica no `localStorage`, onde estaria exposto a XSS.
- A api pode ter várias instâncias sem sessão presa a uma delas.
- Cada requisição autenticada consulta o Redis, que já é obrigatório pelo cache do setlist.fm.
- O front precisa enviar o token CSRF em todo POST, PUT e DELETE.
- Nos testes com MockMvc o Spring Session ignora `server.servlet.session.cookie.*`; os atributos do cookie são conferidos com o servidor real (`SessionCookieIntegrationTest`).

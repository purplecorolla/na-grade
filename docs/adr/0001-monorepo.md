# ADR 0001: Monorepo

**Status:** Aceito · **Data:** 2026-10-20

## Contexto
O Na Grade terá três serviços (api em Kotlin desde o ADR 0005, radar em Python e notifier) e uma pessoa desenvolvendo.

## Decisão
Todos os serviços, a infra e a documentação ficam num único repositório, em `services/`, `infra/` e `docs/`.

## Consequências
- Uma esteira só, com um job por serviço disparado por caminho alterado.
- Mudanças que cruzam serviços saem num único PR.
- Se o projeto crescer muito, os serviços podem ser separados depois.

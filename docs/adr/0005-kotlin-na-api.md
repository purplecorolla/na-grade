# ADR 0005: Kotlin na api

**Status:** Aceito · **Data:** 2026-10-20

## Contexto
A api nasceu em Java 21 (ADR 0001), mas tinha só a classe principal, a configuração de segurança e um teste. Trocar a linguagem agora custa pouco; depois da #9 o custo cresce a cada entidade e endpoint.

## Decisão
- A api passa a ser escrita em Kotlin. JVM 21, Spring Boot e Maven continuam.
- A versão do Kotlin vem do parent do Spring Boot (`${kotlin.version}`), para manter uma combinação compatível.
- O compilador usa os plugins `spring` (abre as classes anotadas para os proxies do Spring) e `jpa` (gera o construtor vazio que o Hibernate exige).
- `jackson-module-kotlin` para o JSON respeitar nulidade e valores padrão.

## Consequências
- Null-safety no compilador, `data class` para DTOs e menos boilerplate.
- O Spring Security tem uma DSL Kotlin, que passa a ser o padrão nas configurações.
- Tutoriais e exemplos em Java precisam ser traduzidos.
- O CI não muda: o Kotlin compila dentro do `./mvnw verify` com o JDK 21.

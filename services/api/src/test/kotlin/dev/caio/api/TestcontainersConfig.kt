package dev.caio.api

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

    // GenericContainer usa um genérico recursivo (SELF) que o Kotlin não infere; <Nothing> resolve
    @Bean
    @ServiceConnection(name = "redis")
    fun redis(): GenericContainer<*> =
        GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine")).apply { withExposedPorts(6379) }
}

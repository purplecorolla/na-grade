package app.nagrade.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// Test page for the browser flows until the frontend exists. Outside the dev profile it's a 404
@Configuration
@Profile("dev")
class PlaygroundConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/playground.html").addResourceLocations("classpath:/playground/")
    }
}

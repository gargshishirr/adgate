package io.github.gargshishirr.adgate.localinfra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The chaos switch is POST only, answers local tools such as curl, and refuses browsers and other hosts. */
@WebMvcTest(ChaosController.class)
@Import(LocalOnlyWebConfig.class)
class ChaosSwitchTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChaosRedis redis;

    @Test
    void aLocalPostSwitchesTheMode() throws Exception {
        when(redis.mode()).thenReturn(ChaosRedis.Mode.DOWN);

        mvc.perform(post("/chaos/redis/down")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("DOWN"));
        verify(redis).setMode(ChaosRedis.Mode.DOWN, 200);
    }

    @Test
    void itIsPostOnly() throws Exception {
        mvc.perform(get("/chaos/redis/down")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/chaos/redis/sideways")).andExpect(status().isBadRequest());
        verify(redis, never()).setMode(any(), anyLong());
    }

    @Test
    void browsersAndOtherHostsAreRefused() throws Exception {
        mvc.perform(post("/chaos/redis/down").header("Origin", "https://example.com")).andExpect(status().isForbidden());
        mvc.perform(post("/chaos/redis/down").with(request -> {
            request.setServerName("attacker.example");
            return request;
        })).andExpect(status().isForbidden());
        verify(redis, never()).setMode(any(), anyLong());
    }

    @Test
    void theServerAlwaysBindsToLoopback() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setAddress(InetAddress.getByName("0.0.0.0"));

        new LocalOnlyWebConfig.LoopbackOnly().customize(factory);

        assertThat(factory.getAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    void refusesToRunWithTheProdProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        ApplicationEnvironmentPreparedEvent event = new ApplicationEnvironmentPreparedEvent(
                null, new SpringApplication(LocalInfraApplication.class), new String[0], environment);

        assertThatThrownBy(() -> LocalInfraApplication.refuseProdProfile().onApplicationEvent(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local development only");
    }
}

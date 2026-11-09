package com.example.fluxnova.portal;

import jakarta.servlet.FilterChain;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NoTenantsFilterTest {

    private final Map<String, ProcessEngine> original = Map.copyOf(TenantEngineDirectory.engines());
    private final NoTenantsFilter filter = new NoTenantsFilter();

    @AfterEach
    void restoreDirectory() {
        TenantEngineDirectory.publish(original);
    }

    @Test
    void withoutAnyEngineThePortalAnswersWithAnExplanationInsteadOfAnError() throws Exception {
        TenantEngineDirectory.publish(Map.of());
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/fluxnova/app/welcome/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("15");
        assertThat(response.getContentAsString()).contains("No tenants are configured yet");
        verifyNoInteractions(chain);
    }

    @Test
    void withAnEngineTheRequestGoesOnToThePortal() throws Exception {
        TenantEngineDirectory.publish(Map.of("tenant-a", mock(ProcessEngine.class)));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fluxnova/app/welcome/tenant-a/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

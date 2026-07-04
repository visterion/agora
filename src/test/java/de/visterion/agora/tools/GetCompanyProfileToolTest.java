package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.Profile;
import de.visterion.agora.fetch.finnhub.ProfileService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetCompanyProfileToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsProfile() {
        ProfileService svc = Mockito.mock(ProfileService.class);
        var profile = mapper.createObjectNode().put("finnhubIndustry", "Technology").put("name", "Apple Inc");
        when(svc.profile(any())).thenReturn(new Profile("AAPL", profile));
        var r = new GetCompanyProfileTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString("")).isEqualTo("AAPL");
        assertThat(r.output().get("profile").get("finnhubIndustry").asString("")).isEqualTo("Technology");
    }

    @Test void missingSymbolUnavailable() {
        assertThat(new GetCompanyProfileTool(Mockito.mock(ProfileService.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        ProfileService svc = Mockito.mock(ProfileService.class);
        when(svc.profile(any())).thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no key", null));
        assertThat(new GetCompanyProfileTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }
}
